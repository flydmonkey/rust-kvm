//! ATX 电源：LCUS 系列 USB 串口继电器并在主板前面板的电源键（和可选的复位键）跳线上，
//! 继电器吸合就等于按下。短按开机或让系统正常关机，长按强制断电。
//!
//! 协议和时长照搬 chrome-kvm 的 `atx-relay.js`。区别在于继电器插在宿主机上、由服务端
//! 驱动：一次按压从开串口到发断开指令全在一个独立线程里跑完，和发起它的 HTTP 请求
//! 无关——浏览器中途关掉、网络断了，断开指令照样会发。chrome-kvm 那边长按时关掉页面，
//! 继电器会一直吸合，这里没有这个问题。
//!
//! 串口只在按的那一下打开，按完就关，平时不占着。

use std::io::Write;
use std::sync::mpsc::{self, RecvTimeoutError};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use anyhow::{Context, Result};
use serde::Deserialize;
use tracing::{info, warn};

use crate::config::AtxSettings;

pub const BAUD_RATE: u32 = 9600;
pub const MIN_CHANNEL: u8 = 1;
pub const MAX_CHANNEL: u8 = 4;

const SHORT_PRESS: Duration = Duration::from_millis(500);
/// ATX 规范是按住 4 秒强制关机，多按一会儿给慢的主板留余量
const LONG_PRESS: Duration = Duration::from_millis(6000);
const PORT_TIMEOUT: Duration = Duration::from_millis(500);

#[derive(Clone, Copy, Debug, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum PressKind {
    Short,
    Long,
    Reset,
}

impl PressKind {
    fn duration(self) -> Duration {
        match self {
            Self::Long => LONG_PRESS,
            Self::Short | Self::Reset => SHORT_PRESS,
        }
    }

    fn label(self) -> &'static str {
        match self {
            Self::Short => "按一下电源键",
            Self::Long => "长按电源键",
            Self::Reset => "按一下复位键",
        }
    }
}

#[derive(Debug)]
pub enum PressError {
    /// 上一下还没按完。一次只按一下：两下叠在一起，第二下的断开会把第一下提前松掉
    Busy,
    NoResetChannel,
    Failed(anyhow::Error),
}

/// LCUS 协议：`A0 通道 开关 校验和`，校验和是前三字节相加取低 8 位
pub fn command(channel: u8, on: bool) -> [u8; 4] {
    let state = u8::from(on);
    [0xA0, channel, state, 0xA0u8.wrapping_add(channel).wrapping_add(state)]
}

fn valid_channel(channel: u8) -> bool {
    (MIN_CHANNEL..=MAX_CHANNEL).contains(&channel)
}

/// 没选继电器串口就是不用，返回 `None`，页面据此隐藏电源按钮。复位键可以不接，
/// 接了也不能和电源键同一路，否则点重启按的却是电源键。
pub fn normalize(atx: Option<AtxSettings>) -> Option<AtxSettings> {
    let atx = atx?;
    let port = atx.port.trim().to_string();
    if port.is_empty() {
        return None;
    }
    let channel = if valid_channel(atx.channel) { atx.channel } else { MIN_CHANNEL };
    let reset_channel = atx
        .reset_channel
        .filter(|&reset| valid_channel(reset) && reset != channel);
    Some(AtxSettings {
        port,
        channel,
        reset_channel,
    })
}

/// 吸合、等待、断开。断开不管前面成没成都要发：电源键一直按着，机器会被强制关机。
/// 返回 true 表示等待被 [`AtxRelay::release`] 提前打断。
fn run_press<W: Write + ?Sized>(
    port: &mut W,
    channel: u8,
    hold: Duration,
    cancel: &mpsc::Receiver<()>,
) -> Result<bool> {
    let pressed = port
        .write_all(&command(channel, true))
        .and_then(|()| port.flush());
    let released_early = pressed.is_ok()
        && match cancel.recv_timeout(hold) {
            Ok(()) => true,
            Err(RecvTimeoutError::Timeout | RecvTimeoutError::Disconnected) => false,
        };
    let released = port
        .write_all(&command(channel, false))
        .and_then(|()| port.flush());
    pressed.context("写吸合指令失败")?;
    released.context("写断开指令失败，继电器可能还吸合着，拔插一下它的 USB 线就会松开")?;
    Ok(released_early)
}

pub struct AtxRelay {
    /// 正在按时放着提前松开用的发送端，`None` 表示空闲
    pressing: Mutex<Option<mpsc::Sender<()>>>,
}

