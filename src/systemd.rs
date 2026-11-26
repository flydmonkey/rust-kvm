//! 装成 systemd 服务，是 Windows 那边 `service.rs` 在 Linux 上的对应物。
//!
//! 和 Windows 不同，这里装成服务**只是**为了开机自启：Linux 没有会话隔离，前台跑和
//! 服务里跑看到的是同一批设备。真正要操心的是两件权限上的事：
//!
//! - **设备节点。** `/dev/video*`、`/dev/snd/*`、`/dev/ttyUSB*` 分别属于 `video`、
//!   `audio`、`dialout` 组。桌面登录时 logind 会给当前用户临时挂一条 ACL，所以前台跑
//!   一切正常；服务不在任何登录会话里，那条 ACL 不作数，只能靠组。症状是「前台好好的，
//!   装成服务就打不开采集卡」，而权限错误被 nokhwa 包了一层之后很难认出来。
//! - **实时优先级。** 采音线程要 `SCHED_FIFO`（见 `audio::promote_audio_thread`），
//!   普通用户的 `RLIMIT_RTPRIO` 默认是 0。
//!
//! 服务以装它的那个用户的身份跑（`sudo` 时取 `SUDO_USER`），不用 root：资源目录里的
//! `certs/`、`config.json` 是这个用户的，让 root 写进去之后再前台跑就改不动了。

use std::ffi::CString;
use std::net::{Ipv4Addr, SocketAddr, TcpStream};
use std::path::Path;
use std::process::Command;
use std::time::{Duration, Instant};

use anyhow::{Context, Result, bail};

const SERVICE_NAME: &str = "rust-kvm";
const UNIT_PATH: &str = "/etc/systemd/system/rust-kvm.service";

/// 服务跑的是这份拷贝，不是装它的那个 exe。`target/` 会被 `cargo clean` 清掉，
/// `CARGO_TARGET_DIR` 还可能指在 `/tmp` 下，重启就没了——单元文件指过去，服务
/// 开机就起不来。拷到这里之后 `sudo rust-kvm start` 也直接在 PATH 里。
///
/// 这里往上找不到 `web/`，`asset_root` 会退回工作目录，单元文件的
/// `WorkingDirectory` 正好指着资源目录，所以资源照样找得到。
const INSTALLED_EXE: &str = "/usr/local/bin/rust-kvm";

/// 设备节点归属的组。不是每个发行版都有全这几个（比如没有 `dialout`），而
/// `SupplementaryGroups` 里写了不存在的组，服务会直接以 216/GROUP 起不来，
/// 所以装的时候逐个查过再写。
const DEVICE_GROUPS: [&str; 3] = ["video", "audio", "dialout"];

/// 等服务真能访问的上限。`systemctl start` 返回只说明进程拉起来了，不等于端口在听。
const READY_TIMEOUT: Duration = Duration::from_secs(15);

pub fn install() -> Result<()> {
    require_root("install")?;
    let exe = std::env::current_exe()
        .and_then(|path| path.canonicalize())
        .context("取不到自身路径")?;
    let root = crate::asset_root()
        .canonicalize()
        .context("取不到资源目录")?;
    if !root.join("web").is_dir() {
        bail!(
            "{} 下没有 web/，找不到资源目录。到项目目录里再跑一次 install",
            root.display()
        );
    }
    let installed = Path::new(INSTALLED_EXE);
    if exe != installed {
        copy_exe(&exe, installed)?;
    }
    let user = service_user();
    let groups: Vec<&str> = DEVICE_GROUPS
        .into_iter()
        .filter(|group| group_exists(group))
        .collect();

    // 只在 ARM 上加：x86 的板子没有 USB 设备控制器，多一步只会在日志里多一行「跳过」
    let gadget = cfg!(any(target_arch = "aarch64", target_arch = "arm"));
    std::fs::write(UNIT_PATH, unit_file(installed, &root, &user, &groups, gadget))
        .with_context(|| format!("写 {UNIT_PATH} 失败"))?;
    systemctl(&["daemon-reload"])?;
    systemctl(&["enable", SERVICE_NAME])?;

    println!("已注册服务 {SERVICE_NAME}（开机自启）");
    println!("  程序：{INSTALLED_EXE}（从 {} 拷过去）", exe.display());
    println!("  用户：{user}，附加组：{}", groups.join(" "));
    println!("  资源目录：{}", root.display());
    if gadget {
        println!("  本机 USB 口：每次启动前以 root 建 gadget（虚拟光驱、U 盘、键鼠）");
    }
    println!();
    println!("接下来：sudo rust-kvm start（服务已经在跑的话 stop 再 start 才换成新程序）");
    Ok(())
}

