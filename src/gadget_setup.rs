//! `rust-kvm usb-gadget-setup <服务用户>`：服务启动前以 root 建好 gadget。
//!
//! systemd 单元里写成 `ExecStartPre=-+…`：`+` 让这一步以 root 跑，`-` 让它失败了也不挡
//! 服务启动——gadget 用不了时，画面和 CH9329 键鼠照样要能用。结构见设计文档
//! `docs/superpowers/specs/2026-11-26-usb-gadget-design.md`.

use std::collections::BTreeMap;
use std::ffi::CString;
use std::fs::Permissions;
use std::os::unix::fs::{DirBuilderExt, PermissionsExt};
use std::path::Path;
use std::process::Command;
use std::time::{Duration, Instant};

use anyhow::{Context, Result, bail};

use crate::usb_gadget::{
    ABS_MOUSE_FUNCTION, CONFIG_DIR, GADGET_NAME, GadgetPaths, HID_FUNCTIONS, KEYBOARD_FUNCTION,
    REL_MOUSE_FUNCTION, STORAGE_FUNCTION, Slot,
};

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
    let paths = GadgetPaths::system();
    let result = setup(user, &paths);
    record_outcome(&paths, &result);
    result
}

fn setup(user: Option<&str>, paths: &GadgetPaths) -> Result<()> {
    let user = user.context("用法：rust-kvm usb-gadget-setup <服务用户>")?;
    let arch = std::env::consts::ARCH;
    if !matches!(arch, "aarch64" | "arm") {
        println!("{arch} 上不建 USB gadget，跳过");
        return Ok(());
    }
    // 编进内核的话 modprobe 会失败，那不要紧，下面看 configfs 目录在不在
    let _ = Command::new("modprobe").arg("libcomposite").status();
    if !paths.configfs.is_dir() {
        println!("没有 {}，跳过", paths.configfs.display());
        return Ok(());
    }
    let Some(udc) = pick_udc(paths)? else {
        println!("没有 USB 设备控制器，跳过");
        return Ok(());
    };
    let owner = lookup_user(user)?;
    if paths.gadget().exists() {
        teardown(paths).context("拆掉上次的 gadget 失败")?;
    }
    build(paths, &serial_number())?;
    std::fs::write(paths.gadget().join("UDC"), &udc).with_context(|| format!("绑定 {udc} 失败"))?;
    hand_over(paths, owner, Duration::from_secs(3))?;
    println!("gadget 已绑定到 {udc}，交给用户 {user}");
    Ok(())
}

/// `ExecStartPre` 的 stdout 只进 journal，设置页看不到。把失败原因落在文件里，服务启动后读。
/// 成功就删掉，免得一次早已修好的失败一直挂在设置页上。
fn record_outcome(paths: &GadgetPaths, result: &Result<()>) {
    let file = &paths.setup_error;
    match result {
        Ok(()) => {
            let _ = std::fs::remove_file(file);
        }
        Err(error) => {
            if let Err(write_error) = write_setup_error(file, &format!("{error:#}\n")) {
                eprintln!("记录 setup 失败原因到 {} 失败：{write_error}", file.display());
            }
        }
    }
}

