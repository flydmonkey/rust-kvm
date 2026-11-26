//! 本机 USB 口（configfs gadget）的运行时部分：判断能不能用、写 HID 报告、读键盘灯、
//! 挂盘弹盘。建 gadget 要 root，在 `gadget_setup.rs` 里，由 systemd 在服务启动前跑。
//!
//! configfs、udc、`/dev` 三个根目录都从 `GadgetPaths` 进来，测试时换成临时目录。

use std::fs::{File, OpenOptions};
use std::io::{self, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU8, Ordering};
use std::sync::{Arc, Mutex};
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
    /// setup 失败时把原因写在这里，服务启动后据此告诉用户为什么用不了
    pub setup_error: PathBuf,
}

impl GadgetPaths {
    pub fn system() -> Self {
        Self {
            configfs: PathBuf::from("/sys/kernel/config/usb_gadget"),
            udc_class: PathBuf::from("/sys/class/udc"),
            dev: PathBuf::from("/dev"),
            setup_error: PathBuf::from("/run/rust-kvm/gadget-setup.err"),
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
        Err(error) => Availability::Unavailable(match setup_failure(paths) {
            Some(failure) => format!("上次 setup 失败：{failure}"),
            None => format!("{error:#}"),
        }),
    }
}

/// setup 成功时会删掉这个文件，所以它在就说明最近一次 setup 是失败的。
fn setup_failure(paths: &GadgetPaths) -> Option<String> {
    let text = std::fs::read_to_string(&paths.setup_error).ok()?;
    let text = text.trim();
    (!text.is_empty()).then(|| text.to_string())
}

pub struct UsbGadget {
    paths: GadgetPaths,
    udc: String,
    keyboard: File,
    abs_mouse: File,
    rel_mouse: File,
    leds: AtomicU8,
    /// 5.19 以前的内核没有 `forced_eject`
    forced_eject: bool,
    /// 串行化对 LUN 属性的多步写：并发的挂盘和弹盘交错起来会写出半挂半弹的状态
    lun_lock: Mutex<()>,
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
        let forced_eject = Slot::ALL.iter().all(|slot| {
            OpenOptions::new().write(true).open(paths.lun(*slot).join("forced_eject")).is_ok()
        });
        if !forced_eject {
            debug!("内核没有 forced_eject（或写不了），弹盘直接写 file");
        }
        Ok(Self {
            keyboard: open_hid(&paths, KEYBOARD_FUNCTION)?,
            abs_mouse: open_hid(&paths, ABS_MOUSE_FUNCTION)?,
            rel_mouse: open_hid(&paths, REL_MOUSE_FUNCTION)?,
            leds: AtomicU8::new(0),
            forced_eject,
            lun_lock: Mutex::new(()),
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
                // 超时是常态，直接进下一轮；只有读到 0 字节（对端关了）才歇一下，免得空转
                Ok(None) => {}
                Ok(Some(0)) => std::thread::sleep(Duration::from_millis(100)),
                Ok(Some(_)) => self.leds.store(buf[0], Ordering::Relaxed),
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

    /// 先弹出：被控机锁着介质（Windows 打开过光驱就会锁）时直接写 `file` 会 EBUSY；
    /// `ro` 也只能在空槽时改。光驱的只读在 setup 时就定了，这里不碰。
    pub fn mount(&self, slot: Slot, image: &Path, read_only: bool) -> Result<()> {
        let _guard = self.lun_lock.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
        let lun = self.paths.lun(slot);
        self.close_medium(&lun)?;
        if slot == Slot::Disk {
            write_attr(&lun.join("ro"), if read_only { "1" } else { "0" })?;
        }
        write_file_attr(&lun.join("file"), &image.to_string_lossy())
    }

    pub fn eject(&self, slot: Slot) -> Result<()> {
        let _guard = self.lun_lock.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
        self.close_medium(&self.paths.lun(slot))
    }

    /// 有 `forced_eject` 就用它（连被控机锁住的也能弹）；没有就写 `file`。内核的
    /// `fsg_store_file` 会去掉结尾的一个换行，空串即弹出——写字节数为 0 的
    /// 空串根本不会发 write 系统调用，所以要写 `"\n"`。
    fn close_medium(&self, lun: &Path) -> Result<()> {
        if self.forced_eject {
            write_attr(&lun.join("forced_eject"), "1")?;
        }
        write_file_attr(&lun.join("file"), "\n")
    }
}

fn open_hid(paths: &GadgetPaths, function: &str) -> Result<File> {
    let node = paths.hid_node(function)?;
    sys::open_nonblocking(&node)
        .with_context(|| format!("打不开 {}（setup 没把它交给服务用户？）。{REINSTALL}", node.display()))
}

/// configfs 的属性文件一定已经存在，不带 create：写错路径时要报错，不能悄悄建个新文件。
fn write_raw(path: &Path, value: &str) -> io::Result<()> {
    OpenOptions::new().write(true).truncate(true).open(path)?.write_all(value.as_bytes())
}

fn write_attr(path: &Path, value: &str) -> Result<()> {
    write_raw(path, value).with_context(|| format!("写 {} 失败", path.display()))
}

fn write_file_attr(path: &Path, value: &str) -> Result<()> {
    write_raw(path, value).map_err(|error| file_write_error(path, error))
}

/// 主机禁止了介质移除时，内核对 `file` 的写返回 EBUSY。
fn file_write_error(path: &Path, error: io::Error) -> anyhow::Error {
    if error.kind() == io::ErrorKind::ResourceBusy {
        anyhow::anyhow!("被控机锁住了介质，先在被控机上弹出")
    } else {
        anyhow::Error::new(error).context(format!("写 {} 失败", path.display()))
    }
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

    /// `None` 是等到超时也没有数据；`Some(0)` 才是真的读到了 0 字节。
    pub fn read_report(mut file: &File, buf: &mut [u8], timeout: Duration) -> io::Result<Option<usize>> {
        if !wait(file, libc::POLLIN, timeout)? {
            return Ok(None);
        }
        match file.read(buf) {
            Err(error) if error.kind() == io::ErrorKind::WouldBlock => Ok(None),
            other => other.map(Some),
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

    pub fn read_report(_file: &File, _buf: &mut [u8], _timeout: Duration) -> io::Result<Option<usize>> {
        Err(unsupported())
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
            setup_error: temp.path().join("run/gadget-setup.err"),
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
        let (fixture, gadget) = test_support::gadget();
        gadget.mount(Slot::Disk, Path::new("/images/a.img"), false).unwrap();
        gadget.eject(Slot::Disk).unwrap();
        assert_eq!(gadget.slot(Slot::Disk).image, None);
        // 空串不会发 write，内核收不到弹出；必须是一个换行
        let raw = std::fs::read_to_string(fixture.paths.lun(Slot::Disk).join("file")).unwrap();
        assert_eq!(raw, "\n");
    }

    fn without_forced_eject() -> (test_support::Fixture, Arc<UsbGadget>) {
        let fixture = fixture();
        for slot in Slot::ALL {
            std::fs::remove_file(fixture.paths.lun(slot).join("forced_eject")).unwrap();
        }
        let gadget = Arc::new(UsbGadget::open(fixture.paths.clone()).unwrap());
        (fixture, gadget)
    }

    #[test]
    fn 没有_forced_eject_的内核照样能打开和挂盘弹盘() {
        let (fixture, gadget) = without_forced_eject();
        assert!(!gadget.forced_eject);
        gadget.write_report(HidTarget::Keyboard, &[0; 8]).unwrap();

        gadget.mount(Slot::Disk, Path::new("/images/a.img"), true).unwrap();
        let lun = fixture.paths.lun(Slot::Disk);
        assert!(!lun.join("forced_eject").exists());
        assert_eq!(std::fs::read_to_string(lun.join("ro")).unwrap(), "1");
        assert_eq!(gadget.slot(Slot::Disk).image, Some(PathBuf::from("/images/a.img")));

        gadget.eject(Slot::Disk).unwrap();
        assert_eq!(std::fs::read_to_string(lun.join("file")).unwrap(), "\n");
        assert_eq!(gadget.slot(Slot::Disk).image, None);
    }

    #[test]
    fn 有_forced_eject_时记下来() {
        let (_fixture, gadget) = test_support::gadget();
        assert!(gadget.forced_eject);
    }

    #[test]
    fn 写_file_得到_ebusy_时提示在被控机上弹出() {
        let busy = io::Error::from(io::ErrorKind::ResourceBusy);
        let message = format!("{:#}", file_write_error(Path::new("/x/file"), busy));
        assert!(message.contains("被控机锁住了介质"), "{message}");
        let other = io::Error::from(io::ErrorKind::PermissionDenied);
        let message = format!("{:#}", file_write_error(Path::new("/x/file"), other));
        assert!(message.contains("/x/file"), "{message}");
    }

    #[test]
    fn 挂盘持有锁时别的写要等() {
        let (fixture, gadget) = test_support::gadget();
        let guard = gadget.lun_lock.lock().unwrap();
        let worker = {
            let gadget = gadget.clone();
            std::thread::spawn(move || gadget.mount(Slot::Cdrom, Path::new("/images/a.iso"), false))
        };
        std::thread::sleep(Duration::from_millis(200));
        let file = fixture.paths.lun(Slot::Cdrom).join("file");
        assert_eq!(std::fs::read_to_string(&file).unwrap(), "");
        drop(guard);
        worker.join().unwrap().unwrap();
        assert_eq!(gadget.slot(Slot::Cdrom).image, Some(PathBuf::from("/images/a.iso")));
    }

    #[test]
    fn setup_失败过时原因用记下的内容() {
        let fixture = fixture();
        std::fs::remove_dir_all(fixture.paths.gadget()).unwrap();
        std::fs::create_dir_all(fixture.paths.setup_error.parent().unwrap()).unwrap();
        std::fs::write(&fixture.paths.setup_error, "绑定 fe800000.usb 失败: busy\n").unwrap();
        let reason = reason(detect(&fixture.paths, "aarch64")).unwrap();
        assert_eq!(reason, "上次 setup 失败：绑定 fe800000.usb 失败: busy");
    }

    #[test]
    fn 记下的失败原因是空的就退回通用提示() {
        let fixture = fixture();
        std::fs::remove_dir_all(fixture.paths.gadget()).unwrap();
        std::fs::create_dir_all(fixture.paths.setup_error.parent().unwrap()).unwrap();
        std::fs::write(&fixture.paths.setup_error, "\n").unwrap();
        let reason = reason(detect(&fixture.paths, "aarch64")).unwrap();
        assert!(reason.contains("rust-kvm install"), "{reason}");
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn 没数据超时是_none_读到零字节才是_some_0() {
        use std::os::fd::{FromRawFd, OwnedFd};

        let mut fds = [0 as libc::c_int; 2];
        assert_eq!(unsafe { libc::pipe(fds.as_mut_ptr()) }, 0);
        let (reader, writer) =
            unsafe { (File::from(OwnedFd::from_raw_fd(fds[0])), File::from(OwnedFd::from_raw_fd(fds[1]))) };
        let mut buf = [0u8; 8];
        let timeout = Duration::from_millis(10);
        assert_eq!(sys::read_report(&reader, &mut buf, timeout).unwrap(), None);
        (&writer).write_all(&[0x02]).unwrap();
        assert_eq!(sys::read_report(&reader, &mut buf, timeout).unwrap(), Some(1));
        assert_eq!(buf[0], 0x02);
        drop(writer);
        assert_eq!(sys::read_report(&reader, &mut buf, timeout).unwrap(), Some(0));
    }
}
