# 本机 USB 口直连 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在带 USB 设备口的 ARM 板上，用 Linux configfs USB gadget 给被控机提供虚拟光驱、虚拟 U 盘和一套可以代替 CH9329 的键鼠。

**Architecture:** 服务启动前由 systemd 以 root 跑 `rust-kvm usb-gadget-setup <用户>` 建好 gadget（3 个 HID 加 1 个双逻辑盘的 mass_storage）、绑定控制器、把运行时要写的文件 chown 给服务用户。服务本身仍是普通用户：键鼠走串口桥里的一个假端口 `usb-gadget`，字节交给服务端的 CH9329 模拟器，翻译成 HID 报告写进 `/dev/hidg*`，浏览器和安卓客户端不用改；光驱和 U 盘通过 `/api/usb*` 挂载 `images/` 目录里的镜像。

**Tech Stack:** Rust 2024（axum 0.8、tokio、libc），configfs `libcomposite`（`hid`、`mass_storage` 功能），浏览器端原生 JS，`node --test`。

设计文档：[`docs/superpowers/specs/2026-11-26-usb-gadget-design.md`](../specs/2026-11-26-usb-gadget-design.md)。

## Global Constraints

- 只在 `target_os = "linux"` 且 `std::env::consts::ARCH` 为 `aarch64` 或 `arm` 时启用；x86 上没有新界面，`GET /api/usb` 回 `available: false, reason: null`。
- gadget 名字 `rust-kvm`，在 `/sys/kernel/config/usb_gadget/rust-kvm`；VID `0x1d6b`、PID `0x0104`；厂商 `rust-kvm`，产品名 `Virtual KVM`。
- 功能目录名固定：`hid.kbd`、`hid.abs`、`hid.rel`、`mass_storage.0`；配置目录 `configs/c.1`。`lun.0` 是光驱，`lun.1` 是 U 盘。
- hidg 节点按功能目录下 `dev` 文件的次设备号找：`/dev/hidg<次设备号>`，不按建立顺序猜。
- 串口假端口名 `usb-gadget`，描述 `本机 USB 口直连（不需要 CH9329）`。
- CH9329 错误码：`0xE3` 命令码错，`0xE4` 校验错，`0xE5` 参数错。
- 镜像目录固定为资源目录下的 `images/`，不做配置项；只认 `.iso`、`.img`（不分大小写）；光驱只收 `.iso`。
- 挂载状态不跨重启保留；挂盘、换盘、弹盘不解绑控制器。
- 注释和界面文案用中文，风格照现有代码：注释只写代码表达不了的约束。
- Rust 构建前先 `. ~/.local/devdeps/env.sh && export CARGO_TARGET_DIR=/tmp/cursor-sandbox-cache/2760c64a08a43faf89b82358a1a04e73/cargo-target`。没有 lib target，测试用 `cargo test --bin rust-kvm <过滤词>`。
- 提交身份：`git -c user.name=flydmonkey -c user.email=flydmonkey@gmail.com commit …`。**不推送**，推送要用户另外同意。

## 文件结构

| 文件 | 职责 |
| --- | --- |
| `src/hid_emulator.rs`（新） | 纯逻辑的 CH9329 模拟器：字节流进，HID 报告和应答出 |
| `src/usb_gadget.rs`（新） | 运行时：路径定义、启用判断、写 HID 报告、读键盘灯、挂盘弹盘；`test_support` 测试夹具 |
| `src/gadget_setup.rs`（新，仅 Linux） | root 执行的 setup：建 gadget、HID 描述符、绑定、chown、拆旧 gadget |
| `src/media.rs`（新） | 镜像目录：文件名校验、列出、路径换算 |
| `src/usb_api.rs`（新） | `/api/usb*` 路由：概览、挂盘、弹盘、流式上传、删除 |
| `src/serial_bridge.rs` | 加 `usb-gadget` 假端口和模拟器线程 |
| `src/systemd.rs` | ARM 上单元文件加 `ExecStartPre=-+… usb-gadget-setup <用户>` |
| `src/main.rs` | 挂模块、子命令、启动时判断、接路由、继电器不能选假端口 |
| `Cargo.toml` | tokio 加 `fs`、`io-util`；dev-dependencies 加 `tempfile` |
| `web/usb-media.js`（新） | 纯函数：尺寸格式化、槽位文案、下拉选项 |
| `web/media-panel.js`（新） | 控制页「虚拟介质」面板 |
| `web/index.html` | 工具栏按钮、面板、样式、脚本 |
| `web/settings.html`、`web/settings-page.js` | 选直连时隐藏 CH9329 芯片一节；继电器列表去掉假端口；ARM 上不可用时显示原因 |
| `tests/web/usb-media.test.js`（新） | `usb-media.js` 的测试 |
| `README.md` | 硬件要求、设备树、接线、用法 |

---

### Task 0: 先提交工作区里已有的改动

工作区里有两处跟本计划无关、还没提交的改动（设置页「重新检测」按钮挪位、「没人看时也保持采集」开关），它们改了 `src/main.rs`、`src/video.rs`、`src/config.rs`、`web/settings.html`、`web/settings-page.js`、`README.md` 和两个安卓文件。不先提交的话会混进本计划的提交里。

- [ ] **Step 1: 问用户是否可以提交这两处改动**（用户同意后再继续）

- [ ] **Step 2: 提交**

```bash
cd /home/ecs-user/Projects/rust-kvm
git add README.md src/config.rs src/main.rs src/video.rs web/settings.html web/settings-page.js \
  android/app/src/main/java/com/rustkvm/app/ui/SettingsScreen.kt \
  android/core-protocol/src/main/java/com/rustkvm/protocol/ConfigModels.kt
git -c user.name=flydmonkey -c user.email=flydmonkey@gmail.com commit -m "设置页：重新检测按钮并到分辨率一行；视频加「没人看时也保持采集」开关"
git status --short
```

Expected: `git status --short` 没有输出。

---

### Task 1: CH9329 模拟器（`hid_emulator.rs`）

**Files:**
- Create: `src/hid_emulator.rs`
- Modify: `src/main.rs`（模块列表）

**Interfaces:**
- Produces:
  - `pub enum HidTarget { Keyboard, AbsMouse, RelMouse }`（`Clone, Copy, Debug, PartialEq, Eq`）
  - `pub enum UsbState { Configured, Suspended, Disconnected }`（同上）
  - `pub struct DeviceStatus { pub usb: UsbState, pub leds: u8 }`（`Clone, Copy, Debug`）
  - `pub struct Output { pub reports: Vec<(HidTarget, Vec<u8>)>, pub replies: Vec<u8> }`（`Debug, Default, PartialEq, Eq`）
  - `pub struct Emulator`，`Emulator::new() -> Emulator`，`Emulator::feed(&mut self, bytes: &[u8], status: &mut dyn FnMut() -> DeviceStatus) -> Output`

- [ ] **Step 1: 写模块骨架和失败的测试**

`src/main.rs` 的模块列表里，在 `mod h264;` 后面加一行：

```rust
mod hid_emulator;
```

新建 `src/hid_emulator.rs`，先只放类型、空实现和测试：

```rust
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
}

impl Emulator {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn feed(&mut self, _bytes: &[u8], _status: &mut dyn FnMut() -> DeviceStatus) -> Output {
        Output::default()
    }
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
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cargo test --bin rust-kvm hid_emulator`
Expected: 编译通过，`读参数配置给出_9600_波特率` 等多数测试 FAIL（`feed` 还是空实现，越界或断言不等）。

- [ ] **Step 3: 实现**

把 `src/hid_emulator.rs` 里 `impl Emulator { … }` 整块换成下面这些（类型定义和测试不动）：

```rust
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
        out
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
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cargo test --bin rust-kvm hid_emulator`
Expected: 14 个测试全部 PASS。此时会有 `dead_code` 警告（模块还没人用），Task 4 接上后消失。

- [ ] **Step 5: 提交**

```bash
git add src/hid_emulator.rs src/main.rs
git -c user.name=flydmonkey -c user.email=flydmonkey@gmail.com commit -m "CH9329 模拟器：把键鼠帧翻成 HID 报告并回应答"
```

---

### Task 2: gadget 运行时（`usb_gadget.rs`）

**Files:**
- Create: `src/usb_gadget.rs`
- Modify: `src/main.rs`（模块列表）、`Cargo.toml`（dev-dependencies）

**Interfaces:**
- Consumes: `crate::hid_emulator::{DeviceStatus, HidTarget, UsbState}`
- Produces:
  - 常量 `GADGET_NAME`、`CONFIG_DIR`、`KEYBOARD_FUNCTION`、`ABS_MOUSE_FUNCTION`、`REL_MOUSE_FUNCTION`、`STORAGE_FUNCTION`、`HID_FUNCTIONS: [&str; 3]`
  - `pub struct GadgetPaths { pub configfs: PathBuf, pub udc_class: PathBuf, pub dev: PathBuf }`，方法 `system()`、`gadget()`、`function(&str)`、`lun(Slot)`、`hid_node(&str) -> Result<PathBuf>`
  - `pub enum Slot { Cdrom, Disk }`（serde 小写），`Slot::ALL`、`Slot::lun(self) -> &'static str`、`Slot::other(self) -> Slot`
  - `pub struct SlotState { pub image: Option<PathBuf>, pub read_only: bool }`
  - `pub enum Availability { Unsupported, Unavailable(String), Ready(Arc<UsbGadget>) }`
  - `pub fn detect(paths: &GadgetPaths, arch: &str) -> Availability`
  - `pub struct UsbGadget`：`open(GadgetPaths) -> Result<Self>`、`status(&self) -> DeviceStatus`、`write_report(&self, HidTarget, &[u8]) -> io::Result<()>`、`spawn_led_reader(self: &Arc<Self>)`、`slot(&self, Slot) -> SlotState`、`mount(&self, Slot, &Path, bool) -> Result<()>`、`eject(&self, Slot) -> Result<()>`
  - `#[cfg(test)] pub(crate) mod test_support`：`Fixture { pub temp: TempDir, pub paths: GadgetPaths }`、`fixture() -> Fixture`、`gadget() -> (Fixture, Arc<UsbGadget>)`

- [ ] **Step 1: 加依赖和模块**

`Cargo.toml` 末尾加：

```toml
[dev-dependencies]
# gadget、镜像目录的测试都对着临时目录跑，代替 configfs 和 /dev
tempfile = "3"
```

`src/main.rs` 模块列表里，在 `mod systemd;` 那一组后面、`mod video;` 前面加：

```rust
mod usb_gadget;
```

- [ ] **Step 2: 写骨架和失败的测试**

新建 `src/usb_gadget.rs`：

