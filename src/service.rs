//! 注册成 Windows 服务。
//!
//! 除了开机自启，这一步还顺带解决一个绕不过去的问题：**远程桌面会话里枚举不到
//! 物理音频端点**。Windows 会把它们换成一个「远程音频」端点，采集卡的音频口在
//! 那个会话里根本不存在，cpal 报零个输入设备。服务跑在 session 0（LocalSystem），
//! 那里看得到全部物理设备——这一点已经用计划任务以 SYSTEM 跑 probe-audio 验证过。
//!
//! 所以 `account_name: None`（即 LocalSystem）不是随手选的默认值，是必须的。

use std::ffi::OsString;
use std::time::{Duration, Instant};

use anyhow::{Context, Result, bail};
use windows_service::service::{
    ServiceAccess, ServiceControl, ServiceControlAccept, ServiceErrorControl, ServiceExitCode,
    ServiceInfo, ServiceStartType, ServiceState, ServiceStatus, ServiceType,
};
use windows_service::service_control_handler::{self, ServiceControlHandlerResult};
use windows_service::service_manager::{ServiceManager, ServiceManagerAccess};
use windows_service::{define_windows_service, service_dispatcher};

pub const SERVICE_NAME: &str = "rust-kvm";
const DISPLAY_NAME: &str = "rust-kvm 远程控制";
const DESCRIPTION: &str = "把本机的采集卡画面/声音和 CH9329 键鼠透过 HTTPS 提供给局域网浏览器。";
const SERVICE_TYPE: ServiceType = ServiceType::OWN_PROCESS;

/// SCM 拉起进程时附加的参数，`main` 靠它认出「这次是被服务管理器启动的」。
pub const RUN_ARG: &str = "run-service";

/// 停止时留给在飞连接的收尾时间。主要是让串口线程把「全释放」包发出去——
/// 服务停的时候，被控机上不该留着按下不放的键。
const STOP_GRACE: Duration = Duration::from_secs(3);

/// 等服务状态变化的上限。SCM 的启停都是异步的，命令返回不等于事情做完了。
const WAIT_TIMEOUT: Duration = Duration::from_secs(15);

fn manager(access: ServiceManagerAccess) -> Result<ServiceManager> {
    ServiceManager::local_computer(None::<&str>, ServiceManagerAccess::CONNECT | access)
        .context("连接服务管理器失败。这一步需要管理员权限，请用管理员身份运行")
}

pub fn install() -> Result<()> {
    let manager = manager(ServiceManagerAccess::CREATE_SERVICE)?;
    let exe = std::env::current_exe().context("取不到自身路径")?;

    let info = ServiceInfo {
        name: OsString::from(SERVICE_NAME),
        display_name: OsString::from(DISPLAY_NAME),
        service_type: SERVICE_TYPE,
        start_type: ServiceStartType::AutoStart,
        error_control: ServiceErrorControl::Normal,
        executable_path: exe.clone(),
        launch_arguments: vec![OsString::from(RUN_ARG)],
        dependencies: vec![],
        // None 就是 LocalSystem。见模块开头：必须是它，否则看不到物理音频端点。
        account_name: None,
        account_password: None,
    };

    let service = manager
        .create_service(&info, ServiceAccess::CHANGE_CONFIG)
        .context("创建服务失败（已经装过的话先 uninstall）")?;
    service
        .set_description(DESCRIPTION)
        .context("写服务描述失败")?;

    println!("已注册服务 {SERVICE_NAME}（开机自启）");
    println!("  程序：{}", exe.display());
    println!("  账户：LocalSystem（session 0，能看到物理音频设备）");
    println!("  资源目录：{}", crate::asset_root().display());
    println!();
    println!("接下来：rust-kvm start");
    Ok(())
}

pub fn uninstall() -> Result<()> {
    let manager = manager(ServiceManagerAccess::empty())?;
    let service = manager
        .open_service(
            SERVICE_NAME,
            ServiceAccess::QUERY_STATUS | ServiceAccess::STOP | ServiceAccess::DELETE,
        )
        .context("打不开服务，可能本来就没装")?;

    if service.query_status()?.current_state != ServiceState::Stopped {
        service.stop().context("停止服务失败")?;
        // 删除要等它真的停下来，否则服务只会被「标记为删除」，
        // 一直挂到下次重启，期间既跑不起来也装不回去
        wait_for(&service, ServiceState::Stopped)?;
    }
    service.delete().context("删除服务失败")?;
    println!("已卸载服务 {SERVICE_NAME}");
    Ok(())
}

pub fn start() -> Result<()> {
    let manager = manager(ServiceManagerAccess::empty())?;
    let service = manager
        .open_service(
            SERVICE_NAME,
            ServiceAccess::QUERY_STATUS | ServiceAccess::START,
        )
        .context("打不开服务，先跑一次 rust-kvm install")?;

    if service.query_status()?.current_state == ServiceState::Running {
        println!("服务已经在跑了");
        return Ok(());
    }
    service.start(&[] as &[&OsString]).context("启动服务失败")?;
    wait_for(&service, ServiceState::Running)?;
    println!("服务已启动。日志在 {}", log_path().display());
    Ok(())
}

