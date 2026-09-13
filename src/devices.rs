//! 设备枚举，供设置页选设备用。
//!
//! 远程浏览器没有宿主机的 `getUserMedia` / Web Serial 权限，所以不能再走
//! chrome-kvm 那套「浏览器弹窗授权选设备」。列表只能由服务端给。
//!
//! 每一类都带自己的 `error`，而不是整体失败或者返回空列表了事。空列表的原因
//! 往往才是用户真正需要知道的——尤其音频：远程桌面会话里物理端点根本不被枚举，
//! 光看到一个空下拉框没人猜得出来是为什么。

use cpal::traits::{DeviceTrait, HostTrait};
use nokhwa::utils::ApiBackend;
use nokhwa::query;
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
    /// 设备自报的默认配置。这两个值值得显示出来：这张采集卡报的是 96000 Hz
    /// 单声道，光看名字看不出它和普通麦克风的区别。
    pub sample_rate: Option<u32>,
    pub channels: Option<u16>,
}

pub fn enumerate() -> Devices {
    Devices {
        video: video_devices(),
        audio: audio_devices(),
        serial: serial_devices(),
    }
}

/// 只列名字，不去问每个设备支持哪些分辨率——那需要真的打开设备，而采集卡是
/// 独占的，枚举时打开会跟正在推流的采集撞车。要看格式清单用 `cargo run --bin probe`。
fn video_devices() -> DeviceList<VideoDevice> {
    match query(ApiBackend::Auto) {
        Ok(devices) => DeviceList::ok(
            devices
                .iter()
                .map(|info| VideoDevice {
                    index: info.index().to_string(),
                    name: info.human_name(),
                })
                .collect(),
        ),
        Err(e) => DeviceList::failed(format!("枚举视频设备失败: {e}")),
    }
}

fn audio_devices() -> DeviceList<AudioDevice> {
    let host = cpal::default_host();
    let devices = match host.input_devices() {
        Ok(devices) => devices,
        Err(e) => return DeviceList::failed(format!("枚举音频设备失败: {e}")),
    };

    let items: Vec<AudioDevice> = devices
        .map(|device| {
            let config = device.default_input_config().ok();
            AudioDevice {
                // cpal 0.18 起没有 name()，设备名走 Display
                name: device.to_string(),
                sample_rate: config.as_ref().map(|c| c.sample_rate()),
                channels: config.as_ref().map(|c| c.channels()),
            }
        })
        .collect();

    if items.is_empty() {
        // 空列表在这里几乎总是同一个原因，直说比让人去猜好
        return DeviceList::failed(
            "没有找到任何音频输入设备。如果服务是在远程桌面会话里启动的，\
             物理音频端点在那个会话里不可见，需要改到控制台会话或 Windows 服务里跑"
                .to_string(),
        );
    }
    DeviceList::ok(items)
}

fn serial_devices() -> DeviceList<PortInfo> {
    DeviceList::ok(serial_bridge::list_ports())
}