```rust
//! 本机 USB 口（configfs gadget）的运行时部分：判断能不能用、写 HID 报告、读键盘灯、
//! 挂盘弹盘。建 gadget 要 root，在 `gadget_setup.rs` 里，由 systemd 在服务启动前跑。
//!
//! configfs、udc、`/dev` 三个根目录都从 `GadgetPaths` 进来，测试时换成临时目录。

use std::fs::{File, OpenOptions};
use std::io::{self, Write};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::atomic::{AtomicU8, Ordering};
use std::time::Duration;

use anyhow::{Context, Result, bail};
use serde::{Deserialize, Serialize};
use tracing::{debug, warn};

use crate::hid_emulator::{DeviceStatus, HidTarget, UsbState};

pub const GADGET_NAME: &str = "rust-kvm";
pub const CONFIG_DIR: &str = "configs/c.1";
pub const KEYBOARD_FUNCTION: &str = "hid.kbd";
pub const ABS_MOUSE_FUNCTION: &str = "hid.abs";
pub const REL_MOUSE_FUNCTION: &str = "hid.rel";
pub const STORAGE_FUNCTION: &str = "mass_storage.0";
pub const HID_FUNCTIONS: [&str; 3] = [KEYBOARD_FUNCTION, ABS_MOUSE_FUNCTION, REL_MOUSE_FUNCTION];

/// 被控机这么久不取走上一份报告就算它没在听，这一帧丢掉。主机正常 1–10ms 轮询一次。
const REPORT_TIMEOUT: Duration = Duration::from_millis(50);

const REINSTALL: &str =
    "用新版重新装一次服务（sudo rust-kvm install），再 sudo rust-kvm stop && sudo rust-kvm start";

#[derive(Clone, Debug)]
pub struct GadgetPaths {
    pub configfs: PathBuf,
    pub udc_class: PathBuf,
    pub dev: PathBuf,
}

impl GadgetPaths {
    pub fn system() -> Self {
        Self {
            configfs: PathBuf::from("/sys/kernel/config/usb_gadget"),
            udc_class: PathBuf::from("/sys/class/udc"),
            dev: PathBuf::from("/dev"),
        }
    }

    pub fn gadget(&self) -> PathBuf {
        self.configfs.join(GADGET_NAME)
    }

    pub fn function(&self, name: &str) -> PathBuf {
        self.gadget().join("functions").join(name)
    }

    pub fn lun(&self, slot: Slot) -> PathBuf {
        self.function(STORAGE_FUNCTION).join(slot.lun())
    }

    /// 功能目录下的 `dev` 写着「主:次」，节点就是 `hidg<次>`。不按建立顺序猜：
    /// 板子上要是还有别的 gadget 占了 hidg0，顺序就对不上了。
    pub fn hid_node(&self, function: &str) -> Result<PathBuf> {
        let dev_file = self.function(function).join("dev");
        let text = std::fs::read_to_string(&dev_file)
            .with_context(|| format!("读 {} 失败", dev_file.display()))?;
        let minor = text
            .trim()
            .split(':')
            .nth(1)
            .and_then(|minor| minor.parse::<u32>().ok())
            .with_context(|| format!("{} 的内容不是「主:次」：{text:?}", dev_file.display()))?;
        Ok(self.dev.join(format!("hidg{minor}")))
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Slot {
    Cdrom,
    Disk,
}

impl Slot {
    pub const ALL: [Slot; 2] = [Slot::Cdrom, Slot::Disk];

    pub fn lun(self) -> &'static str {
        match self {
            Slot::Cdrom => "lun.0",
            Slot::Disk => "lun.1",
        }
    }

    pub fn other(self) -> Slot {
        match self {
            Slot::Cdrom => Slot::Disk,
            Slot::Disk => Slot::Cdrom,
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SlotState {
    pub image: Option<PathBuf>,
    pub read_only: bool,
}

pub enum Availability {
    /// 不是 ARM Linux：功能整个不出现，也不提示
    Unsupported,
    /// ARM 上缺了条件，原因直接显示给用户
    Unavailable(String),
    Ready(Arc<UsbGadget>),
}

pub fn detect(_paths: &GadgetPaths, _arch: &str) -> Availability {
    Availability::Unsupported
}

pub struct UsbGadget {
    paths: GadgetPaths,
    udc: String,
    keyboard: File,
    abs_mouse: File,
    rel_mouse: File,
    leds: AtomicU8,
}

impl UsbGadget {
    pub fn open(_paths: GadgetPaths) -> Result<Self> {
        bail!("还没实现")
    }
}

#[cfg(test)]
pub(crate) mod test_support {
    use super::*;

    pub struct Fixture {
        pub temp: tempfile::TempDir,
        pub paths: GadgetPaths,
    }

    /// 一个「setup 已经跑过」的样子。hid 的次设备号故意不按顺序（键盘是 2），
    /// 验证节点是按 `dev` 找的。
    pub fn fixture() -> Fixture {
        let temp = tempfile::tempdir().unwrap();
        let paths = GadgetPaths {
            configfs: temp.path().join("usb_gadget"),
            udc_class: temp.path().join("udc"),
            dev: temp.path().join("dev"),
        };
        let udc = paths.udc_class.join("fe800000.usb");
        std::fs::create_dir_all(&udc).unwrap();
        std::fs::write(udc.join("state"), "configured\n").unwrap();
        std::fs::create_dir_all(&paths.dev).unwrap();
        std::fs::create_dir_all(paths.gadget()).unwrap();
        std::fs::write(paths.gadget().join("UDC"), "fe800000.usb\n").unwrap();
        for (function, minor) in HID_FUNCTIONS.iter().zip([2, 0, 1]) {
            std::fs::create_dir_all(paths.function(function)).unwrap();
            std::fs::write(paths.function(function).join("dev"), format!("236:{minor}\n")).unwrap();
            std::fs::write(paths.dev.join(format!("hidg{minor}")), b"").unwrap();
        }
        for slot in Slot::ALL {
            let lun = paths.lun(slot);
            std::fs::create_dir_all(&lun).unwrap();
            for attr in ["file", "ro", "forced_eject"] {
                std::fs::write(lun.join(attr), "").unwrap();
            }
        }
        std::fs::write(paths.lun(Slot::Cdrom).join("ro"), "1\n").unwrap();
        Fixture { temp, paths }
    }

    pub fn gadget() -> (Fixture, Arc<UsbGadget>) {
        let fixture = fixture();
        let gadget = Arc::new(UsbGadget::open(fixture.paths.clone()).unwrap());
        (fixture, gadget)
    }
}

#[cfg(test)]
mod tests {
    use super::test_support::fixture;
    use super::*;

    fn reason(availability: Availability) -> Option<String> {
        match availability {
            Availability::Unavailable(reason) => Some(reason),
            _ => None,
        }
    }

    #[test]
    fn x86_上不支持() {
        let fixture = fixture();
        assert!(matches!(detect(&fixture.paths, "x86_64"), Availability::Unsupported));
    }

    #[test]
    fn 全齐时可用() {
        let fixture = fixture();
        assert!(matches!(detect(&fixture.paths, "aarch64"), Availability::Ready(_)));
    }

    #[test]
    fn 没有_configfs_时说明原因() {
        let fixture = fixture();
        std::fs::remove_dir_all(&fixture.paths.configfs).unwrap();
        let reason = reason(detect(&fixture.paths, "aarch64")).unwrap();
        assert!(reason.contains("configfs"), "{reason}");
    }

    #[test]
    fn 没有控制器时提示设备树() {
        let fixture = fixture();
        std::fs::remove_dir_all(fixture.paths.udc_class.join("fe800000.usb")).unwrap();
        let reason = reason(detect(&fixture.paths, "aarch64")).unwrap();
        assert!(reason.contains("dr_mode"), "{reason}");
    }

    #[test]
    fn 没建_gadget_时提示重装() {
        let fixture = fixture();
        std::fs::remove_dir_all(fixture.paths.gadget()).unwrap();
        let reason = reason(detect(&fixture.paths, "arm")).unwrap();
        assert!(reason.contains("rust-kvm install"), "{reason}");
    }

    #[test]
    fn 没绑定时提示重装() {
        let fixture = fixture();
        std::fs::write(fixture.paths.gadget().join("UDC"), "\n").unwrap();
        let reason = reason(detect(&fixture.paths, "aarch64")).unwrap();
        assert!(reason.contains("没有绑定"), "{reason}");
    }

    #[test]
    fn 键盘报告写进_dev_里记的那个节点() {
        let (fixture, gadget) = test_support::gadget();
        gadget.write_report(HidTarget::Keyboard, &[0, 0, 4, 0, 0, 0, 0, 0]).unwrap();
        gadget.write_report(HidTarget::RelMouse, &[0, 1, 1, 0]).unwrap();
        let keyboard = std::fs::read(fixture.paths.dev.join("hidg2")).unwrap();
        assert_eq!(keyboard, vec![0, 0, 4, 0, 0, 0, 0, 0]);
        let mouse = std::fs::read(fixture.paths.dev.join("hidg1")).unwrap();
        assert_eq!(mouse, vec![0, 1, 1, 0]);
    }

    #[test]
    fn 状态里带_udc_的连接状态() {
        let (fixture, gadget) = test_support::gadget();
        assert_eq!(gadget.status().usb, UsbState::Configured);
        let state = fixture.paths.udc_class.join("fe800000.usb/state");
        std::fs::write(&state, "suspended\n").unwrap();
        assert_eq!(gadget.status().usb, UsbState::Suspended);
        std::fs::write(&state, "not attached\n").unwrap();
        assert_eq!(gadget.status().usb, UsbState::Disconnected);
    }

    #[test]
    fn u盘挂盘先弹出再写只读再写文件() {
        let (fixture, gadget) = test_support::gadget();
        gadget.mount(Slot::Disk, Path::new("/images/a.img"), true).unwrap();
        let lun = fixture.paths.lun(Slot::Disk);
        assert_eq!(std::fs::read_to_string(lun.join("forced_eject")).unwrap(), "1");
        assert_eq!(std::fs::read_to_string(lun.join("ro")).unwrap(), "1");
        assert_eq!(
            gadget.slot(Slot::Disk),
            SlotState { image: Some(PathBuf::from("/images/a.img")), read_only: true }
        );
    }

    #[test]
    fn 光驱挂盘不碰只读() {
        let (fixture, gadget) = test_support::gadget();
        gadget.mount(Slot::Cdrom, Path::new("/images/win.iso"), false).unwrap();
        let ro = std::fs::read_to_string(fixture.paths.lun(Slot::Cdrom).join("ro")).unwrap();
        assert_eq!(ro.trim(), "1");
        assert!(gadget.slot(Slot::Cdrom).read_only);
    }

    #[test]
    fn 弹出后槽是空的() {
        let (_fixture, gadget) = test_support::gadget();
        gadget.mount(Slot::Disk, Path::new("/images/a.img"), false).unwrap();
        gadget.eject(Slot::Disk).unwrap();
        assert_eq!(gadget.slot(Slot::Disk).image, None);
    }
}
```

- [ ] **Step 3: 跑测试确认失败**

Run: `cargo test --bin rust-kvm usb_gadget`
Expected: 编译失败，`UsbGadget` 上没有 `write_report`、`status`、`mount`、`eject`、`slot`。

- [ ] **Step 4: 实现**

把 `detect` 和 `impl UsbGadget { … }` 换成下面的代码，并在文件里（`test_support` 之前）加上 `sys` 子模块：