fn write_setup_error(file: &Path, text: &str) -> std::io::Result<()> {
    if let Some(dir) = file.parent() {
        std::fs::DirBuilder::new().recursive(true).mode(0o755).create(dir)?;
        std::fs::set_permissions(dir, Permissions::from_mode(0o755))?;
    }
    std::fs::write(file, text)?;
    std::fs::set_permissions(file, Permissions::from_mode(0o644))
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
///
/// 尽力而为：每个节点、每个属性都试，失败的汇总成一个错误。LUN 属性按内核有什么交什么
/// （老内核没有 `forced_eject`），任何一项失败都不该连累键鼠节点。
pub fn hand_over(paths: &GadgetPaths, owner: (u32, u32), timeout: Duration) -> Result<()> {
    let mut failures = Vec::new();
    let deadline = Instant::now() + timeout;
    for function in HID_FUNCTIONS {
        if let Err(error) = hand_over_node(paths, function, owner, deadline, timeout) {
            failures.push(format!("{error:#}"));
        }
    }
    for slot in Slot::ALL {
        for attr in ["file", "ro", "forced_eject"] {
            let path = paths.lun(slot).join(attr);
            if !path.exists() {
                continue;
            }
            if let Err(error) = chown(&path, owner) {
                failures.push(format!("{error:#}"));
            }
        }
    }
    if failures.is_empty() {
        Ok(())
    } else {
        bail!("交给服务用户时有 {} 处失败：{}", failures.len(), failures.join("；"))
    }
}

fn hand_over_node(
    paths: &GadgetPaths,
    function: &str,
    owner: (u32, u32),
    deadline: Instant,
    timeout: Duration,
) -> Result<()> {
    let node = paths.hid_node(function)?;
    while !node.exists() {
        if Instant::now() >= deadline {
            bail!("等了 {timeout:?} 还没有 {}（udev 没建出来？）", node.display());
        }
        std::thread::sleep(Duration::from_millis(50));
    }
    chown(&node, owner)
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

/// 挑一个没被别的 gadget 占着的控制器。板子上要是已经有别的 gadget（比如厂商的 ADB）绑着
/// 唯一的控制器，抢过来会把它拆掉。自己上次建的不算占用：下面会先拆掉重建。
fn pick_udc(paths: &GadgetPaths) -> Result<Option<String>> {
    let Ok(entries) = std::fs::read_dir(&paths.udc_class) else {
        return Ok(None);
    };
    let mut controllers: Vec<String> =
        entries.flatten().map(|entry| entry.file_name().to_string_lossy().into_owned()).collect();
    controllers.sort();
    if controllers.is_empty() {
        return Ok(None);
    }
    let occupied = occupied_udcs(paths);
    if let Some(free) = controllers.iter().find(|name| !occupied.contains_key(*name)) {
        return Ok(Some(free.clone()));
    }
    let detail: Vec<String> = controllers
        .iter()
        .map(|name| format!("{name} 被 gadget「{}」占用", occupied[name]))
        .collect();
    bail!("所有 USB 设备控制器都被别的 gadget 占着：{}", detail.join("，"))
}

/// 控制器名 → 占着它的 gadget 目录名。
fn occupied_udcs(paths: &GadgetPaths) -> BTreeMap<String, String> {
    let mut occupied = BTreeMap::new();
    let Ok(entries) = std::fs::read_dir(&paths.configfs) else {
        return occupied;
    };
    for entry in entries.flatten() {
        let gadget = entry.file_name().to_string_lossy().into_owned();
        if gadget == GADGET_NAME {
            continue;
        }
        if let Ok(udc) = std::fs::read_to_string(entry.path().join("UDC")) {
            let udc = udc.trim();
            if !udc.is_empty() {
                occupied.insert(udc.to_string(), gadget);
            }
        }
    }
    occupied
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
            setup_error: temp.path().join("run/gadget-setup.err"),
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

    fn me() -> (u32, u32) {
        unsafe { (libc::geteuid(), libc::getegid()) }
    }

    fn not_root() -> bool {
        unsafe { libc::geteuid() != 0 }
    }

    #[test]
    fn 交出去的文件包括三个_hidg_节点() {
        let fixture = fixture();
        hand_over(&fixture.paths, me(), Duration::from_millis(100)).unwrap();
    }

    #[test]
    fn 老内核没有_forced_eject_照样交出去() {
        let fixture = fixture();
        for slot in Slot::ALL {
            std::fs::remove_file(fixture.paths.lun(slot).join("forced_eject")).unwrap();
        }
        hand_over(&fixture.paths, me(), Duration::from_millis(100)).unwrap();
        assert!(!fixture.paths.lun(Slot::Disk).join("forced_eject").exists());
    }

    #[test]
    fn hidg_节点迟迟不出现就报错() {
        let fixture = fixture();
        std::fs::remove_file(fixture.paths.dev.join("hidg2")).unwrap();
        let error = hand_over(&fixture.paths, me(), Duration::from_millis(100)).unwrap_err();
        assert!(format!("{error:#}").contains("hidg2"), "{error:#}");
    }

    #[test]
    fn 一个节点失败不挡其他的_失败汇总成一个错误() {
        let fixture = fixture();
        std::fs::remove_file(fixture.paths.dev.join("hidg2")).unwrap();
        std::fs::remove_file(fixture.paths.dev.join("hidg0")).unwrap();
        let error = format!("{:#}", hand_over(&fixture.paths, me(), Duration::from_millis(100)).unwrap_err());
        assert!(error.contains("hidg2") && error.contains("hidg0"), "{error}");
        assert!(error.contains("2 处"), "{error}");
    }

    #[test]
    fn chown_失败后还继续试后面的() {
        if !not_root() {
            return;
        }
        let fixture = fixture();
        // 非 root 不能把文件交给别人：3 个节点 + 2 个 LUN 的 3 个属性 = 9 处全部失败，
        // 每一处都得试到
        let other = (me().0 + 1, me().1);
        let error = format!("{:#}", hand_over(&fixture.paths, other, Duration::from_millis(100)).unwrap_err());
        assert!(error.contains("9 处"), "{error}");
        assert!(error.contains("hidg1") && error.contains("lun.1"), "{error}");
    }

    fn set_udc(paths: &GadgetPaths, gadget: &str, udc: &str) {
        let dir = paths.configfs.join(gadget);
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("UDC"), format!("{udc}\n")).unwrap();
    }

    fn add_controller(paths: &GadgetPaths, name: &str) {
        std::fs::create_dir_all(paths.udc_class.join(name)).unwrap();
    }

    #[test]
    fn 选控制器时跳过别的_gadget_占着的() {
        let fixture = fixture();
        add_controller(&fixture.paths, "ff400000.usb");
        set_udc(&fixture.paths, "g1", "fe800000.usb");
        assert_eq!(pick_udc(&fixture.paths).unwrap().as_deref(), Some("ff400000.usb"));
    }

    #[test]
    fn 自己上次占着的控制器不算被占用() {
        let fixture = fixture();
        assert_eq!(pick_udc(&fixture.paths).unwrap().as_deref(), Some("fe800000.usb"));
    }

    #[test]
    fn 控制器全被别的_gadget_占着时点名() {
        let fixture = fixture();
        set_udc(&fixture.paths, "g_adb", "fe800000.usb");
        let error = format!("{:#}", pick_udc(&fixture.paths).unwrap_err());
        assert!(error.contains("g_adb") && error.contains("fe800000.usb"), "{error}");
    }

    #[test]
    fn 没有控制器时是_none() {
        let fixture = fixture();
        std::fs::remove_dir_all(fixture.paths.udc_class.join("fe800000.usb")).unwrap();
        assert_eq!(pick_udc(&fixture.paths).unwrap(), None);
    }

    #[test]
    fn 失败时把错误链写进文件_成功时删掉() {
        let temp = tempfile::tempdir().unwrap();
        let file = temp.path().join("run/rust-kvm/gadget-setup.err");
        let mut paths = fixture().paths;
        paths.setup_error = file.clone();

        let failed: Result<()> = Err(anyhow::anyhow!("底层原因").context("绑定失败"));
        record_outcome(&paths, &failed);
        let text = std::fs::read_to_string(&file).unwrap();
        assert!(text.contains("绑定失败") && text.contains("底层原因"), "{text}");
        let mode = |path: &Path| std::fs::metadata(path).unwrap().permissions().mode() & 0o777;
        assert_eq!(mode(&file), 0o644);
        assert_eq!(mode(file.parent().unwrap()), 0o755);

        record_outcome(&paths, &Ok(()));
        assert!(!file.exists());
        record_outcome(&paths, &Ok(()));
    }
}
