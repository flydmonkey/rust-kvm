//! 设备枚举，供设置页选设备用。
//!
//! 远程浏览器没有宿主机的 `getUserMedia` / Web Serial 权限，所以不能再走
//! chrome-kvm 那套「浏览器弹窗授权选设备」。列表只能由服务端给。
//!
//! 每一类都带自己的 `error`，而不是整体失败或者返回空列表了事。空列表的原因
//! 往往才是用户真正需要知道的——尤其音频：远程桌面会话里物理端点根本不被枚举
//! （Linux 上则是用户不在 `audio` 组），光看到一个空下拉框没人猜得出来是为什么。

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc;
use std::time::Duration;

use cpal::traits::{DeviceTrait, HostTrait};
use serde::Serialize;

use crate::serial_bridge::{self, PortInfo};

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Devices {
    pub video: DeviceList<VideoDevice>,
    pub audio: DeviceList<AudioDevice>,
    pub serial: DeviceList<PortInfo>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DeviceList<T> {
    pub items: Vec<T>,
    /// 枚举失败或结果为空时的人话解释，直接显示给用户
    pub error: Option<String>,
}

impl<T> DeviceList<T> {
    fn ok(items: Vec<T>) -> Self {
        Self { items, error: None }
    }

    fn failed(message: String) -> Self {
        Self {
            items: Vec::new(),
            error: Some(message),
        }
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct VideoDevice {
    pub index: String,
    pub name: String,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AudioDevice {
    pub name: String,
    /// cpal 的设备 ID（如 `alsa:hw:CARD=MS2109,DEV=0`）。名字不唯一时设置页靠它区分：
    /// ALSA 下同一张卡会以好几个 PCM 出现，名字一字不差。
    pub id: Option<String>,
    /// 设备自报的默认配置。这两个值值得显示出来：这张采集卡报的是 96000 Hz
    /// 单声道，光看名字看不出它和普通麦克风的区别。
    pub sample_rate: Option<u32>,
    pub channels: Option<u16>,
}

pub fn enumerate() -> Devices {
    Devices {
        video: video_devices(),
        audio: within(&AUDIO_BUSY, AUDIO_TIMEOUT, AUDIO_STUCK, audio_devices),
        serial: serial_devices(),
    }
}

/// 列音频要真的打开每个设备问默认格式，而 cpal 用一把全局锁串起所有打开操作：
/// 有一次打开卡死在驱动或插件里，之后每一次都排在锁后面永远等着。所以音频单独
/// 限时，卡住时只有这一栏报错，视频和串口照常列出来。比外层的整体超时短，不然
/// 外层先放弃，这一栏的解释就送不到页面上。
const AUDIO_TIMEOUT: Duration = Duration::from_secs(3);
static AUDIO_BUSY: AtomicBool = AtomicBool::new(false);
const AUDIO_STUCK: &str = "音频设备列不出来：之前有一次打开音频设备卡住了一直没返回，\
     之后的音频操作都在排队等它。重启服务才能恢复，视频和串口不受影响";

/// 在单独的线程里跑 `work`，限时等结果。
///
/// 上一次还没回来就不再开新线程，直接报卡住：卡住的线程收不回来，每刷新一次
/// 设置页就多攒一个，攒多了整个进程跟着拖垮。
fn within<T: Send + 'static>(
    busy: &'static AtomicBool,
    limit: Duration,
    stuck: &str,
    work: fn() -> DeviceList<T>,
) -> DeviceList<T> {
    if busy.swap(true, Ordering::SeqCst) {
        return DeviceList::failed(stuck.to_string());
    }
    let (tx, rx) = mpsc::channel();
    let spawned = std::thread::Builder::new()
        .name("list-devices".into())
        .spawn(move || {
            let list = work();
            busy.store(false, Ordering::SeqCst);
            let _ = tx.send(list);
        });
    if let Err(e) = spawned {
        busy.store(false, Ordering::SeqCst);
        return DeviceList::failed(format!("开不了枚举线程: {e}"));
    }
    rx.recv_timeout(limit)
        .unwrap_or_else(|_| DeviceList::failed(stuck.to_string()))
}

/// 只列名字，不去问每个设备支持哪些分辨率——那需要真的打开设备，而采集卡是
/// 独占的，枚举时打开会跟正在推流的采集撞车。要看格式清单用 `cargo run --bin probe`。
fn video_devices() -> DeviceList<VideoDevice> {
    match crate::camera::capture_devices() {
        Ok(devices) => DeviceList::ok(
            devices
                .iter()
                .map(|info| VideoDevice {
                    index: info.index().to_string(),
                    name: info.human_name(),
                })
                .collect(),
        ),
        Err(e) => DeviceList::failed(format!("{e:#}")),
    }
}

fn audio_devices() -> DeviceList<AudioDevice> {
    let host = cpal::default_host();
    let devices = match host.input_devices() {
        Ok(devices) => devices,
        Err(e) => return DeviceList::failed(format!("枚举音频设备失败: {e}")),
    };

    let items: Vec<AudioDevice> = devices
        .filter(crate::audio::opens_directly)
        .map(|device| {
            let config = device.default_input_config().ok();
            AudioDevice {
                // cpal 0.18 起没有 name()，设备名走 Display
                name: device.to_string(),
                id: device.id().ok().map(|id| id.to_string()),
                sample_rate: config.as_ref().map(|c| c.sample_rate()),
                channels: config.as_ref().map(|c| c.channels()),
            }
        })
        .collect();

    if items.is_empty() {
        return DeviceList::failed(crate::audio::NO_INPUT_DEVICES.to_string());
    }
    DeviceList::ok(items)
}

fn serial_devices() -> DeviceList<PortInfo> {
    DeviceList::ok(serial_bridge::list_ports())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Instant;

    const LIMIT: Duration = Duration::from_millis(100);
    fn quick() -> DeviceList<u32> {
        DeviceList::ok(vec![1, 2])
    }

    fn hangs() -> DeviceList<u32> {
        std::thread::sleep(Duration::from_millis(800));
        DeviceList::ok(vec![3])
    }

    #[test]
    fn passes_result_through_and_frees_the_slot() {
        static BUSY: AtomicBool = AtomicBool::new(false);
        let list = within(&BUSY, LIMIT, "卡住", quick);
        assert_eq!(list.items, vec![1, 2]);
        assert!(list.error.is_none());
        assert_eq!(within(&BUSY, LIMIT, "卡住", quick).items, vec![1, 2]);
    }

    #[test]
    fn gives_up_in_time_and_does_not_pile_up_threads() {
        static BUSY: AtomicBool = AtomicBool::new(false);
        let started = Instant::now();
        let first = within(&BUSY, LIMIT, "卡住", hangs);
        assert_eq!(first.error.as_deref(), Some("卡住"));
        assert!(started.elapsed() < Duration::from_millis(500));

        // 上一次还卡着：立刻报错，不再开线程
        let started = Instant::now();
        let second = within(&BUSY, LIMIT, "卡住", quick);
        assert_eq!(second.error.as_deref(), Some("卡住"));
        assert!(started.elapsed() < Duration::from_millis(50));

        // 卡住的那次终于回来了，之后照常
        std::thread::sleep(Duration::from_millis(900));
        assert_eq!(within(&BUSY, LIMIT, "卡住", quick).items, vec![1, 2]);
    }
}