```rust
pub fn detect(paths: &GadgetPaths, arch: &str) -> Availability {
    if !cfg!(target_os = "linux") || !matches!(arch, "aarch64" | "arm") {
        return Availability::Unsupported;
    }
    if !paths.configfs.is_dir() {
        return Availability::Unavailable(format!(
            "内核没有 configfs 的 USB gadget（{} 不存在）：确认内核开了 CONFIG_USB_CONFIGFS，并且 libcomposite 能加载",
            paths.configfs.display()
        ));
    }
    let has_udc = std::fs::read_dir(&paths.udc_class)
        .map(|mut entries| entries.next().is_some())
        .unwrap_or(false);
    if !has_udc {
        return Availability::Unavailable(
            "没有 USB 设备控制器（/sys/class/udc 是空的）。RK 板的 OTG 口要在设备树里把 dr_mode 设成 peripheral 或 otg".into(),
        );
    }
    match UsbGadget::open(paths.clone()) {
        Ok(gadget) => Availability::Ready(Arc::new(gadget)),
        Err(error) => Availability::Unavailable(format!("{error:#}")),
    }
}

impl UsbGadget {
    pub fn open(paths: GadgetPaths) -> Result<Self> {
        let gadget = paths.gadget();
        if !gadget.is_dir() {
            bail!("gadget 还没建（{} 不存在）。{REINSTALL}", gadget.display());
        }
        let udc = std::fs::read_to_string(gadget.join("UDC"))
            .unwrap_or_default()
            .trim()
            .to_string();
        if udc.is_empty() {
            bail!("gadget 没有绑定到 USB 控制器。{REINSTALL}");
        }
        for slot in Slot::ALL {
            let eject = paths.lun(slot).join("forced_eject");
            OpenOptions::new().write(true).open(&eject).with_context(|| {
                format!("写不了 {}，setup 没把它交给服务用户。{REINSTALL}", eject.display())
            })?;
        }
        Ok(Self {
            keyboard: open_hid(&paths, KEYBOARD_FUNCTION)?,
            abs_mouse: open_hid(&paths, ABS_MOUSE_FUNCTION)?,
            rel_mouse: open_hid(&paths, REL_MOUSE_FUNCTION)?,
            leds: AtomicU8::new(0),
            udc,
            paths,
        })
    }

    pub fn status(&self) -> DeviceStatus {
        let state = self.paths.udc_class.join(&self.udc).join("state");
        let text = std::fs::read_to_string(state).unwrap_or_default();
        let usb = match text.trim() {
            "configured" => UsbState::Configured,
            "suspended" => UsbState::Suspended,
            _ => UsbState::Disconnected,
        };
        DeviceStatus { usb, leds: self.leds.load(Ordering::Relaxed) }
    }

    pub fn write_report(&self, target: HidTarget, report: &[u8]) -> io::Result<()> {
        let file = match target {
            HidTarget::Keyboard => &self.keyboard,
            HidTarget::AbsMouse => &self.abs_mouse,
            HidTarget::RelMouse => &self.rel_mouse,
        };
        sys::write_report(file, report, REPORT_TIMEOUT)
    }

    /// 被控机发来的键盘灯是一字节的输出报告，从键盘节点读。读是阻塞的，单开一个线程，
    /// 跟着服务一直活着。
    pub fn spawn_led_reader(self: &Arc<Self>) {
        let gadget = self.clone();
        let spawned = std::thread::Builder::new()
            .name("hid-leds".into())
            .spawn(move || gadget.read_leds_forever());
        if let Err(error) = spawned {
            warn!("启动键盘灯线程失败，控制页的键盘指示灯不会跟着变：{error}");
        }
    }

    fn read_leds_forever(&self) {
        let mut buf = [0u8; 8];
        loop {
            match sys::read_report(&self.keyboard, &mut buf, Duration::from_millis(500)) {
                Ok(0) => std::thread::sleep(Duration::from_millis(100)),
                Ok(_) => self.leds.store(buf[0], Ordering::Relaxed),
                Err(error) => {
                    debug!("读键盘灯失败：{error}");
                    std::thread::sleep(Duration::from_millis(500));
                }
            }
        }
    }

    /// 以 configfs 为准，不在内存里另存：被控机自己弹出光盘后两边才不会对不上。
    pub fn slot(&self, slot: Slot) -> SlotState {
        let lun = self.paths.lun(slot);
        let file = std::fs::read_to_string(lun.join("file")).unwrap_or_default();
        let file = file.trim_end_matches('\n');
        let ro = std::fs::read_to_string(lun.join("ro")).unwrap_or_default();
        SlotState {
            image: (!file.is_empty()).then(|| PathBuf::from(file)),
            read_only: ro.trim() == "1",
        }
    }

    /// 先强制弹出：被控机锁着介质（Windows 打开过光驱就会锁）时直接写 `file` 会 EBUSY；
    /// `ro` 也只能在空槽时改。光驱的只读在 setup 时就定了，这里不碰。
    pub fn mount(&self, slot: Slot, image: &Path, read_only: bool) -> Result<()> {
        let lun = self.paths.lun(slot);
        write_attr(&lun.join("forced_eject"), "1")?;
        if slot == Slot::Disk {
            write_attr(&lun.join("ro"), if read_only { "1" } else { "0" })?;
        }
        write_attr(&lun.join("file"), &image.to_string_lossy())
    }

    pub fn eject(&self, slot: Slot) -> Result<()> {
        let lun = self.paths.lun(slot);
        write_attr(&lun.join("forced_eject"), "1")?;
        write_attr(&lun.join("file"), "")
    }
}

fn open_hid(paths: &GadgetPaths, function: &str) -> Result<File> {
    let node = paths.hid_node(function)?;
    sys::open_nonblocking(&node)
        .with_context(|| format!("打不开 {}（setup 没把它交给服务用户？）。{REINSTALL}", node.display()))
}

/// configfs 的属性文件一定已经存在，不带 create：写错路径时要报错，不能悄悄建个新文件。
fn write_attr(path: &Path, value: &str) -> Result<()> {
    let mut file = OpenOptions::new()
        .write(true)
        .truncate(true)
        .open(path)
        .with_context(|| format!("打开 {} 失败", path.display()))?;
    file.write_all(value.as_bytes())
        .with_context(|| format!("写 {} 失败", path.display()))
}

#[cfg(target_os = "linux")]
mod sys {
    use std::fs::{File, OpenOptions};
    use std::io::{self, Read, Write};
    use std::os::fd::AsRawFd;
    use std::os::unix::fs::OpenOptionsExt;
    use std::path::Path;
    use std::time::Duration;

    /// 非阻塞打开：被控机没接时 hidg 的 write 会一直挂着，那样整条键鼠线程就卡死了。
    pub fn open_nonblocking(path: &Path) -> io::Result<File> {
        OpenOptions::new()
            .read(true)
            .write(true)
            .custom_flags(libc::O_NONBLOCK)
            .open(path)
    }

    fn wait(file: &File, events: libc::c_short, timeout: Duration) -> io::Result<bool> {
        let mut fd = libc::pollfd { fd: file.as_raw_fd(), events, revents: 0 };
        let millis = timeout.as_millis().min(i32::MAX as u128) as libc::c_int;
        let ready = unsafe { libc::poll(&mut fd, 1, millis) };
        if ready < 0 {
            return Err(io::Error::last_os_error());
        }
        Ok(ready > 0)
    }

    /// hidg 一次只排一份报告。上一份还没被主机取走时等一会儿，不直接丢：丢的要是
    /// 松键那一帧，被控机上的键就一直按着。
    pub fn write_report(mut file: &File, report: &[u8], timeout: Duration) -> io::Result<()> {
        match file.write(report) {
            Err(error) if error.kind() == io::ErrorKind::WouldBlock => {
                if !wait(file, libc::POLLOUT, timeout)? {
                    return Err(io::Error::new(io::ErrorKind::TimedOut, "被控机没有取走上一份报告"));
                }
                file.write(report).map(|_| ())
            }
            other => other.map(|_| ()),
        }
    }

    pub fn read_report(mut file: &File, buf: &mut [u8], timeout: Duration) -> io::Result<usize> {
        if !wait(file, libc::POLLIN, timeout)? {
            return Ok(0);
        }
        match file.read(buf) {
            Err(error) if error.kind() == io::ErrorKind::WouldBlock => Ok(0),
            other => other,
        }
    }
}

#[cfg(not(target_os = "linux"))]
mod sys {
    use std::fs::File;
    use std::io;
    use std::path::Path;
    use std::time::Duration;

    fn unsupported() -> io::Error {
        io::Error::new(io::ErrorKind::Unsupported, "只有 Linux 支持 USB gadget")
    }

    pub fn open_nonblocking(_path: &Path) -> io::Result<File> {
        Err(unsupported())
    }

    pub fn write_report(_file: &File, _report: &[u8], _timeout: Duration) -> io::Result<()> {
        Err(unsupported())
    }

    pub fn read_report(_file: &File, _buf: &mut [u8], _timeout: Duration) -> io::Result<usize> {
        Err(unsupported())
    }
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: `cargo test --bin rust-kvm usb_gadget`
Expected: 11 个测试全部 PASS。

- [ ] **Step 6: 提交**

```bash
git add Cargo.toml Cargo.lock src/usb_gadget.rs src/main.rs
git -c user.name=flydmonkey -c user.email=flydmonkey@gmail.com commit -m "USB gadget 运行时：启用判断、写 HID 报告、挂盘弹盘"
```

---

### Task 3: root 执行的 setup 和 systemd 单元

**Files:**
- Create: `src/gadget_setup.rs`
- Modify: `src/main.rs`（模块、`run_command`、`USAGE`）、`src/systemd.rs`（`install`、`unit_file`、测试）

**Interfaces:**
- Consumes: `usb_gadget::{GadgetPaths, Slot, CONFIG_DIR, HID_FUNCTIONS, KEYBOARD_FUNCTION, ABS_MOUSE_FUNCTION, REL_MOUSE_FUNCTION, STORAGE_FUNCTION}`；Task 2 的 `test_support::fixture()`
- Produces: `gadget_setup::run(user: Option<&str>) -> Result<()>`、`build(&GadgetPaths, serial: &str) -> Result<()>`、`hand_over(&GadgetPaths, owner: (u32, u32), timeout: Duration) -> Result<()>`；`systemd::unit_file(exe, root, user, groups, gadget: bool)`

- [ ] **Step 1: 写骨架和失败的测试**

`src/main.rs` 模块列表里，在 `mod devices;` 后面加：

```rust
#[cfg(target_os = "linux")]
mod gadget_setup;
```

新建 `src/gadget_setup.rs`：

```rust
//! `rust-kvm usb-gadget-setup <服务用户>`：服务启动前以 root 建好 gadget。
//!
//! systemd 单元里写成 `ExecStartPre=-+…`：`+` 让这一步以 root 跑，`-` 让它失败了也不挡
//! 服务启动——gadget 用不了时，画面和 CH9329 键鼠照样要能用。结构见设计文档
//! `docs/superpowers/specs/2026-11-26-usb-gadget-design.md`。

use std::ffi::CString;
use std::path::Path;
use std::process::Command;
use std::time::{Duration, Instant};

use anyhow::{Context, Result, bail};

use crate::usb_gadget::{
    ABS_MOUSE_FUNCTION, CONFIG_DIR, GadgetPaths, HID_FUNCTIONS, KEYBOARD_FUNCTION,
    REL_MOUSE_FUNCTION, STORAGE_FUNCTION, Slot,
};

pub fn run(_user: Option<&str>) -> Result<()> {
    bail!("还没实现")
}

pub fn build(_paths: &GadgetPaths, _serial: &str) -> Result<()> {
    bail!("还没实现")
}