/// 先写到旁边再改名。服务正在跑时直接覆盖会报 ETXTBSY（文本文件忙）；改名只换
/// 目录项，跑着的进程继续用旧的 inode，下次启动就是新的。
fn copy_exe(from: &Path, to: &Path) -> Result<()> {
    use std::os::unix::fs::PermissionsExt;

    let staging = to.with_extension("new");
    std::fs::copy(from, &staging)
        .with_context(|| format!("拷 {} 到 {} 失败", from.display(), staging.display()))?;
    std::fs::set_permissions(&staging, std::fs::Permissions::from_mode(0o755))
        .with_context(|| format!("改 {} 权限失败", staging.display()))?;
    std::fs::rename(&staging, to)
        .with_context(|| format!("换上 {} 失败", to.display()))?;
    Ok(())
}

pub fn uninstall() -> Result<()> {
    require_root("uninstall")?;
    if !Path::new(UNIT_PATH).exists() {
        println!("服务没有安装");
        return Ok(());
    }
    // --now 顺带停掉：不停的话进程还占着采集卡和端口，而单元文件已经没了，
    // 之后连 systemctl stop 都找不到它
    systemctl(&["disable", "--now", SERVICE_NAME])?;
    std::fs::remove_file(UNIT_PATH).with_context(|| format!("删 {UNIT_PATH} 失败"))?;
    systemctl(&["daemon-reload"])?;
    match std::fs::remove_file(INSTALLED_EXE) {
        Ok(()) => {}
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
        Err(e) => return Err(e).with_context(|| format!("删 {INSTALLED_EXE} 失败")),
    }
    println!("已卸载服务 {SERVICE_NAME}");
    Ok(())
}

pub fn start() -> Result<()> {
    require_installed()?;
    systemctl(&["start", SERVICE_NAME])?;
    wait_until_listening()?;
    println!("服务已启动。日志：journalctl -u {SERVICE_NAME} -f");
    Ok(())
}

pub fn stop() -> Result<()> {
    require_installed()?;
    // systemctl stop 会等到进程真的退出（或者 TimeoutStopSec 到了强杀）才返回
    systemctl(&["stop", SERVICE_NAME])?;
    println!("服务已停止");
    Ok(())
}

pub fn status() -> Result<()> {
    if !Path::new(UNIT_PATH).exists() {
        println!("服务没有安装。跑 sudo rust-kvm install 装上");
        return Ok(());
    }
    // 服务没在跑时 systemctl status 的退出码是 3，那不是错误，所以不看退出码
    let _ = Command::new("systemctl")
        .args(["status", SERVICE_NAME, "--no-pager"])
        .status();
    println!();
    println!("日志：journalctl -u {SERVICE_NAME} -f");
    Ok(())
}

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

fn require_root(command: &str) -> Result<()> {
    if unsafe { libc::geteuid() } != 0 {
        bail!("{command} 要写 /etc/systemd/system，得用 root：sudo rust-kvm {command}");
    }
    Ok(())
}

fn require_installed() -> Result<()> {
    if !Path::new(UNIT_PATH).exists() {
        bail!("服务没有安装，先跑一次 sudo rust-kvm install");
    }
    Ok(())
}

/// `sudo rust-kvm install` 的时候装给敲命令的那个人，直接以 root 登录装的才用 root。
fn service_user() -> String {
    std::env::var("SUDO_USER")
        .ok()
        .filter(|user| !user.is_empty() && user != "root")
        .unwrap_or_else(|| "root".to_string())
}

fn group_exists(name: &str) -> bool {
    let Ok(name) = CString::new(name) else {
        return false;
    };
    !unsafe { libc::getgrnam(name.as_ptr()) }.is_null()
}

fn systemctl(args: &[&str]) -> Result<()> {
    let status = Command::new("systemctl")
        .args(args)
        .status()
        .context("跑不起来 systemctl（这台机器不是 systemd？）")?;
    if !status.success() {
        bail!("systemctl {} 失败（{status}）", args.join(" "));
    }
    Ok(())
}

/// 探明文的 8080：它只绑回环、和 8443 是同一个 app，连得上就说明服务真的起来了。
fn wait_until_listening() -> Result<()> {
    let address = SocketAddr::from((Ipv4Addr::LOCALHOST, crate::HTTP_PORT));
    let deadline = Instant::now() + READY_TIMEOUT;
    while Instant::now() < deadline {
        if TcpStream::connect_timeout(&address, Duration::from_millis(500)).is_ok() {
            return Ok(());
        }
        std::thread::sleep(Duration::from_millis(300));
    }
    bail!(
        "服务起来了，但 {} 秒内 {address} 都没应答。看日志：journalctl -u {SERVICE_NAME} -e",
        READY_TIMEOUT.as_secs()
    )
}

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