pub fn stop() -> Result<()> {
    let manager = manager(ServiceManagerAccess::empty())?;
    let service = manager
        .open_service(SERVICE_NAME, ServiceAccess::QUERY_STATUS | ServiceAccess::STOP)
        .context("打不开服务，可能没装")?;

    if service.query_status()?.current_state == ServiceState::Stopped {
        println!("服务本来就是停的");
        return Ok(());
    }
    service.stop().context("停止服务失败")?;
    wait_for(&service, ServiceState::Stopped)?;
    println!("服务已停止");
    Ok(())
}

pub fn status() -> Result<()> {
    let manager = manager(ServiceManagerAccess::empty())?;
    let Ok(service) = manager.open_service(SERVICE_NAME, ServiceAccess::QUERY_STATUS) else {
        println!("服务没有安装。跑 rust-kvm install 装上（需要管理员权限）");
        return Ok(());
    };
    let status = service.query_status().context("查询服务状态失败")?;
    println!("服务 {SERVICE_NAME}：{:?}", status.current_state);
    println!("日志：{}", log_path().display());
    Ok(())
}

/// SCM 的启停都是异步的：命令返回只代表请求送到了。轮询到目标状态才算数，
/// 否则「install → start → 立刻访问」这套连招会时不时扑空。
fn wait_for(
    service: &windows_service::service::Service,
    target: ServiceState,
) -> Result<()> {
    let deadline = Instant::now() + WAIT_TIMEOUT;
    loop {
        let state = service.query_status()?.current_state;
        if state == target {
            return Ok(());
        }
        if Instant::now() >= deadline {
            bail!("等服务变成 {target:?} 超时，当前是 {state:?}。看看 {} 里有什么", log_path().display());
        }
        std::thread::sleep(Duration::from_millis(300));
    }
}

pub fn log_path() -> std::path::PathBuf {
    crate::asset_root().join("rust-kvm.log")
}

/// 被 SCM 拉起来时走这里，会一直阻塞到服务停止。
pub fn run_as_service() -> Result<()> {
    service_dispatcher::start(SERVICE_NAME, ffi_service_main)
        .context("向服务管理器注册失败。这个命令只能由服务管理器调用，不要手动跑")
}

define_windows_service!(ffi_service_main, service_main);

fn service_main(_arguments: Vec<OsString>) {
    // 服务里没有 stdout，日志只能进文件，而且这一步要最先做——否则后面出的错
    // 会安静地消失，从外面只能看到「服务启动后又停止了」这种毫无线索的提示。
    let _ = crate::init_logging(Some(&log_path()));

    if let Err(e) = run_service() {
        tracing::error!("服务异常退出: {e:#}");
    }
}

fn run_service() -> Result<()> {
    let handle = crate::ServerHandle::new();

    let shutdown = handle.clone();
    let status_handle = service_control_handler::register(SERVICE_NAME, move |control| {
        match control {
            ServiceControl::Interrogate => ServiceControlHandlerResult::NoError,
            ServiceControl::Stop | ServiceControl::Shutdown => {
                tracing::info!("收到停止指令，开始收尾");
                shutdown.graceful_shutdown(Some(STOP_GRACE));
                ServiceControlHandlerResult::NoError
            }
            _ => ServiceControlHandlerResult::NotImplemented,
        }
    })
    .context("注册服务控制回调失败")?;

    status_handle
        .set_service_status(ServiceStatus {
            service_type: SERVICE_TYPE,
            current_state: ServiceState::Running,
            // 也接受 SHUTDOWN：关机时能收到通知，才有机会把「全释放」包发出去
            controls_accepted: ServiceControlAccept::STOP | ServiceControlAccept::SHUTDOWN,
            exit_code: ServiceExitCode::Win32(0),
            checkpoint: 0,
            wait_hint: Duration::default(),
            process_id: None,
        })
        .context("上报 Running 状态失败")?;

    let outcome = crate::block_on_server(handle);
    if let Err(e) = &outcome {
        tracing::error!("服务主体退出: {e:#}");
    }

    // 无论成败都要上报 Stopped，否则 SCM 会一直显示「正在停止」，
    // 直到超时才强杀，期间既停不掉也起不来。
    status_handle
        .set_service_status(ServiceStatus {
            service_type: SERVICE_TYPE,
            current_state: ServiceState::Stopped,
            controls_accepted: ServiceControlAccept::empty(),
            exit_code: match &outcome {
                Ok(()) => ServiceExitCode::Win32(0),
                Err(_) => ServiceExitCode::ServiceSpecific(1),
            },
            checkpoint: 0,
            wait_hint: Duration::default(),
            process_id: None,
        })
        .context("上报 Stopped 状态失败")?;

    outcome
}