pub fn hand_over(_paths: &GadgetPaths, _owner: (u32, u32), _timeout: Duration) -> Result<()> {
    bail!("还没实现")
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::usb_gadget::test_support::fixture;

    fn read(path: impl AsRef<Path>) -> String {
        std::fs::read_to_string(path).unwrap().trim().to_string()
    }

    /// 按描述符把所有 Input 项的位数加起来。和 `report_length` 对不上的话，
    /// 主机会按描述符去解析一份长度不同的报告，键鼠全乱。
    fn input_report_bytes(descriptor: &[u8]) -> usize {
        let (mut size, mut count, mut bits) = (0usize, 0usize, 0usize);
        let mut i = 0;
        while i < descriptor.len() {
            let prefix = descriptor[i];
            let len = match prefix & 0x03 {
                3 => 4,
                n => n as usize,
            };
            let value = descriptor[i + 1..i + 1 + len]
                .iter()
                .rev()
                .fold(0usize, |acc, byte| (acc << 8) | *byte as usize);
            match prefix & 0xFC {
                0x74 => size = value,
                0x94 => count = value,
                0x80 => bits += size * count,
                _ => {}
            }
            i += 1 + len;
        }
        bits / 8
    }

    #[test]
    fn 描述符和报告长度对得上() {
        for hid in &HIDS {
            assert_eq!(
                input_report_bytes(hid.descriptor),
                hid.report_length as usize,
                "{}",
                hid.name
            );
        }
    }

    #[test]
    fn 建出来的结构() {
        let temp = tempfile::tempdir().unwrap();
        let paths = GadgetPaths {
            configfs: temp.path().join("usb_gadget"),
            udc_class: temp.path().join("udc"),
            dev: temp.path().join("dev"),
        };
        build(&paths, "0123456789abcdef").unwrap();
        let gadget = paths.gadget();
        assert_eq!(read(gadget.join("idVendor")), "0x1d6b");
        assert_eq!(read(gadget.join("idProduct")), "0x0104");
        assert_eq!(read(gadget.join("strings/0x409/product")), "Virtual KVM");
        assert_eq!(read(gadget.join("strings/0x409/serialnumber")), "0123456789abcdef");

        let keyboard = paths.function(KEYBOARD_FUNCTION);
        assert_eq!(read(keyboard.join("protocol")), "1");
        assert_eq!(read(keyboard.join("subclass")), "1");
        assert_eq!(read(keyboard.join("report_length")), "8");
        assert_eq!(std::fs::read(keyboard.join("report_desc")).unwrap(), KEYBOARD_DESC);

        let cdrom = paths.lun(Slot::Cdrom);
        assert_eq!(read(cdrom.join("cdrom")), "1");
        assert_eq!(read(cdrom.join("ro")), "1");
        let disk = paths.lun(Slot::Disk);
        assert_eq!(read(disk.join("cdrom")), "0");
        assert_eq!(read(disk.join("removable")), "1");

        for function in HID_FUNCTIONS.iter().chain([&STORAGE_FUNCTION]) {
            let link = gadget.join(CONFIG_DIR).join(function);
            assert_eq!(std::fs::read_link(&link).unwrap(), paths.function(function));
        }
    }

    #[test]
    fn 交出去的文件包括三个_hidg_节点() {
        let fixture = fixture();
        let me = unsafe { (libc::geteuid(), libc::getegid()) };
        hand_over(&fixture.paths, me, Duration::from_millis(100)).unwrap();
    }

    #[test]
    fn hidg_节点迟迟不出现就报错() {
        let fixture = fixture();
        std::fs::remove_file(fixture.paths.dev.join("hidg2")).unwrap();
        let me = unsafe { (libc::geteuid(), libc::getegid()) };
        let error = hand_over(&fixture.paths, me, Duration::from_millis(100)).unwrap_err();
        assert!(format!("{error:#}").contains("hidg2"), "{error:#}");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cargo test --bin rust-kvm gadget_setup`
Expected: 编译失败，`HIDS`、`KEYBOARD_DESC` 未定义。

- [ ] **Step 3: 实现**

把三个 `bail!("还没实现")` 的函数换成下面的代码（测试不动）：

```rust
/// 开机协议键盘：1 字节修饰键、1 字节保留、6 个键码；输出报告是 5 位键盘灯。
/// CH9329 的键盘数据就是这 8 个字节，模拟器原样转发。
const KEYBOARD_DESC: &[u8] = &[
    0x05, 0x01, 0x09, 0x06, 0xA1, 0x01, //
    0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x08, 0x81, 0x02, //
    0x95, 0x01, 0x75, 0x08, 0x81, 0x03, //
    0x95, 0x05, 0x75, 0x01, 0x05, 0x08, 0x19, 0x01, 0x29, 0x05, 0x91, 0x02, //
    0x95, 0x01, 0x75, 0x03, 0x91, 0x03, //
    0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65, 0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81, 0x00, //
    0xC0,
];

/// 绝对鼠标：3 键、X/Y 各 16 位（逻辑范围 0–4095，和 CH9329 一样，不用换算）、滚轮。
const ABS_MOUSE_DESC: &[u8] = &[
    0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, 0x09, 0x01, 0xA1, 0x00, //
    0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01, 0x95, 0x03, 0x75, 0x01, 0x81, 0x02, //
    0x95, 0x01, 0x75, 0x05, 0x81, 0x03, //
    0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x15, 0x00, 0x26, 0xFF, 0x0F, 0x75, 0x10, 0x95, 0x02, 0x81, 0x02, //
    0x09, 0x38, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x01, 0x81, 0x06, //
    0xC0, 0xC0,
];

/// 开机协议鼠标：3 键、dx、dy、滚轮。BIOS 只看前三个字节。
const REL_MOUSE_DESC: &[u8] = &[
    0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, 0x09, 0x01, 0xA1, 0x00, //
    0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01, 0x95, 0x03, 0x75, 0x01, 0x81, 0x02, //
    0x95, 0x01, 0x75, 0x05, 0x81, 0x03, //
    0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x03, 0x81, 0x06, //
    0xC0, 0xC0,
];

struct HidFunction {
    name: &'static str,
    protocol: u8,
    subclass: u8,
    report_length: u8,
    descriptor: &'static [u8],
}

const HIDS: [HidFunction; 3] = [
    HidFunction { name: KEYBOARD_FUNCTION, protocol: 1, subclass: 1, report_length: 8, descriptor: KEYBOARD_DESC },
    HidFunction { name: ABS_MOUSE_FUNCTION, protocol: 0, subclass: 0, report_length: 6, descriptor: ABS_MOUSE_DESC },
    HidFunction { name: REL_MOUSE_FUNCTION, protocol: 2, subclass: 1, report_length: 4, descriptor: REL_MOUSE_DESC },
];

pub fn run(user: Option<&str>) -> Result<()> {
    let user = user.context("用法：rust-kvm usb-gadget-setup <服务用户>")?;
    let arch = std::env::consts::ARCH;
    if !matches!(arch, "aarch64" | "arm") {
        println!("{arch} 上不建 USB gadget，跳过");
        return Ok(());
    }
    // 编进内核的话 modprobe 会失败，那不要紧，下面看 configfs 目录在不在
    let _ = Command::new("modprobe").arg("libcomposite").status();
    let paths = GadgetPaths::system();
    if !paths.configfs.is_dir() {
        println!("没有 {}，跳过", paths.configfs.display());
        return Ok(());
    }
    let Some(udc) = first_udc(&paths.udc_class) else {
        println!("没有 USB 设备控制器，跳过");
        return Ok(());
    };
    let owner = lookup_user(user)?;
    if paths.gadget().exists() {
        teardown(&paths).context("拆掉上次的 gadget 失败")?;
    }
    build(&paths, &serial_number())?;
    std::fs::write(paths.gadget().join("UDC"), &udc).with_context(|| format!("绑定 {udc} 失败"))?;
    hand_over(&paths, owner, Duration::from_secs(3))?;
    println!("gadget 已绑定到 {udc}，交给用户 {user}");
    Ok(())
}

/// 属性文件用 `fs::write`（带 create）：configfs 里它们已经存在，测试的临时目录里不存在。
pub fn build(paths: &GadgetPaths, serial: &str) -> Result<()> {
    let gadget = paths.gadget();
    attrs(&gadget, &[("idVendor", "0x1d6b"), ("idProduct", "0x0104"), ("bcdDevice", "0x0100"), ("bcdUSB", "0x0200")])?;
    attrs(
        &gadget.join("strings/0x409"),
        &[("manufacturer", "rust-kvm"), ("product", "Virtual KVM"), ("serialnumber", serial)],
    )?;
    let config = gadget.join(CONFIG_DIR);
    attrs(&config, &[("MaxPower", "250")])?;
    attrs(&config.join("strings/0x409"), &[("configuration", "rust-kvm")])?;

    for hid in &HIDS {
        let dir = paths.function(hid.name);
        attrs(
            &dir,
            &[
                ("protocol", &hid.protocol.to_string()),
                ("subclass", &hid.subclass.to_string()),
                ("report_length", &hid.report_length.to_string()),
            ],
        )?;
        std::fs::write(dir.join("report_desc"), hid.descriptor)
            .with_context(|| format!("写 {} 的描述符失败", hid.name))?;
    }

    // 光驱的 ro 先于 cdrom 写，两者都只能在空槽时改，setup 时正好是空的
    attrs(&paths.lun(Slot::Cdrom), &[("removable", "1"), ("ro", "1"), ("cdrom", "1")])?;
    attrs(&paths.lun(Slot::Disk), &[("removable", "1"), ("ro", "0"), ("cdrom", "0")])?;

    for function in HID_FUNCTIONS.iter().chain([&STORAGE_FUNCTION]) {
        let link = config.join(function);
        std::os::unix::fs::symlink(paths.function(function), &link)
            .with_context(|| format!("把 {function} 链进配置失败"))?;
    }
    Ok(())
}

/// hidg 节点由 udev 在绑定之后才建出来，所以要等。
pub fn hand_over(paths: &GadgetPaths, owner: (u32, u32), timeout: Duration) -> Result<()> {
    for slot in Slot::ALL {
        for attr in ["file", "ro", "forced_eject"] {
            chown(&paths.lun(slot).join(attr), owner)?;
        }
    }
    let deadline = Instant::now() + timeout;
    for function in HID_FUNCTIONS {
        let node = paths.hid_node(function)?;
        while !node.exists() {
            if Instant::now() >= deadline {
                bail!("等了 {timeout:?} 还没有 {}（udev 没建出来？）", node.display());
            }
            std::thread::sleep(Duration::from_millis(50));
        }
        chown(&node, owner)?;
    }
    Ok(())
}

/// 和建的顺序反过来。configfs 里只能 rmdir，不能删文件；`lun.0` 随功能目录一起没。
fn teardown(paths: &GadgetPaths) -> Result<()> {
    let gadget = paths.gadget();
    let _ = std::fs::write(gadget.join("UDC"), "\n");
    let config = gadget.join(CONFIG_DIR);
    if let Ok(entries) = std::fs::read_dir(&config) {
        for entry in entries.flatten() {
            if entry.file_type().is_ok_and(|kind| kind.is_symlink()) {
                std::fs::remove_file(entry.path())
                    .with_context(|| format!("删 {} 失败", entry.path().display()))?;
            }
        }
    }
    rmdir(&config.join("strings/0x409"))?;
    rmdir(&config)?;
    rmdir(&paths.lun(Slot::Disk))?;
    if let Ok(entries) = std::fs::read_dir(gadget.join("functions")) {
        for entry in entries.flatten() {
            rmdir(&entry.path())?;
        }
    }
    rmdir(&gadget.join("strings/0x409"))?;
    rmdir(&gadget)
}

fn rmdir(path: &Path) -> Result<()> {
    match std::fs::remove_dir(path) {
        Ok(()) => Ok(()),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(error) => Err(error).with_context(|| format!("rmdir {} 失败", path.display())),
    }
}

fn attrs(dir: &Path, values: &[(&str, &str)]) -> Result<()> {
    std::fs::create_dir_all(dir).with_context(|| format!("建 {} 失败", dir.display()))?;
    for (name, value) in values {
        std::fs::write(dir.join(name), value)
            .with_context(|| format!("写 {} 失败", dir.join(name).display()))?;
    }
    Ok(())
}

fn chown(path: &Path, (uid, gid): (u32, u32)) -> Result<()> {
    std::os::unix::fs::chown(path, Some(uid), Some(gid))
        .with_context(|| format!("chown {} 失败", path.display()))
}

fn first_udc(dir: &Path) -> Option<String> {
    let mut names: Vec<String> = std::fs::read_dir(dir)
        .ok()?
        .flatten()
        .map(|entry| entry.file_name().to_string_lossy().into_owned())
        .collect();
    names.sort();
    names.into_iter().next()
}

fn lookup_user(name: &str) -> Result<(u32, u32)> {
    let c_name = CString::new(name).context("用户名里有空字符")?;
    let entry = unsafe { libc::getpwnam(c_name.as_ptr()) };
    if entry.is_null() {
        bail!("没有用户 {name}");
    }
    let entry = unsafe { &*entry };
    Ok((entry.pw_uid, entry.pw_gid))
}

/// 每块板子一个固定的序列号，Windows 靠它记住「这是同一个设备」，不会每次插上都装一遍驱动。
fn serial_number() -> String {
    std::fs::read_to_string("/etc/machine-id")
        .ok()
        .map(|id| id.trim().chars().take(16).collect::<String>())
        .filter(|id| id.len() == 16)
        .unwrap_or_else(|| "0000000000000001".to_string())
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cargo test --bin rust-kvm gadget_setup`
Expected: 4 个测试全部 PASS。`teardown` 没有自动测试：普通目录里有文件时 `rmdir` 会失败，只能在真机上验证（Task 7）。

- [ ] **Step 5: 接上子命令**

`src/main.rs` 的 Linux 版 `run_command` 里，在 `"status" => systemd::status(),` 后面加一行：

```rust
        "usb-gadget-setup" => gadget_setup::run(std::env::args().nth(2).as_deref()),
```

Linux 版 `USAGE` 里，在 `rust-kvm status           看服务状态和日志位置` 这一行后面加：

```text
  rust-kvm usb-gadget-setup <用户>
                            建本机 USB 口的 gadget（ARM 板上 systemd 启动前自动跑，不用手动）
```

- [ ] **Step 6: 改 systemd 单元，先改测试**

`src/systemd.rs` 的测试模块换成：

```rust
#[cfg(test)]
mod tests {
    use super::*;

    /// 路径带空格时 ExecStart 必须加引号，否则 systemd 把后半截当成参数；
    /// RTPRIO 要和采音线程请求的优先级对上，低了就是白给。
    #[test]
    fn 单元文件的关键几行() {
        let unit = unit_file(
            Path::new("/opt/my kvm/rust-kvm"),
            Path::new("/opt/my kvm"),
            "alice",
            &["video", "audio"],
            false,
        );
        assert!(unit.contains("ExecStart=\"/opt/my kvm/rust-kvm\"\n"));
        assert!(unit.contains("User=alice\n"));
        assert!(unit.contains("SupplementaryGroups=video audio\n"));
        assert!(unit.contains(&format!("LimitRTPRIO={}\n", crate::audio::REALTIME_PRIORITY)));
        assert!(!unit.contains("usb-gadget-setup"));
    }

    /// `-` 让 gadget 建不起来时服务照样启动，`+` 让这一步以 root 跑。
    #[test]
    fn arm_上先以_root_建_gadget() {
        let unit = unit_file(Path::new("/usr/local/bin/rust-kvm"), Path::new("/srv/kvm"), "alice", &[], true);
        assert!(unit.contains("ExecStartPre=-+\"/usr/local/bin/rust-kvm\" usb-gadget-setup alice\n"));
    }
}
```

Run: `cargo test --bin rust-kvm systemd`
Expected: 编译失败，`unit_file` 只收 4 个参数。

- [ ] **Step 7: 改 `unit_file` 和 `install`**

`unit_file` 换成：

```rust
fn unit_file(exe: &Path, root: &Path, user: &str, groups: &[&str], gadget: bool) -> String {
    let gadget_setup = if gadget {
        format!("ExecStartPre=-+\"{}\" usb-gadget-setup {user}\n", exe.display())
    } else {
        String::new()
    };
    format!(
        "\
[Unit]
Description=rust-kvm 远程控制（采集卡画面/声音 + CH9329 键鼠）
After=network-online.target sound.target
Wants=network-online.target

[Service]
{gadget_setup}ExecStart=\"{exe}\"
WorkingDirectory={root}
User={user}
SupplementaryGroups={groups}
LimitRTPRIO={rtprio}
Restart=on-failure
RestartSec=2
# 收到 SIGTERM 后要把「全释放」包发出去，留足时间再强杀
TimeoutStopSec=10

[Install]
WantedBy=multi-user.target
",
        exe = exe.display(),
        root = root.display(),
        groups = groups.join(" "),
        rtprio = crate::audio::REALTIME_PRIORITY,
    )
}
```

`install` 里写单元文件那两行换成：

```rust
    // 只在 ARM 上加：x86 的板子没有 USB 设备控制器，多一步只会在日志里多一行「跳过」
    let gadget = cfg!(any(target_arch = "aarch64", target_arch = "arm"));
    std::fs::write(UNIT_PATH, unit_file(installed, &root, &user, &groups, gadget))
        .with_context(|| format!("写 {UNIT_PATH} 失败"))?;
```

并在 `println!("  资源目录：{}", root.display());` 后面加：

```rust
    if gadget {
        println!("  本机 USB 口：每次启动前以 root 建 gadget（虚拟光驱、U 盘、键鼠）");
    }
```

- [ ] **Step 8: 跑全部测试**

Run: `cargo test --bin rust-kvm`
Expected: 全部 PASS。

- [ ] **Step 9: 提交**

```bash
git add src/gadget_setup.rs src/systemd.rs src/main.rs
git -c user.name=flydmonkey -c user.email=flydmonkey@gmail.com commit -m "USB gadget setup：root 建 gadget、绑定、交权限；ARM 上 systemd 启动前自动跑"
```

---

### Task 4: 串口桥接上模拟器，设置页隐藏芯片一节

**Files:**
- Modify: `src/serial_bridge.rs`、`src/main.rs`、`web/settings.html`、`web/settings-page.js`

**Interfaces:**
- Consumes: `hid_emulator::Emulator`、`usb_gadget::{Availability, GadgetPaths, UsbGadget, detect}`、Task 2 的 `test_support::gadget()`
- Produces:
  - `serial_bridge::GADGET_PORT: &str = "usb-gadget"`
  - `SerialHub::new(gadget: Option<Arc<UsbGadget>>) -> Arc<SerialHub>`
  - `SerialHub::add_gadget_port(&self, ports: &mut Vec<PortInfo>)`
  - `main.rs` 里启动时得到的 `usb: usb_gadget::Availability`（Task 5 要用它建 `UsbService`）

- [ ] **Step 1: 写失败的测试**

`src/serial_bridge.rs` 末尾加测试模块：

```rust
#[cfg(test)]
mod tests {
    use super::*;
    use crate::usb_gadget::{KEYBOARD_FUNCTION, test_support};

    #[test]
    fn 直连口把键盘帧写成_hid_报告并回应答() {
        let (fixture, gadget) = test_support::gadget();
        let (link, mut replies) = SerialLink::open_gadget(gadget, 9600).unwrap();
        let frame = [0x57, 0xAB, 0x00, 0x02, 0x08, 0x00, 0x00, 0x04, 0, 0, 0, 0, 0, 0x10];
        assert!(link.write(Bytes::from_static(&frame)));
        let reply = replies.blocking_recv().unwrap();
        assert_eq!(&reply[..], &[0x57, 0xAB, 0x00, 0x82, 0x01, 0x00, 0x85]);
        let node = fixture.paths.hid_node(KEYBOARD_FUNCTION).unwrap();
        assert_eq!(std::fs::read(node).unwrap(), vec![0, 0, 0x04, 0, 0, 0, 0, 0]);
    }

    #[test]
    fn 有_gadget_时串口列表最前面多一项() {
        let (_fixture, gadget) = test_support::gadget();
        let mut ports = vec![PortInfo { name: "/dev/ttyUSB0".into(), description: None }];
        SerialHub::new(Some(gadget)).add_gadget_port(&mut ports);
        assert_eq!(ports[0].name, GADGET_PORT);
        assert_eq!(ports.len(), 2);

        let mut ports = Vec::new();
        SerialHub::new(None).add_gadget_port(&mut ports);
        assert!(ports.is_empty());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cargo test --bin rust-kvm serial_bridge`
Expected: 编译失败，没有 `open_gadget`、`GADGET_PORT`、`add_gadget_port`，`SerialHub::new` 参数个数不对。

- [ ] **Step 3: 实现串口桥部分**

`src/serial_bridge.rs`：

1. 模块文档第 5 行后面补一段：

```rust
//!
//! 另一个例外是假端口 `usb-gadget`：选它时不开串口，字节交给服务端的 CH9329 模拟器
//! （`hid_emulator`），翻成 HID 报告写进本机 USB 口的 `/dev/hidg*`。浏览器那边分不出来。
```

2. `use` 区加：

```rust
use crate::hid_emulator::Emulator;
use crate::usb_gadget::UsbGadget;
```

3. 在 `PortInfo` 定义前加常量：

```rust
/// 本机 USB 口直连的假端口名。配置里存的就是它，所以切换机器配置、安卓客户端都不用改。
pub const GADGET_PORT: &str = "usb-gadget";
```

4. `impl SerialLink` 里，在 `fn open` 后面加：

```rust
    fn open_gadget(gadget: Arc<UsbGadget>, baud_rate: u32) -> Result<(Self, mpsc::Receiver<Bytes>)> {
        let (write_tx, write_rx) = blocking_mpsc::channel::<Bytes>();
        let (read_tx, read_rx) = mpsc::channel::<Bytes>(64);
        let (done_tx, done_rx) = oneshot::channel();
        let round_trip_us = Arc::new(AtomicU64::new(0));
        let thread_round_trip = round_trip_us.clone();
        std::thread::Builder::new()
            .name("serial-usb-gadget".into())
            .spawn(move || {
                emulate(&gadget, write_rx, read_tx, &thread_round_trip);
                info!("本机 USB 口键鼠已关闭");
                let _ = done_tx.send(());
            })
            .context("启动 USB 口键鼠线程失败")?;
        info!("本机 USB 口键鼠已打开");
        Ok((
            Self {
                port: GADGET_PORT.to_string(),
                // 原样报回去：ch9329.js 拿它当「探测成功的波特率」记着，没有别的用处
                baud_rate,
                writes: write_tx,
                finished: done_rx,
                round_trip_us,
            },
            read_rx,
        ))
    }
```

5. 在 `fn pump` 后面加：

```rust
/// 直连口的线程体，对应真串口的 `pump`。没有包间隔、没有轮询：模拟器自己组帧，应答
/// 当场就有。被控机没接时报告写不进去，照样回成功——真 CH9329 没接被控机时也是这样，
/// 查信息时的 USB 状态会如实报未连接。
fn emulate(
    gadget: &UsbGadget,
    writes: blocking_mpsc::Receiver<Bytes>,
    reads: mpsc::Sender<Bytes>,
    round_trip_us: &AtomicU64,
) {
    let mut emulator = Emulator::new();
    while let Ok(bytes) = writes.recv() {
        let started = Instant::now();
        let output = emulator.feed(&bytes, &mut || gadget.status());
        for (target, report) in &output.reports {
            if let Err(error) = gadget.write_report(*target, report) {
                debug!("HID 报告没写进去（被控机没接或没在听）：{error}");
            }
        }
        if output.replies.is_empty() {
            continue;
        }
        round_trip_us.store(started.elapsed().as_micros().max(1) as u64, Ordering::Relaxed);
        if reads.blocking_send(Bytes::from(output.replies)).is_err() {
            return;
        }
    }
}
```

6. `SerialHub` 结构体加字段（放在 `evict` 后面）：

```rust
    /// 本机 USB 口可用时才有，`usb-gadget` 这个假端口靠它打开。
    gadget: Option<Arc<UsbGadget>>,
```

`SerialHub::new` 换成：

```rust
    pub fn new(gadget: Option<Arc<UsbGadget>>) -> Arc<Self> {
        Arc::new(Self {
            seat: Arc::new(tokio::sync::Mutex::new(())),
            evict: std::sync::Mutex::new(None),
            gadget,
        })
    }

    /// 串口列表里加上直连口。放最前面：接了这根线的人多半就是想用它。
    pub fn add_gadget_port(&self, ports: &mut Vec<PortInfo>) {
        if self.gadget.is_some() {
            ports.insert(
                0,
                PortInfo {
                    name: GADGET_PORT.to_string(),
                    description: Some("本机 USB 口直连（不需要 CH9329）".to_string()),
                },
            );
        }
    }
```

7. `handle` 里 `match open_port(port, baud_rate).await {` 改成：

```rust
                                match open_port(port, baud_rate, hub.gadget.clone()).await {
```

8. `open_port` 换成：

```rust
async fn open_port(
    port: String,
    baud_rate: u32,
    gadget: Option<Arc<UsbGadget>>,
) -> Result<(SerialLink, mpsc::Receiver<Bytes>)> {
    if port == GADGET_PORT {
        let gadget = gadget.context("本机 USB 口不可用，原因见设置页「键鼠」一节")?;
        return SerialLink::open_gadget(gadget, baud_rate);
    }
    let opening = tokio::task::spawn_blocking(move || SerialLink::open(&port, baud_rate));
    tokio::time::timeout(OPEN_TIMEOUT, opening)
        .await
        .context("打开串口超时")?
        .context("打开串口的任务异常退出")?
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cargo test --bin rust-kvm serial_bridge`
Expected: 编译失败只剩 `main.rs` 里 `SerialHub::new()` 少参数；先做 Step 5 再跑。

- [ ] **Step 5: 接到 `main.rs`**

1. `use` 区加：

```rust
use usb_gadget::{Availability, GadgetPaths};
```

2. `run_server` 里，`let settings = store.get();` 后面加：

```rust
    let usb = usb_gadget::detect(&GadgetPaths::system(), std::env::consts::ARCH);
    let gadget = match &usb {
        Availability::Ready(gadget) => {
            info!("本机 USB 口可用：虚拟光驱、U 盘和直连键鼠");
            gadget.spawn_led_reader();
            Some(gadget.clone())
        }
        Availability::Unavailable(reason) => {
            warn!("本机 USB 口不可用：{reason}");
            None
        }
        Availability::Unsupported => None,
    };
```

3. `AppState` 初始化里 `serial: SerialHub::new(),` 改成 `serial: SerialHub::new(gadget),`。

4. `serial_ports` 和 `list_devices` 换成：

```rust
/// 远程浏览器没有宿主机的 Web Serial 权限，选口只能靠服务端枚举。
async fn serial_ports(State(state): State<AppState>) -> Json<Vec<PortInfo>> {
    let mut ports = serial_bridge::list_ports();
    state.serial.add_gadget_port(&mut ports);
    Json(ports)
}

/// 每次请求都重新枚举，不缓存：设备是会被插拔的，而设置页上那个列表过期了
/// 就直接导致选不到设备。
async fn list_devices(State(state): State<AppState>) -> Json<Devices> {
    let mut devices = devices::enumerate();
    state.serial.add_gadget_port(&mut devices.serial.items);
    Json(devices)
}
```

5. `put_config` 里，在「继电器和 CH9329 不能是同一个串口」那个检查后面加：

```rust
    if config
        .atx
        .as_ref()
        .is_some_and(|relay| relay.port == serial_bridge::GADGET_PORT)
    {
        return Err((
            axum::http::StatusCode::BAD_REQUEST,
            "继电器要接真串口，不能选本机 USB 口",
        )
            .into_response());
    }
```

Run: `cargo test --bin rust-kvm`
Expected: 全部 PASS；`hid_emulator` 的 `dead_code` 警告消失。`usb_gadget` 里 `Slot::other`、`mount` 等挂盘相关的函数还没人调，会有 `dead_code` 警告，Task 5 接上后消失。

- [ ] **Step 6: 设置页：选直连时隐藏芯片一节，继电器列表去掉假端口**

`web/settings.html`：`<h2>CH9329 芯片</h2>` 所在的 `<section>` 改成 `<section id="chip-section">`。键鼠那一节里，`<div class="problem" id="serial-problem"></div>` 后面加：

```html
        <p class="hint" id="gadget-hint" hidden></p>
```

`web/settings-page.js`：

1. `const chipButtons = …` 后面加：

```js
    // 本机 USB 口直连的假端口。选它时没有芯片可读写，芯片那一节整个藏起来
    const GADGET_PORT = "usb-gadget";
    const chipSection = el("chip-section");

    function renderChipSection() {
        chipSection.hidden = serialPort.value === GADGET_PORT;
    }
```

2. `renderDevices` 里继电器那段换成：

```js
        fill(atxPort, [{value: "", label: "不使用"}].concat(
            devices.serial.items
                .filter((item) => item.name !== GADGET_PORT)
                .map((item) => ({
                    value: item.name,
                    label: item.description ? `${item.name} — ${item.description}` : item.name
                }))
        ));
```

3. `renderConfig` 里 `selectOrKeep(serialPort, config.serial.port);` 后面加 `renderChipSection();`。

4. 事件绑定区（`atxPort.addEventListener("change", renderAtxState);` 附近）加：

```js
    serialPort.addEventListener("change", renderChipSection);
```

Run: `node --test tests/web/`
Expected: 全部 PASS（这一步没有新测试，确认没弄坏别的）。

- [ ] **Step 7: 提交**

```bash
git add src/serial_bridge.rs src/main.rs web/settings.html web/settings-page.js
git -c user.name=flydmonkey -c user.email=flydmonkey@gmail.com commit -m "串口桥：usb-gadget 假端口走 CH9329 模拟器写 HID；设置页选直连时隐藏芯片一节"
```

---

### Task 5: 镜像目录和 `/api/usb*`

**Files:**
- Create: `src/media.rs`、`src/usb_api.rs`
- Modify: `src/main.rs`、`Cargo.toml`

**Interfaces:**
- Consumes: `usb_gadget::{Availability, Slot, SlotState, UsbGadget}`、Task 4 里 `run_server` 的 `usb` 变量、`test_support::gadget()`
- Produces:
  - `media::DIR_NAME = "images"`、`media::validate_name(&str) -> Result<(), &'static str>`、`media::is_iso(&str) -> bool`
  - `media::ImageInfo { name: String, size: u64 }`
  - `media::ImageDir::new(PathBuf)`、`root()`、`path(&str)`、`part_path(&str)`、`list() -> io::Result<Vec<ImageInfo>>`、`name_of(&Path) -> String`
  - `usb_api::UsbService::new(&Availability, ImageDir) -> Arc<UsbService>`、`usb_api::router<S>(Arc<UsbService>) -> Router<S>`
  - HTTP：`GET /api/usb`、`POST /api/usb/{cdrom|disk}/mount`（`{image, readOnly}`）、`POST /api/usb/{cdrom|disk}/eject`、`PUT|DELETE /api/usb/images/{name}`；成功都回概览 JSON：`{available, reason, slots: {cdrom: {image, readOnly}, disk: {…}} | null, images: [{name, size}]}`

- [ ] **Step 1: 依赖和模块**

`Cargo.toml` 里 tokio 那一行换成：

```toml
tokio = { version = "1.53.1", features = ["rt-multi-thread", "macros", "sync", "time", "signal", "fs", "io-util"] }
```

`src/main.rs` 模块列表里，在 `mod hid_emulator;` 后面加 `mod media;`，在 `mod usb_gadget;` 前面加 `mod usb_api;`：

```rust
mod media;
```

```rust
mod usb_api;
```

- [ ] **Step 2: `media.rs` 的失败测试**

新建 `src/media.rs`：

```rust
//! 镜像目录：资源目录下的 `images/`。光驱挂 `.iso`，U 盘挂 `.iso` 或 `.img`。
//!
//! 不做成配置项：安卓客户端保存配置时会丢掉它不认识的字段。想放到别的盘上，
//! 把 `images` 做成软链接。

use std::io;
use std::path::{Path, PathBuf};

use serde::Serialize;

pub const DIR_NAME: &str = "images";
const PART_SUFFIX: &str = ".part";

pub fn validate_name(_name: &str) -> Result<(), &'static str> {
    Err("还没实现")
}

pub fn is_iso(name: &str) -> bool {
    name.to_ascii_lowercase().ends_with(".iso")
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct ImageInfo {
    pub name: String,
    pub size: u64,
}

pub struct ImageDir {
    root: PathBuf,
}

impl ImageDir {
    pub fn new(root: PathBuf) -> Self {
        Self { root }
    }

    pub fn root(&self) -> &Path {
        &self.root
    }

    pub fn path(&self, name: &str) -> Result<PathBuf, &'static str> {
        validate_name(name)?;
        Ok(self.root.join(name))
    }

    pub fn part_path(&self, name: &str) -> Result<PathBuf, &'static str> {
        validate_name(name)?;
        Ok(self.root.join(format!("{name}{PART_SUFFIX}")))
    }

    pub fn list(&self) -> io::Result<Vec<ImageInfo>> {
        Ok(Vec::new())
    }

    pub fn name_of(&self, image: &Path) -> String {
        image.display().to_string()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn 合法的文件名() {
        for name in ["win11.iso", "Drivers.IMG", "ubuntu 24.04.iso", "驱动盘.img"] {
            assert_eq!(validate_name(name), Ok(()), "{name}");
        }
    }

    #[test]
    fn 不合法的文件名() {
        for name in ["", "../x.iso", "a/b.iso", "a\\b.iso", ".hidden.iso", "x.exe", "x.iso.part"] {
            assert!(validate_name(name).is_err(), "{name}");
        }
    }

    #[test]
    fn 只列镜像并按名字排序() {
        let temp = tempfile::tempdir().unwrap();
        for name in ["b.iso", "a.img", "c.txt", "d.iso.part"] {
            std::fs::write(temp.path().join(name), "12345").unwrap();
        }
        std::fs::create_dir(temp.path().join("e.iso")).unwrap();
        let images = ImageDir::new(temp.path().to_path_buf()).list().unwrap();
        assert_eq!(
            images,
            vec![
                ImageInfo { name: "a.img".into(), size: 5 },
                ImageInfo { name: "b.iso".into(), size: 5 },
            ]
        );
    }

    #[test]
    fn 目录不存在时列表为错() {
        let temp = tempfile::tempdir().unwrap();
        assert!(ImageDir::new(temp.path().join("missing")).list().is_err());
    }

    #[test]
    fn 槽里的路径换回镜像名() {
        let dir = ImageDir::new(PathBuf::from("/srv/kvm/images"));
        assert_eq!(dir.name_of(Path::new("/srv/kvm/images/win.iso")), "win.iso");
        assert_eq!(dir.name_of(Path::new("/mnt/other/x.img")), "/mnt/other/x.img");
    }
}
```

Run: `cargo test --bin rust-kvm media`
Expected: `合法的文件名`、`只列镜像并按名字排序`、`槽里的路径换回镜像名` FAIL。

- [ ] **Step 3: 实现 `media.rs`**

把 `validate_name`、`list`、`name_of` 换成：

```rust
/// 只允许一层普通文件名：网页传上来的名字会直接拼进路径，带 `/` 或 `..` 就能写到目录外面。
pub fn validate_name(name: &str) -> Result<(), &'static str> {
    if name.is_empty() || name.len() > 200 {
        return Err("文件名为空或太长");
    }
    if name.starts_with('.') || name.contains(['/', '\\', '\0']) {
        return Err("文件名只能是一层普通名字，不能带路径，也不能以 . 开头");
    }
    let lower = name.to_ascii_lowercase();
    if !(lower.ends_with(".iso") || lower.ends_with(".img")) {
        return Err("只支持 .iso 和 .img");
    }
    Ok(())
}
```

```rust
    /// 跟着软链接看是不是普通文件：用户可能把大镜像放在别处、在这里链一个过来。
    pub fn list(&self) -> io::Result<Vec<ImageInfo>> {
        let mut images = Vec::new();
        for entry in std::fs::read_dir(&self.root)? {
            let entry = entry?;
            let name = entry.file_name().to_string_lossy().into_owned();
            if validate_name(&name).is_err() {
                continue;
            }
            let Ok(meta) = std::fs::metadata(entry.path()) else {
                continue;
            };
            if meta.is_file() {
                images.push(ImageInfo { name, size: meta.len() });
            }
        }
        images.sort_by(|a, b| a.name.cmp(&b.name));
        Ok(images)
    }

    /// 不在镜像目录里的（手动往 configfs 写的）原样显示全路径。
    pub fn name_of(&self, image: &Path) -> String {
        match (image.parent(), image.file_name()) {
            (Some(parent), Some(name)) if parent == self.root => name.to_string_lossy().into_owned(),
            _ => image.display().to_string(),
        }
    }
```

Run: `cargo test --bin rust-kvm media`
Expected: 5 个测试全部 PASS。

- [ ] **Step 4: `usb_api.rs` 的失败测试**

新建 `src/usb_api.rs`，先放类型、空路由和测试：

```rust
//! `/api/usb*`：本机 USB 口上的光驱和 U 盘。查状态、挂盘弹盘、上传删除镜像。

use std::collections::HashSet;
use std::path::{Path as FsPath, PathBuf};
use std::sync::{Arc, Mutex};

use anyhow::{Context, Result};
use axum::Json;
use axum::Router;
use axum::body::Body;
use axum::extract::{DefaultBodyLimit, Path, State};
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post, put};
use futures_util::StreamExt;
use serde::{Deserialize, Serialize};
use tokio::io::AsyncWriteExt;
use tracing::{info, warn};

use crate::media::{self, ImageDir, ImageInfo};
use crate::usb_gadget::{Availability, Slot, SlotState, UsbGadget};

pub struct UsbService {
    gadget: Option<Arc<UsbGadget>>,
    reason: Option<String>,
    images: ImageDir,
    uploading: Mutex<HashSet<String>>,
}

impl UsbService {
    pub fn new(availability: &Availability, images: ImageDir) -> Arc<Self> {
        let (gadget, reason) = match availability {
            Availability::Ready(gadget) => (Some(gadget.clone()), None),
            Availability::Unavailable(reason) => (None, Some(reason.clone())),
            Availability::Unsupported => (None, None),
        };
        Arc::new(Self { gadget, reason, images, uploading: Mutex::new(HashSet::new()) })
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Overview {
    available: bool,
    reason: Option<String>,
    slots: Option<Slots>,
    images: Vec<ImageInfo>,
}

#[derive(Serialize)]
struct Slots {
    cdrom: SlotView,
    disk: SlotView,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct SlotView {
    image: Option<String>,
    read_only: bool,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct MountRequest {
    image: String,
    #[serde(default)]
    read_only: bool,
}

pub fn router<S: Clone + Send + Sync + 'static>(service: Arc<UsbService>) -> Router<S> {
    Router::new().with_state(service)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::usb_gadget::test_support;

    struct Setup {
        _fixture: test_support::Fixture,
        service: Arc<UsbService>,
        images: PathBuf,
    }

    fn setup() -> Setup {
        let (fixture, gadget) = test_support::gadget();
        let images = fixture.temp.path().join("images");
        std::fs::create_dir_all(&images).unwrap();
        std::fs::write(images.join("win.iso"), "iso").unwrap();
        std::fs::write(images.join("drivers.img"), "img").unwrap();
        let service = UsbService::new(&Availability::Ready(gadget), ImageDir::new(images.clone()));
        Setup { _fixture: fixture, service, images }
    }

    async fn json(response: Response) -> serde_json::Value {
        let bytes = axum::body::to_bytes(response.into_body(), usize::MAX).await.unwrap();
        serde_json::from_slice(&bytes).unwrap()
    }

    fn request(image: &str, read_only: bool) -> Json<MountRequest> {
        Json(MountRequest { image: image.into(), read_only })
    }

    #[tokio::test]
    async fn 挂上之后概览里是镜像名() {
        let s = setup();
        let response = mount(State(s.service.clone()), Path(Slot::Cdrom), request("win.iso", false)).await;
        assert_eq!(response.status(), StatusCode::OK);
        let body = json(response).await;
        assert_eq!(body["slots"]["cdrom"]["image"], "win.iso");
        assert_eq!(body["slots"]["cdrom"]["readOnly"], true);
        assert_eq!(body["slots"]["disk"]["image"], serde_json::Value::Null);
        assert_eq!(body["images"].as_array().unwrap().len(), 2);
    }

    #[tokio::test]
    async fn 光驱不收_img() {
        let s = setup();
        let response = mount(State(s.service), Path(Slot::Cdrom), request("drivers.img", false)).await;
        assert_eq!(response.status(), StatusCode::BAD_REQUEST);
    }

    #[tokio::test]
    async fn 不存在的镜像回_404() {
        let s = setup();
        let response = mount(State(s.service), Path(Slot::Disk), request("nope.img", false)).await;
        assert_eq!(response.status(), StatusCode::NOT_FOUND);
    }

    #[tokio::test]
    async fn 同一个镜像不能挂两个槽() {
        let s = setup();
        mount(State(s.service.clone()), Path(Slot::Cdrom), request("win.iso", false)).await;
        let response = mount(State(s.service), Path(Slot::Disk), request("win.iso", false)).await;
        assert_eq!(response.status(), StatusCode::CONFLICT);
    }

    #[tokio::test]
    async fn 弹出后槽是空的() {
        let s = setup();
        mount(State(s.service.clone()), Path(Slot::Disk), request("drivers.img", true)).await;
        let body = json(eject(State(s.service), Path(Slot::Disk)).await).await;
        assert_eq!(body["slots"]["disk"]["image"], serde_json::Value::Null);
    }

    #[tokio::test]
    async fn 上传后改名_不留_part() {
        let s = setup();
        let response = upload(State(s.service), Path("new.img".into()), Body::from("hello")).await;
        assert_eq!(response.status(), StatusCode::OK);
        assert_eq!(std::fs::read_to_string(s.images.join("new.img")).unwrap(), "hello");
        assert!(!s.images.join("new.img.part").exists());
    }

    #[tokio::test]
    async fn 非法文件名回_400() {
        let s = setup();
        let response = upload(State(s.service), Path("../x.iso".into()), Body::from("x")).await;
        assert_eq!(response.status(), StatusCode::BAD_REQUEST);
    }

    #[tokio::test]
    async fn 正挂着的镜像不能删也不能覆盖() {
        let s = setup();
        mount(State(s.service.clone()), Path(Slot::Disk), request("drivers.img", false)).await;
        let response = delete_image(State(s.service.clone()), Path("drivers.img".into())).await;
        assert_eq!(response.status(), StatusCode::CONFLICT);
        let response = upload(State(s.service), Path("drivers.img".into()), Body::from("x")).await;
        assert_eq!(response.status(), StatusCode::CONFLICT);
        assert_eq!(std::fs::read_to_string(s.images.join("drivers.img")).unwrap(), "img");
    }

    #[tokio::test]
    async fn 删除镜像() {
        let s = setup();
        let response = delete_image(State(s.service), Path("win.iso".into())).await;
        assert_eq!(response.status(), StatusCode::OK);
        assert!(!s.images.join("win.iso").exists());
    }

    #[tokio::test]
    async fn 不可用时说明原因() {
        let temp = tempfile::tempdir().unwrap();
        let service = UsbService::new(
            &Availability::Unavailable("没有 USB 设备控制器".into()),
            ImageDir::new(temp.path().to_path_buf()),
        );
        let body = json(overview(State(service.clone())).await.into_response()).await;
        assert_eq!(body["available"], false);
        assert_eq!(body["reason"], "没有 USB 设备控制器");
        let response = mount(State(service), Path(Slot::Disk), request("a.img", false)).await;
        assert_eq!(response.status(), StatusCode::SERVICE_UNAVAILABLE);
    }
}
```

Run: `cargo test --bin rust-kvm usb_api`
Expected: 编译失败，`mount`、`eject`、`upload`、`delete_image`、`overview` 未定义。

- [ ] **Step 5: 实现 `usb_api.rs`**

把 `pub fn router…` 换成下面的代码（测试不动）：

```rust
pub fn router<S: Clone + Send + Sync + 'static>(service: Arc<UsbService>) -> Router<S> {
    Router::new()
        .route("/api/usb", get(overview))
        .route("/api/usb/{slot}/mount", post(mount))
        .route("/api/usb/{slot}/eject", post(eject))
        // ISO 动辄几个 GB，默认的 2 MB 上限在这里没有意义
        .route(
            "/api/usb/images/{name}",
            put(upload).delete(delete_image).layer(DefaultBodyLimit::disable()),
        )
        .with_state(service)
}

impl UsbService {
    fn view(&self, state: SlotState) -> SlotView {
        SlotView {
            image: state.image.map(|path| self.images.name_of(&path)),
            read_only: state.read_only,
        }
    }

    fn is_mounted(&self, path: &FsPath) -> bool {
        self.gadget.as_ref().is_some_and(|gadget| {
            Slot::ALL.iter().any(|slot| gadget.slot(*slot).image.as_deref() == Some(path))
        })
    }

    fn unavailable(&self) -> Response {
        let reason = self.reason.as_deref().unwrap_or("这台宿主机不支持本机 USB 口");
        (StatusCode::SERVICE_UNAVAILABLE, reason.to_string()).into_response()
    }
}

async fn overview(State(service): State<Arc<UsbService>>) -> Json<Overview> {
    let slots = service.gadget.as_ref().map(|gadget| Slots {
        cdrom: service.view(gadget.slot(Slot::Cdrom)),
        disk: service.view(gadget.slot(Slot::Disk)),
    });
    Json(Overview {
        available: service.gadget.is_some(),
        reason: service.reason.clone(),
        slots,
        // 目录没建（不可用的板子上就不建）时就是空列表
        images: service.images.list().unwrap_or_default(),
    })
}

async fn mount(
    State(service): State<Arc<UsbService>>,
    Path(slot): Path<Slot>,
    Json(request): Json<MountRequest>,
) -> Response {
    let Some(gadget) = service.gadget.clone() else {
        return service.unavailable();
    };
    let path = match service.images.path(&request.image) {
        Ok(path) => path,
        Err(message) => return (StatusCode::BAD_REQUEST, message).into_response(),
    };
    if slot == Slot::Cdrom && !media::is_iso(&request.image) {
        return (StatusCode::BAD_REQUEST, "光驱只能挂 .iso").into_response();
    }
    if !path.is_file() {
        return (StatusCode::NOT_FOUND, format!("镜像目录里没有 {}", request.image)).into_response();
    }
    if service.uploading.lock().expect("上传表锁").contains(&request.image) {
        return (StatusCode::CONFLICT, "这个镜像正在上传，传完再挂").into_response();
    }
    if gadget.slot(slot.other()).image.as_deref() == Some(path.as_path()) {
        return (StatusCode::CONFLICT, "这个镜像已经挂在另一个槽上了").into_response();
    }
    let read_only = request.read_only;
    let mounted = tokio::task::spawn_blocking(move || gadget.mount(slot, &path, read_only)).await;
    match mounted {
        Ok(Ok(())) => {
            info!("{slot:?} 挂上了 {}", request.image);
            overview(State(service)).await.into_response()
        }
        Ok(Err(error)) => (StatusCode::INTERNAL_SERVER_ERROR, format!("{error:#}")).into_response(),
        Err(error) => {
            (StatusCode::INTERNAL_SERVER_ERROR, format!("挂盘线程异常退出：{error}")).into_response()
        }
    }
}

async fn eject(State(service): State<Arc<UsbService>>, Path(slot): Path<Slot>) -> Response {
    let Some(gadget) = service.gadget.clone() else {
        return service.unavailable();
    };
    match tokio::task::spawn_blocking(move || gadget.eject(slot)).await {
        Ok(Ok(())) => {
            info!("{slot:?} 已弹出");
            overview(State(service)).await.into_response()
        }
        Ok(Err(error)) => (StatusCode::INTERNAL_SERVER_ERROR, format!("{error:#}")).into_response(),
        Err(error) => {
            (StatusCode::INTERNAL_SERVER_ERROR, format!("弹盘线程异常退出：{error}")).into_response()
        }
    }
}

/// 占着「正在上传」这个名字，并且在没传完时删掉 `.part`。用 Drop 而不是在出错分支里删：
/// 浏览器中途关掉时，处理函数的 future 直接被丢弃，出错分支根本走不到。
struct UploadGuard {
    service: Arc<UsbService>,
    name: String,
    part: PathBuf,
    done: bool,
}

impl Drop for UploadGuard {
    fn drop(&mut self) {
        if !self.done {
            let _ = std::fs::remove_file(&self.part);
        }
        self.service.uploading.lock().expect("上传表锁").remove(&self.name);
    }
}

async fn upload(
    State(service): State<Arc<UsbService>>,
    Path(name): Path<String>,
    body: Body,
) -> Response {
    if service.gadget.is_none() {
        return service.unavailable();
    }
    let (target, part) = match (service.images.path(&name), service.images.part_path(&name)) {
        (Ok(target), Ok(part)) => (target, part),
        (Err(message), _) | (_, Err(message)) => {
            return (StatusCode::BAD_REQUEST, message).into_response();
        }
    };
    if service.is_mounted(&target) {
        return (StatusCode::CONFLICT, "这个镜像正挂着，先弹出再覆盖").into_response();
    }
    if !service.uploading.lock().expect("上传表锁").insert(name.clone()) {
        return (StatusCode::CONFLICT, "同名文件正在上传").into_response();
    }
    let mut guard = UploadGuard { service: service.clone(), name: name.clone(), part: part.clone(), done: false };
    match write_upload(body, &part, &target).await {
        Ok(size) => {
            guard.done = true;
            drop(guard);
            info!("上传了 {name}（{size} 字节）");
            overview(State(service)).await.into_response()
        }
        Err(error) => {
            warn!("上传 {name} 失败：{error:#}");
            (StatusCode::INTERNAL_SERVER_ERROR, format!("{error:#}")).into_response()
        }
    }
}

/// 先写 `.part`、落盘、再改名：列表里永远不会出现一个只传了一半的镜像。
async fn write_upload(body: Body, part: &FsPath, target: &FsPath) -> Result<u64> {
    let mut file = tokio::fs::File::create(part)
        .await
        .with_context(|| format!("建 {} 失败", part.display()))?;
    let mut stream = body.into_data_stream();
    let mut size = 0u64;
    while let Some(chunk) = stream.next().await {
        let chunk = chunk.context("上传中断")?;
        size += chunk.len() as u64;
        file.write_all(&chunk).await.context("写镜像失败")?;
    }
    file.sync_all().await.context("镜像落盘失败")?;
    drop(file);
    tokio::fs::rename(part, target)
        .await
        .with_context(|| format!("改名成 {} 失败", target.display()))?;
    Ok(size)
}

async fn delete_image(State(service): State<Arc<UsbService>>, Path(name): Path<String>) -> Response {
    if service.gadget.is_none() {
        return service.unavailable();
    }
    let target = match service.images.path(&name) {
        Ok(target) => target,
        Err(message) => return (StatusCode::BAD_REQUEST, message).into_response(),
    };
    if service.is_mounted(&target) {
        return (StatusCode::CONFLICT, "这个镜像正挂着，先弹出再删").into_response();
    }
    match tokio::fs::remove_file(&target).await {
        Ok(()) => {
            info!("删了镜像 {name}");
            overview(State(service)).await.into_response()
        }
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            (StatusCode::NOT_FOUND, format!("镜像目录里没有 {name}")).into_response()
        }
        Err(error) => (StatusCode::INTERNAL_SERVER_ERROR, format!("删除失败：{error}")).into_response(),
    }
}
```

Run: `cargo test --bin rust-kvm usb_api`
Expected: 10 个测试全部 PASS。

- [ ] **Step 6: 接到 `main.rs`**

1. Task 4 加的 `let gadget = match &usb { … };` 后面加：

```rust
    // 只在可用时建目录：x86 上不该凭空多出一个 images/
    let image_root = root.join(media::DIR_NAME);
    if gadget.is_some()
        && let Err(error) = std::fs::create_dir_all(&image_root)
    {
        warn!("建镜像目录 {} 失败：{error}", image_root.display());
    }
    // 规范化之后再交出去：写进 configfs 的必须是绝对路径，软链接也要解开，
    // 否则槽里读回来的路径和这里拼出来的对不上，概览里就显示成全路径
    let image_root = image_root.canonicalize().unwrap_or(image_root);
    let usb_service = usb_api::UsbService::new(&usb, media::ImageDir::new(image_root));
```

2. 路由里 `.route("/cert.crt", get(download_cert))` 后面加：

```rust
        .merge(usb_api::router(usb_service))
```

Run: `cargo test --bin rust-kvm && cargo clippy --bins 2>&1 | rg "^(warning|error)"`
Expected: 测试全部 PASS；clippy 只有原来那条 `result_large_err`（`put_config`）。

- [ ] **Step 7: 提交**

```bash
git add Cargo.toml Cargo.lock src/media.rs src/usb_api.rs src/main.rs
git -c user.name=flydmonkey -c user.email=flydmonkey@gmail.com commit -m "/api/usb：镜像目录、挂盘弹盘、流式上传和删除"
```

---

### Task 6: 控制页「虚拟介质」面板，设置页显示不可用原因

**Files:**
- Create: `web/usb-media.js`、`web/media-panel.js`、`tests/web/usb-media.test.js`
- Modify: `web/index.html`、`web/settings-page.js`

**Interfaces:**
- Consumes: Task 5 的概览 JSON；`window.KvmToast`、`window.KvmClosePanels`（`toolbar.js`）
- Produces: 全局 `UsbMedia`：`SLOT_LABELS`、`formatSize(bytes)`、`choices(images, slot)`、`slotText(slot, state)`、`showButton(overview)`、`acceptsUpload(name)`、`unavailableHint(overview)`

- [ ] **Step 1: 写失败的测试**

新建 `tests/web/usb-media.test.js`：

```js
// 运行：node --test "tests/web/*.test.js"
const test = require("node:test");
const assert = require("node:assert");
const path = require("node:path");
const UsbMedia = require(path.join(__dirname, "../../web/usb-media.js"));

test("大小按 1024 进位，B 不带小数", () => {
    assert.strictEqual(UsbMedia.formatSize(512), "512 B");
    assert.strictEqual(UsbMedia.formatSize(1536), "1.5 KB");
    assert.strictEqual(UsbMedia.formatSize(5046586573), "4.7 GB");
});

test("光驱只列 .iso，U 盘都列", () => {
    const images = [{name: "a.img", size: 1}, {name: "B.ISO", size: 1}, {name: "c.iso", size: 1}];
    assert.deepStrictEqual(UsbMedia.choices(images, "cdrom"), ["B.ISO", "c.iso"]);
    assert.deepStrictEqual(UsbMedia.choices(images, "disk"), ["a.img", "B.ISO", "c.iso"]);
});

test("槽位文案", () => {
    assert.strictEqual(UsbMedia.slotText("cdrom", {image: null, readOnly: true}), "空");
    assert.strictEqual(UsbMedia.slotText("cdrom", {image: "win.iso", readOnly: true}), "win.iso");
    assert.strictEqual(UsbMedia.slotText("disk", {image: "d.img", readOnly: true}), "d.img（只读）");
    assert.strictEqual(UsbMedia.slotText("disk", {image: "d.img", readOnly: false}), "d.img");
});

test("只有可用时才显示工具栏按钮", () => {
    assert.strictEqual(UsbMedia.showButton(null), false);
    assert.strictEqual(UsbMedia.showButton({available: false, reason: "x"}), false);
    assert.strictEqual(UsbMedia.showButton({available: true, slots: {}, images: []}), true);
});

test("只收 .iso 和 .img", () => {
    assert.strictEqual(UsbMedia.acceptsUpload("x.ISO"), true);
    assert.strictEqual(UsbMedia.acceptsUpload("x.img"), true);
    assert.strictEqual(UsbMedia.acceptsUpload("x.zip"), false);
});

test("不可用的原因只在 ARM 上给（x86 上 reason 是 null）", () => {
    assert.strictEqual(UsbMedia.unavailableHint(null), "");
    assert.strictEqual(UsbMedia.unavailableHint({available: false, reason: null}), "");
    assert.strictEqual(UsbMedia.unavailableHint({available: true, reason: null}), "");
    assert.strictEqual(
        UsbMedia.unavailableHint({available: false, reason: "没有 USB 设备控制器"}),
        "本机 USB 口直连不可用：没有 USB 设备控制器"
    );
});
```

Run: `node --test tests/web/usb-media.test.js`
Expected: FAIL，`Cannot find module …/web/usb-media.js`。

- [ ] **Step 2: 实现 `web/usb-media.js`**

```js
// 本机 USB 口上的光驱和 U 盘：面板和设置页共用的纯函数。
var UsbMedia = (function () {
    var SLOT_LABELS = {cdrom: "光驱", disk: "U 盘"};
    var UNITS = ["B", "KB", "MB", "GB", "TB"];

    function formatSize(bytes) {
        var value = bytes;
        var unit = 0;
        while (value >= 1024 && unit < UNITS.length - 1) {
            value /= 1024;
            unit++;
        }
        return unit === 0 ? value + " B" : value.toFixed(1) + " " + UNITS[unit];
    }

    function isIso(name) {
        return /\.iso$/i.test(name);
    }

    function acceptsUpload(name) {
        return /\.(iso|img)$/i.test(name);
    }

    // 光驱只认 ISO：硬盘镜像当光盘挂上，被控机只会报「无法读取」
    function choices(images, slot) {
        return images
            .filter(function (image) {
                return slot !== "cdrom" || isIso(image.name);
            })
            .map(function (image) {
                return image.name;
            });
    }

    function slotText(slot, state) {
        if (!state || !state.image) {
            return "空";
        }
        return slot === "disk" && state.readOnly ? state.image + "（只读）" : state.image;
    }

    function showButton(overview) {
        return !!overview && overview.available === true;
    }

    function unavailableHint(overview) {
        if (!overview || overview.available || !overview.reason) {
            return "";
        }
        return "本机 USB 口直连不可用：" + overview.reason;
    }

    return {
        SLOT_LABELS: SLOT_LABELS,
        formatSize: formatSize,
        acceptsUpload: acceptsUpload,
        choices: choices,
        slotText: slotText,
        showButton: showButton,
        unavailableHint: unavailableHint
    };
})();

if (typeof module !== "undefined" && module.exports) {
    module.exports = UsbMedia;
}
```

Run: `node --test tests/web/`
Expected: 全部 PASS（原来 108 个加新的 6 个）。

- [ ] **Step 3: 面板 `web/media-panel.js`**

```js
// 虚拟介质面板：本机 USB 口上的光驱和 U 盘。只有服务端报 available 时才出现
// （ARM 板、有 USB 设备控制器、gadget 建好了）。
(function () {
    const button = document.getElementById("media");
    const panel = document.getElementById("media-panel");
    const list = document.getElementById("media-images");
    const fileInput = document.getElementById("media-file");
    const uploadButton = document.getElementById("media-upload");
    const progress = document.getElementById("media-progress");
    const slots = {
        cdrom: {
            select: document.getElementById("media-cdrom-image"),
            mount: document.getElementById("media-cdrom-mount"),
            eject: document.getElementById("media-cdrom-eject"),
            state: document.getElementById("media-cdrom-state")
        },
        disk: {
            select: document.getElementById("media-disk-image"),
            mount: document.getElementById("media-disk-mount"),
            eject: document.getElementById("media-disk-eject"),
            state: document.getElementById("media-disk-state"),
            readOnly: document.getElementById("media-disk-ro")
        }
    };

    const toast = (text, level) => window.KvmToast && KvmToast(text, level);

    let overview = null;
    let busy = false;
    let uploading = false;

    async function refresh() {
        try {
            const response = await fetch("/api/usb", {cache: "no-store"});
            // 旧版服务没有这个接口，当成不支持
            overview = response.ok ? await response.json() : null;
        } catch (e) {
            overview = null;
        }
        render();
    }

    function render() {
        button.hidden = !UsbMedia.showButton(overview);
        if (button.hidden) {
            if (!panel.hidden) {
                setOpen(false);
            }
            return;
        }
        Object.keys(slots).forEach((name) => {
            const slot = slots[name];
            const state = overview.slots[name];
            const keep = slot.select.value;
            slot.select.replaceChildren(...UsbMedia.choices(overview.images, name)
                .map((image) => new Option(image, image)));
            if (keep) {
                slot.select.value = keep;
            }
            slot.state.textContent = UsbMedia.slotText(name, state);
            slot.mount.disabled = busy || !slot.select.value;
            slot.eject.disabled = busy || !state.image;
        });
        if (overview.images.length) {
            list.replaceChildren(...overview.images.map(imageRow));
        } else {
            list.textContent = "镜像目录是空的。点「上传」，或者把 .iso / .img 拷进服务端的 images/ 目录";
        }
        uploadButton.disabled = busy;
    }

    function imageRow(image) {
        const row = document.createElement("div");
        row.className = "media-image";
        const name = document.createElement("span");
        name.textContent = image.name;
        name.title = image.name;
        const size = document.createElement("span");
        size.className = "media-size";
        size.textContent = UsbMedia.formatSize(image.size);
        const remove = document.createElement("button");
        remove.type = "button";
        remove.textContent = "删除";
        remove.disabled = busy;
        remove.addEventListener("click", () => removeImage(image.name));
        row.append(name, size, remove);
        return row;
    }

    async function call(label, method, url, body, done) {
        busy = true;
        render();
        try {
            const response = await fetch(url, {
                method: method,
                headers: body ? {"Content-Type": "application/json"} : {},
                body: body ? JSON.stringify(body) : undefined
            });
            if (!response.ok) {
                throw new Error(await response.text());
            }
            overview = await response.json();
            toast(done, "ok");
        } catch (e) {
            toast(`${label}失败：${e.message || e}`, "error");
        } finally {
            busy = false;
            render();
        }
    }

    function mount(name) {
        const image = slots[name].select.value;
        const label = UsbMedia.SLOT_LABELS[name];
        const readOnly = name === "disk" && slots.disk.readOnly.checked;
        call(`挂${label}`, "POST", `/api/usb/${name}/mount`, {image, readOnly}, `已把 ${image} 挂到${label}`);
    }

    function eject(name) {
        const label = UsbMedia.SLOT_LABELS[name];
        call(`弹出${label}`, "POST", `/api/usb/${name}/eject`, null, `已弹出${label}`);
    }

    function removeImage(name) {
        if (!confirm(`删除镜像 ${name}？`)) {
            return;
        }
        call("删除镜像", "DELETE", `/api/usb/images/${encodeURIComponent(name)}`, null, `已删除 ${name}`);
    }

    // 用 XHR 而不是 fetch：fetch 拿不到上传进度，几个 GB 的 ISO 没有进度条会以为卡死了
    function upload(file) {
        if (!UsbMedia.acceptsUpload(file.name)) {
            toast("只支持 .iso 和 .img", "error");
            fileInput.value = "";
            return;
        }
        busy = true;
        uploading = true;
        progress.value = 0;
        progress.hidden = false;
        render();
        const xhr = new XMLHttpRequest();
        xhr.open("PUT", `/api/usb/images/${encodeURIComponent(file.name)}`);
        xhr.upload.onprogress = (event) => {
            if (event.lengthComputable) {
                progress.value = event.loaded / event.total;
            }
        };
        xhr.onload = () => {
            if (xhr.status >= 200 && xhr.status < 300) {
                overview = JSON.parse(xhr.responseText);
                toast(`已上传 ${file.name}`, "ok");
            } else {
                toast(`上传失败：${xhr.responseText || xhr.status}`, "error");
            }
            finishUpload();
        };
        xhr.onerror = () => {
            toast("上传失败：连接中断", "error");
            finishUpload();
        };
        xhr.send(file);
    }

    function finishUpload() {
        busy = false;
        uploading = false;
        progress.hidden = true;
        fileInput.value = "";
        render();
    }

    function setOpen(open) {
        panel.hidden = !open;
        button.classList.toggle("active", open);
        if (open) {
            KvmClosePanels(panel);
            refresh();
        }
    }

    Object.keys(slots).forEach((name) => {
        slots[name].mount.addEventListener("click", () => mount(name));
        slots[name].eject.addEventListener("click", () => eject(name));
        slots[name].select.addEventListener("change", render);
    });
    uploadButton.addEventListener("click", () => fileInput.click());
    fileInput.addEventListener("change", () => {
        if (fileInput.files.length) {
            upload(fileInput.files[0]);
        }
    });
    button.addEventListener("click", () => setOpen(panel.hidden));
    document.getElementById("media-close").addEventListener("click", () => setOpen(false));
    // 关页面会把上传掐断，服务端只会删掉那个 .part
    window.addEventListener("beforeunload", (event) => {
        if (uploading) {
            event.preventDefault();
        }
    });
    refresh();
})();
```

- [ ] **Step 4: `web/index.html`**

1. 样式：在 `.atx-actions {` 那一组前面加：

```css
        .media-slot {
            display: grid;
            grid-template-columns: 3em 1fr auto auto;
            gap: 6px;
            align-items: center;
        }

        .media-slot select {
            min-width: 0;
        }

        .media-state {
            grid-column: 2 / -1;
            color: var(--text-muted);
            font-size: 12px;
            overflow: hidden;
            text-overflow: ellipsis;
            white-space: nowrap;
        }

        .media-image {
            display: grid;
            grid-template-columns: 1fr auto auto;
            gap: 8px;
            align-items: center;
            font-size: 13px;
        }

        .media-image span:first-child {
            overflow: hidden;
            text-overflow: ellipsis;
            white-space: nowrap;
        }

        .media-size {
            color: var(--text-muted);
        }

        #media-panel button:not(#media-close) {
            padding: 4px 8px;
            border: 1px solid var(--tint-10);
            border-radius: 6px;
            background: var(--tint-04);
        }

        #media-panel button:not(#media-close):hover:not(:disabled) {
            background: var(--tint-12);
        }

        #media-panel button:disabled {
            cursor: default;
            opacity: 0.6;
        }

        #media-progress {
            width: 100%;
        }
```

关闭按钮那两组选择器（`#view-close, #atx-close, …` 和对应的 `:hover`）各加一项 `#media-close` / `#media-close:hover`。

2. 工具栏：在 `<button id="atx" …>` 前面加：

```html
    <button id="media" type="button" aria-label="虚拟介质" hidden>
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"
             stroke-linejoin="round" aria-hidden="true">
            <circle cx="12" cy="12" r="9"/>
            <circle cx="12" cy="12" r="2.5"/>
        </svg>
        <span class="tooltip">虚拟介质
本机 USB 口上的光驱和 U 盘
只在支持的 ARM 板上出现</span>
    </button>
```

3. 面板：在 `<div id="atx-panel" …>` 前面加：

```html
<div id="media-panel" class="tool-panel" data-toggle="media" hidden>
    <div class="tool-panel-header">
        <span>虚拟介质</span>
        <button id="media-close" type="button" aria-label="关闭">×</button>
    </div>
    <div class="media-slot">
        <span>光驱</span>
        <select id="media-cdrom-image" aria-label="光驱镜像"></select>
        <button id="media-cdrom-mount" type="button">挂载</button>
        <button id="media-cdrom-eject" type="button">弹出</button>
        <span class="media-state" id="media-cdrom-state"></span>
    </div>
    <div class="media-slot">
        <span>U 盘</span>
        <select id="media-disk-image" aria-label="U 盘镜像"></select>
        <button id="media-disk-mount" type="button">挂载</button>
        <button id="media-disk-eject" type="button">弹出</button>
        <span class="media-state" id="media-disk-state"></span>
    </div>
    <label class="shortcut-repeat-row"><span>U 盘只读</span><input id="media-disk-ro" type="checkbox"></label>
    <p class="shortcut-hint">镜像</p>
    <div id="media-images" class="profile-list"></div>
    <progress id="media-progress" max="1" value="0" hidden></progress>
    <button id="media-upload" type="button">上传镜像</button>
    <input id="media-file" type="file" accept=".iso,.img" hidden>
    <p class="shortcut-hint">换盘不会让键鼠掉线。挂着的镜像不能删、不能覆盖。</p>
</div>
```

4. 脚本：在 `<script src="atx.js"></script>` 前面加：

```html
<script src="usb-media.js"></script>
<script src="media-panel.js"></script>
```

- [ ] **Step 5: 设置页显示不可用原因**

`web/settings.html` 里，在 `<script src="settings-page.js">` 前面加 `<script src="usb-media.js"></script>`。

`web/settings-page.js` 里，`load()` 的 `loadCapabilities();` 后面加 `loadGadgetHint();`，并在 `load` 函数后面加：

```js
    async function loadGadgetHint() {
        const hint = el("gadget-hint");
        try {
            const response = await fetch("/api/usb", {cache: "no-store"});
            hint.textContent = response.ok ? UsbMedia.unavailableHint(await response.json()) : "";
        } catch (e) {
            hint.textContent = "";
        }
        hint.hidden = !hint.textContent;
    }
```

Run: `node --test tests/web/`
Expected: 全部 PASS。

- [ ] **Step 6: 在这台 x86 上冒烟**

```bash
cargo build --release
```

请用户装上新程序：`sudo <CARGO_TARGET_DIR>/release/rust-kvm install && sudo rust-kvm stop && sudo rust-kvm start`。然后检查：

Run: `curl -sk https://localhost:8443/api/usb`
Expected: `{"available":false,"reason":null,"slots":null,"images":[]}`。控制页工具栏没有「虚拟介质」按钮，设置页串口列表里没有「本机 USB 口直连」，`gadget-hint` 不显示，资源目录下没有新建 `images/`。

- [ ] **Step 7: 提交**

```bash
git add web/usb-media.js web/media-panel.js tests/web/usb-media.test.js web/index.html web/settings.html web/settings-page.js
git -c user.name=flydmonkey -c user.email=flydmonkey@gmail.com commit -m "控制页「虚拟介质」面板；设置页显示本机 USB 口不可用的原因"
```

---

### Task 7: README 和 RK 板真机验证

**Files:**
- Modify: `README.md`

- [ ] **Step 1: 写 README**

在 README 里讲 ATX 电源的那一节后面（找 `ATX` 标题）加一节：

```markdown
### 本机 USB 口直连：虚拟光驱、U 盘和键鼠（ARM 板）

宿主机是带 USB 设备口的 ARM 板（RK3566 / RK3588 一类、树莓派 4/5 的 USB-C 口）时，用一根
USB 线把板子的设备口接到被控机，被控机会看到一个光驱、一个 U 盘和一套键鼠：

- **光驱**挂 `.iso`，可以从它装系统、在 BIOS 里从它启动；
- **U 盘**挂 `.img` 或 `.iso`，可以选只读；
- **键鼠**可以代替 CH9329：设置页「键鼠」的串口选「本机 USB 口直连」。CH9329 芯片那一节
  这时会隐藏，键盘指示灯照常工作。两者可以同时接，用哪个看串口选的是哪个。

被控机上一直能看到一个空光驱和一个「无介质」的可移动盘，跟插着读卡器一样；挂盘、换盘、
弹盘都不会让键盘掉线。挂载状态不跨服务重启保留。

**前提**：

- 内核开了 `CONFIG_USB_CONFIGFS`，`libcomposite` 能加载；
- `/sys/class/udc` 下有控制器。RK 板的 OTG 口在很多镜像里默认是主机模式，要在设备树里把
  那个 `usb@…` 节点的 `dr_mode` 改成 `"peripheral"` 或 `"otg"`，重启后才会出现；
- 用新版程序重新 `sudo rust-kvm install`。ARM 上的单元文件会多一行
  `ExecStartPre=-+… usb-gadget-setup <用户>`：每次启动前以 root 建 gadget、绑定控制器、把
  挂盘和 `/dev/hidg*` 交给服务用户；建不起来也不挡服务启动，原因显示在设置页「键鼠」一节。

**镜像**放在资源目录下的 `images/`（服务第一次在可用的板子上启动时建），控制页「虚拟介质」
面板里可以上传和删除，也可以直接 scp 进去。想放到别的盘上，把 `images` 做成软链接。
```

- [ ] **Step 2: 全量检查**

Run: `cargo test --bin rust-kvm && cargo clippy --bins 2>&1 | rg "^(warning|error)"; node --test tests/web/ 2>&1 | rg "^ℹ (pass|fail)"`
Expected: Rust 测试全部 PASS；clippy 只剩原来那条 `result_large_err`；网页 `fail 0`。

- [ ] **Step 3: 提交**

```bash
git add README.md
git -c user.name=flydmonkey -c user.email=flydmonkey@gmail.com commit -m "README：本机 USB 口直连（虚拟光驱、U 盘、键鼠）"
```

- [ ] **Step 4: RK 板真机验证（用户在板子上做，逐条回报结果）**

在 RK 板上 `git pull`、`cargo build --release`、`sudo target/release/rust-kvm install && sudo rust-kvm stop && sudo rust-kvm start`，然后：

1. `journalctl -u rust-kvm -b | rg -i "gadget|USB 口"`：应看到「gadget 已绑定到 …」和「本机 USB 口可用」。不可用时，把原因贴回来。
2. 被控机（Windows）设备管理器里出现「Virtual KVM」的键盘、两个鼠标、一个光驱和一个可移动磁盘。
3. 设置页串口选「本机 USB 口直连」并保存，控制页连接键鼠：打字、绝对鼠标、相对鼠标（Pointer Lock）都正常；按 CapsLock 后控制页的指示灯跟着变。
4. 上传一个 ISO 挂到光驱：被控机能打开；换一个 ISO：键盘没掉线；弹出：光驱变空。
5. 挂一个 `.img` 到 U 盘（不勾只读）：被控机能读写；勾只读再挂：写入被拒。
6. 重启被控机进 BIOS：键盘能用；启动菜单里能选光驱，从 ISO 启动。
7. 拔掉 USB 线：控制页状态显示「USB 未枚举」；插回后恢复。
8. `sudo rust-kvm stop && sudo rust-kvm start` 连续两次：第二次 setup 能拆掉上次的 gadget 重建（验证 `teardown`），被控机重新识别。
```
