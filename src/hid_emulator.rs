//! 在服务端模拟一颗 CH9329：吃进 `ch9329.js` 发来的帧，吐出 HID 报告和应答。
//!
//! 纯逻辑，不碰文件。报告写进哪个 `/dev/hidg*`、USB 状态从哪读，都由调用方决定
//! （见 `serial_bridge` 里的 gadget 链路）。只覆盖 `ch9329.js` 实际发的命令。

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum HidTarget {
    Keyboard,
    AbsMouse,
    RelMouse,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum UsbState {
    Configured,
    Suspended,
    Disconnected,
}

#[derive(Clone, Copy, Debug)]
pub struct DeviceStatus {
    pub usb: UsbState,
    /// 被控机发来的键盘灯：bit0 NumLock、bit1 CapsLock、bit2 ScrollLock
    pub leds: u8,
}

#[derive(Debug, Default, PartialEq, Eq)]
pub struct Output {
    pub reports: Vec<(HidTarget, Vec<u8>)>,
    /// 要回给客户端的字节，可能是几帧应答首尾相接
    pub replies: Vec<u8>,
}

#[derive(Default)]
pub struct Emulator {
    pending: Vec<u8>,
    /// 绝对鼠标不像相对鼠标，松键要带着坐标，所以得记着最后一份
    last_abs: Option<Vec<u8>>,
}

const HEAD: [u8; 2] = [0x57, 0xAB];
/// CH9329 单帧数据最长 64 字节，更长的一定是错位，丢掉帧头重新找。
const MAX_DATA: usize = 64;

const CMD_GET_INFO: u8 = 0x01;
const CMD_KEYBOARD: u8 = 0x02;
const CMD_MOUSE_ABS: u8 = 0x04;
const CMD_MOUSE_REL: u8 = 0x05;
const CMD_GET_PARA_CFG: u8 = 0x08;
const CMD_SET_PARA_CFG: u8 = 0x09;
const CMD_SET_DEFAULT_CFG: u8 = 0x0C;
const CMD_RESET: u8 = 0x0F;

const STATUS_OK: u8 = 0x00;
const ERR_CMD: u8 = 0xE3;
const ERR_SUM: u8 = 0xE4;
const ERR_PARA: u8 = 0xE5;

/// `ch9329.js` 显示成 `V3.0`。
const VERSION: u8 = 0x30;

/// 出厂配置：模式 0、串口模式 0、地址 0、波特率 9600（大端）、包间隔 3ms，其余为 0。
/// 设置页选直连时不显示芯片那一节，这份只是让 `getParaCfg` 拿到个合法的长度。
const PARA_CFG: [u8; 50] = {
    let mut cfg = [0u8; 50];
    cfg[0] = 0x80;
    cfg[1] = 0x80;
    cfg[5] = 0x25;
    cfg[6] = 0x80;
    cfg[10] = 0x03;
    cfg
};

impl Emulator {
    pub fn new() -> Self {
        Self::default()
    }

    /// `status` 只在收到查信息时才调：它要读 sysfs，不该摊到每一包键鼠上。
    pub fn feed(&mut self, bytes: &[u8], status: &mut dyn FnMut() -> DeviceStatus) -> Output {
        self.pending.extend_from_slice(bytes);
        let mut out = Output::default();
        loop {
            let Some(start) = self.pending.windows(2).position(|pair| pair == HEAD) else {
                // 最后一个字节可能是被拆开的帧头的前半个
                let keep = usize::from(self.pending.last() == Some(&HEAD[0]));
                let garbage = self.pending.len() - keep;
                self.pending.drain(..garbage);
                break;
            };
            self.pending.drain(..start);
            if self.pending.len() < 5 {
                break;
            }
            let len = self.pending[4] as usize;
            if len > MAX_DATA {
                self.pending.drain(..2);
                continue;
            }
            let total = 6 + len;
            if self.pending.len() < total {
                break;
            }
            let cmd = self.pending[3];
            if checksum(&self.pending[..total - 1]) != self.pending[total - 1] {
                // 和 ch9329.js 的 _shiftFrame 一样只丢帧头：错位时后面可能就是一帧好的
                self.pending.drain(..2);
                out.replies.extend(error_reply(cmd, ERR_SUM));
                continue;
            }
            let frame: Vec<u8> = self.pending.drain(..total).collect();
            handle(cmd, &frame[5..5 + len], status, &mut out);
        }
        if let Some((_, report)) = out.reports.iter().rev().find(|(target, _)| *target == HidTarget::AbsMouse) {
            self.last_abs = Some(report.clone());
        }
        out
    }

    /// 控制端断开时要补发的报告：绝对鼠标最后一份要是还按着键，就发一份键松开、坐标不变的。
    /// 断开时下发的全释放帧只覆盖键盘和相对鼠标，绝对鼠标的键会一直按在被控机上。
    pub fn release_reports(&self) -> Vec<(HidTarget, Vec<u8>)> {
        let Some(last) = &self.last_abs else {
            return Vec::new();
        };
        if last.first().is_none_or(|buttons| *buttons == 0) {
            return Vec::new();
        }
        let mut release = last.clone();
        release[0] = 0;
        // 最后一字节是滚轮增量，重发会再滚一次
        if let Some(wheel) = release.last_mut() {
            *wheel = 0;
        }
        vec![(HidTarget::AbsMouse, release)]
    }
}

fn handle(cmd: u8, data: &[u8], status: &mut dyn FnMut() -> DeviceStatus, out: &mut Output) {
    let reply = match cmd {
        CMD_GET_INFO => {
            let now = status();
            let connected = u8::from(now.usb != UsbState::Disconnected);
            let asleep = if now.usb == UsbState::Suspended { 0x03 } else { 0x00 };
            reply(cmd, &[VERSION, connected, now.leds, asleep, 0, 0, 0, 0])
        }
        CMD_KEYBOARD if data.len() == 8 => {
            out.reports.push((HidTarget::Keyboard, data.to_vec()));
            reply(cmd, &[STATUS_OK])
        }
        CMD_MOUSE_ABS if data.len() == 7 && data[0] == 0x02 => {
            out.reports.push((HidTarget::AbsMouse, data[1..].to_vec()));
            reply(cmd, &[STATUS_OK])
        }
        CMD_MOUSE_REL if data.len() == 5 && data[0] == 0x01 => {
            out.reports.push((HidTarget::RelMouse, data[1..].to_vec()));
            reply(cmd, &[STATUS_OK])
        }
        CMD_KEYBOARD | CMD_MOUSE_ABS | CMD_MOUSE_REL => error_reply(cmd, ERR_PARA),
        CMD_GET_PARA_CFG => reply(cmd, &PARA_CFG),
        CMD_SET_PARA_CFG | CMD_SET_DEFAULT_CFG | CMD_RESET => reply(cmd, &[STATUS_OK]),
        _ => error_reply(cmd, ERR_CMD),
    };
    out.replies.extend(reply);
}

fn checksum(bytes: &[u8]) -> u8 {
    bytes.iter().fold(0u8, |sum, byte| sum.wrapping_add(*byte))
}

fn reply(cmd: u8, data: &[u8]) -> Vec<u8> {
    let mut bytes = vec![HEAD[0], HEAD[1], 0x00, cmd | 0x80, data.len() as u8];
    bytes.extend_from_slice(data);
    bytes.push(checksum(&bytes));
    bytes
}

fn error_reply(cmd: u8, code: u8) -> Vec<u8> {
    let mut bytes = vec![HEAD[0], HEAD[1], 0x00, cmd | 0xC0, 0x01, code];
    bytes.push(checksum(&bytes));
    bytes
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sum(bytes: &[u8]) -> u8 {
        bytes.iter().fold(0u8, |acc, byte| acc.wrapping_add(*byte))
    }

    /// 和 `ch9329.js` 一样组帧：地址固定 0。应答也是这个形状，所以期望值也用它拼。
    fn frame(cmd: u8, data: &[u8]) -> Vec<u8> {
        let mut bytes = vec![0x57, 0xAB, 0x00, cmd, data.len() as u8];
        bytes.extend_from_slice(data);
        bytes.push(sum(&bytes));
        bytes
    }

    fn status(usb: UsbState, leds: u8) -> impl FnMut() -> DeviceStatus {
        move || DeviceStatus { usb, leds }
    }

    fn feed(bytes: &[u8]) -> Output {
        Emulator::new().feed(bytes, &mut status(UsbState::Configured, 0))
    }

    #[test]
    fn 键盘帧原样转成报告并回成功() {
        let keys = [0x02, 0x00, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00];
        let out = feed(&frame(0x02, &keys));
        assert_eq!(out.reports, vec![(HidTarget::Keyboard, keys.to_vec())]);
        assert_eq!(out.replies, vec![0x57, 0xAB, 0x00, 0x82, 0x01, 0x00, 0x85]);
    }

    #[test]
    fn 绝对鼠标去掉首字节() {
        let out = feed(&frame(0x04, &[0x02, 0x01, 0xFF, 0x0F, 0x00, 0x08, 0x00]));
        assert_eq!(
            out.reports,
            vec![(HidTarget::AbsMouse, vec![0x01, 0xFF, 0x0F, 0x00, 0x08, 0x00])]
        );
        assert_eq!(out.replies, frame(0x84, &[0x00]));
    }

    #[test]
    fn 相对鼠标去掉首字节() {
        let out = feed(&frame(0x05, &[0x01, 0x00, 0x05, 0xFB, 0x01]));
        assert_eq!(out.reports, vec![(HidTarget::RelMouse, vec![0x00, 0x05, 0xFB, 0x01])]);
        assert_eq!(out.replies, frame(0x85, &[0x00]));
    }

    #[test]
    fn 查信息带上键盘灯和_usb_状态() {
        let mut emulator = Emulator::new();
        let out = emulator.feed(&frame(0x01, &[]), &mut status(UsbState::Configured, 0x02));
        assert!(out.reports.is_empty());
        assert_eq!(out.replies, frame(0x81, &[0x30, 0x01, 0x02, 0x00, 0, 0, 0, 0]));

        let out = emulator.feed(&frame(0x01, &[]), &mut status(UsbState::Suspended, 0));
        assert_eq!(out.replies, frame(0x81, &[0x30, 0x01, 0x00, 0x03, 0, 0, 0, 0]));

        let out = emulator.feed(&frame(0x01, &[]), &mut status(UsbState::Disconnected, 0));
        assert_eq!(out.replies, frame(0x81, &[0x30, 0x00, 0x00, 0x00, 0, 0, 0, 0]));
    }

    #[test]
    fn 只有查信息才去读状态() {
        let mut calls = 0;
        let mut counting = || {
            calls += 1;
            DeviceStatus { usb: UsbState::Configured, leds: 0 }
        };
        let mut emulator = Emulator::new();
        emulator.feed(&frame(0x02, &[0; 8]), &mut counting);
        emulator.feed(&frame(0x01, &[]), &mut counting);
        assert_eq!(calls, 1);
    }

    #[test]
    fn 拆成两半也能组帧() {
        let bytes = frame(0x02, &[0; 8]);
        let mut emulator = Emulator::new();
        let first = emulator.feed(&bytes[..4], &mut status(UsbState::Configured, 0));
        assert_eq!(first, Output::default());
        let second = emulator.feed(&bytes[4..], &mut status(UsbState::Configured, 0));
        assert_eq!(second.reports.len(), 1);
    }

    #[test]
    fn 帧头的两个字节被拆开也能组帧() {
        let bytes = frame(0x02, &[0; 8]);
        let mut emulator = Emulator::new();
        emulator.feed(&bytes[..1], &mut status(UsbState::Configured, 0));
        let out = emulator.feed(&bytes[1..], &mut status(UsbState::Configured, 0));
        assert_eq!(out.reports.len(), 1);
    }

    #[test]
    fn 两帧粘在一起都处理() {
        let mut bytes = frame(0x02, &[0; 8]);
        bytes.extend(frame(0x05, &[0x01, 0, 1, 1, 0]));
        let out = feed(&bytes);
        assert_eq!(out.reports.len(), 2);
        let mut replies = frame(0x82, &[0x00]);
        replies.extend(frame(0x85, &[0x00]));
        assert_eq!(out.replies, replies);
    }

    #[test]
    fn 帧头前的垃圾被跳过() {
        let mut bytes = vec![0x00, 0x13, 0x57];
        bytes.extend(frame(0x02, &[0; 8]));
        assert_eq!(feed(&bytes).reports.len(), 1);
    }

    #[test]
    fn 校验错回_e4_且不耽误后面的帧() {
        let mut bad = frame(0x02, &[0; 8]);
        *bad.last_mut().unwrap() ^= 0xFF;
        bad.extend(frame(0x05, &[0x01, 0, 0, 0, 0]));
        let out = feed(&bad);
        assert_eq!(out.reports, vec![(HidTarget::RelMouse, vec![0, 0, 0, 0])]);
        let mut replies = frame(0xC2, &[0xE4]);
        replies.extend(frame(0x85, &[0x00]));
        assert_eq!(out.replies, replies);
    }

    #[test]
    fn 未知命令回_e3() {
        let out = feed(&frame(0x03, &[0x02, 0x00, 0x00, 0x00]));
        assert!(out.reports.is_empty());
        assert_eq!(out.replies, frame(0xC3, &[0xE3]));
    }

    #[test]
    fn 数据长度不对回_e5() {
        let out = feed(&frame(0x02, &[0; 7]));
        assert!(out.reports.is_empty());
        assert_eq!(out.replies, frame(0xC2, &[0xE5]));
    }

    #[test]
    fn 读参数配置给出_9600_波特率() {
        let out = feed(&frame(0x08, &[]));
        assert_eq!(out.replies[3], 0x88);
        assert_eq!(out.replies[4], 50);
        let data = &out.replies[5..55];
        assert_eq!(&data[3..7], &[0x00, 0x00, 0x25, 0x80]);
    }

    #[test]
    fn 写配置_恢复出厂_复位都回成功() {
        for cmd in [0x09u8, 0x0C, 0x0F] {
            let data = if cmd == 0x09 { vec![0u8; 50] } else { Vec::new() };
            let out = feed(&frame(cmd, &data));
            assert_eq!(out.replies, frame(cmd | 0x80, &[0x00]), "cmd {cmd:#04x}");
        }
    }

    #[test]
    fn 绝对鼠标按着键时断开要补一份松键的报告() {
        let mut emulator = Emulator::new();
        assert!(emulator.release_reports().is_empty());
        emulator.feed(&frame(0x04, &[0x02, 0x01, 0xFF, 0x0F, 0x00, 0x08, 0x05]), &mut status(UsbState::Configured, 0));
        assert_eq!(
            emulator.release_reports(),
            vec![(HidTarget::AbsMouse, vec![0x00, 0xFF, 0x0F, 0x00, 0x08, 0x00])]
        );
    }

    #[test]
    fn 最后一份绝对鼠标报告没按键就不用补() {
        let mut emulator = Emulator::new();
        let mut bytes = frame(0x04, &[0x02, 0x01, 0x10, 0x00, 0x10, 0x00, 0x00]);
        bytes.extend(frame(0x04, &[0x02, 0x00, 0x10, 0x00, 0x10, 0x00, 0x00]));
        emulator.feed(&bytes, &mut status(UsbState::Configured, 0));
        assert!(emulator.release_reports().is_empty());
    }

    #[test]
    fn 只记绝对鼠标_键盘和相对鼠标不影响() {
        let mut emulator = Emulator::new();
        emulator.feed(&frame(0x04, &[0x02, 0x02, 0x34, 0x12, 0x78, 0x05, 0x00]), &mut status(UsbState::Configured, 0));
        emulator.feed(&frame(0x05, &[0x01, 0x00, 1, 1, 0]), &mut status(UsbState::Configured, 0));
        assert_eq!(
            emulator.release_reports(),
            vec![(HidTarget::AbsMouse, vec![0x00, 0x34, 0x12, 0x78, 0x05, 0x00])]
        );
    }
}