impl AtxRelay {
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            pressing: Mutex::new(None),
        })
    }

    /// 按完才返回。返回 true 表示被提前松开
    pub async fn press(
        self: &Arc<Self>,
        settings: &AtxSettings,
        kind: PressKind,
    ) -> Result<bool, PressError> {
        let channel = match kind {
            PressKind::Reset => settings.reset_channel.ok_or(PressError::NoResetChannel)?,
            PressKind::Short | PressKind::Long => settings.channel,
        };
        let (cancel_tx, cancel_rx) = mpsc::channel();
        {
            let mut pressing = self.pressing.lock().expect("atx 锁");
            if pressing.is_some() {
                return Err(PressError::Busy);
            }
            *pressing = Some(cancel_tx);
        }

        let relay = self.clone();
        let port_name = settings.port.clone();
        // 收尾（清掉「正在按」）也放在线程里：请求被丢弃时 await 之后的代码不会跑，
        // 放在外面的话继电器会永远显示成忙
        let task = tokio::task::spawn_blocking(move || {
            let result = serialport::new(&port_name, BAUD_RATE)
                .timeout(PORT_TIMEOUT)
                .open()
                .with_context(|| format!("打开继电器串口 {port_name} 失败"))
                .and_then(|mut port| run_press(&mut *port, channel, kind.duration(), &cancel_rx));
            *relay.pressing.lock().expect("atx 锁") = None;
            match &result {
                Ok(early) => info!(
                    "继电器 {port_name} 第 {channel} 路：{}{}",
                    kind.label(),
                    if *early { "（提前松开）" } else { "" }
                ),
                Err(error) => warn!("继电器 {port_name} 第 {channel} 路{}失败：{error:#}", kind.label()),
            }
            result
        });
        match task.await {
            Ok(result) => result.map_err(PressError::Failed),
            Err(error) => Err(PressError::Failed(anyhow::anyhow!("按压线程异常退出：{error}"))),
        }
    }

    /// 提前松开正在进行的按压。返回 false 表示当时没在按
    pub fn release(&self) -> bool {
        match self.pressing.lock().expect("atx 锁").as_ref() {
            Some(cancel) => cancel.send(()).is_ok(),
            None => false,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io;
    use std::time::Instant;

    fn settings(channel: u8, reset_channel: Option<u8>) -> Option<AtxSettings> {
        Some(AtxSettings {
            port: "COM7".to_string(),
            channel,
            reset_channel,
        })
    }

    #[test]
    fn command_matches_lcus_protocol() {
        assert_eq!(command(1, true), [0xA0, 0x01, 0x01, 0xA2]);
        assert_eq!(command(1, false), [0xA0, 0x01, 0x00, 0xA1]);
        assert_eq!(command(2, true), [0xA0, 0x02, 0x01, 0xA3]);
        assert_eq!(command(4, false), [0xA0, 0x04, 0x00, 0xA4]);
    }

    #[test]
    fn normalize_drops_unused_relay() {
        assert_eq!(normalize(None), None);
        assert_eq!(
            normalize(Some(AtxSettings {
                port: "  ".to_string(),
                channel: 1,
                reset_channel: None
            })),
            None
        );
    }

    #[test]
    fn normalize_fixes_channels() {
        assert_eq!(normalize(settings(9, None)), settings(1, None));
        assert_eq!(normalize(settings(2, Some(2))), settings(2, None));
        assert_eq!(normalize(settings(2, Some(0))), settings(2, None));
        assert_eq!(normalize(settings(2, Some(3))), settings(2, Some(3)));
    }

    #[test]
    fn press_writes_on_then_off() {
        let (_tx, rx) = mpsc::channel();
        let mut written = Vec::new();
        let early = run_press(&mut written, 1, Duration::from_millis(10), &rx).unwrap();
        assert!(!early);
        assert_eq!(written, [0xA0, 0x01, 0x01, 0xA2, 0xA0, 0x01, 0x00, 0xA1]);
    }

    #[test]
    fn release_cuts_long_press_short() {
        let (tx, rx) = mpsc::channel();
        tx.send(()).unwrap();
        let mut written = Vec::new();
        let started = Instant::now();
        let early = run_press(&mut written, 2, Duration::from_secs(10), &rx).unwrap();
        assert!(early);
        assert!(started.elapsed() < Duration::from_secs(1));
        assert_eq!(&written[4..], &command(2, false));
    }

    /// 第一次写失败，之后正常
    struct FailFirst {
        failed: bool,
        written: Vec<u8>,
    }

    impl Write for FailFirst {
        fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
            if !self.failed {
                self.failed = true;
                return Err(io::Error::other("拔了"));
            }
            self.written.extend_from_slice(buf);
            Ok(buf.len())
        }

        fn flush(&mut self) -> io::Result<()> {
            Ok(())
        }
    }

    #[test]
    fn off_is_sent_even_if_on_failed() {
        let (_tx, rx) = mpsc::channel();
        let mut port = FailFirst {
            failed: false,
            written: Vec::new(),
        };
        let started = Instant::now();
        assert!(run_press(&mut port, 1, Duration::from_secs(10), &rx).is_err());
        assert!(started.elapsed() < Duration::from_secs(1), "吸合失败就不该再等");
        assert_eq!(port.written, command(1, false));
    }

    #[tokio::test]
    async fn failed_press_frees_the_relay() {
        let relay = AtxRelay::new();
        let missing = AtxSettings {
            port: "COM_RUST_KVM_NOPE".to_string(),
            channel: 1,
            reset_channel: None,
        };
        assert!(matches!(
            relay.press(&missing, PressKind::Short).await,
            Err(PressError::Failed(_))
        ));
        assert!(matches!(
            relay.press(&missing, PressKind::Reset).await,
            Err(PressError::NoResetChannel)
        ));
        assert!(!relay.release());
        assert!(matches!(
            relay.press(&missing, PressKind::Short).await,
            Err(PressError::Failed(_))
        ));
    }
}
