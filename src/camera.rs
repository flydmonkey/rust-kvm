//! 枚举采集设备。服务端和探针都从这里拿列表，`nokhwa::query` 不直接用。
//!
//! Linux 上 nokhwa 把 `/dev/video*` 一个不落地列出来，而 UVC 驱动给每个摄像头建
//! **两个**节点：一个采画面，一个只吐元数据（`V4L2_CAP_META_CAPTURE`）。两个名字一
//! 模一样，顺序还是倒着的——元数据那个排在前面，「没指定设备就用第一个」于是正好
//! 打开它，报一句看不出原因的 `Could not get device property CameraFormat`。

use anyhow::{Context, Result};
use nokhwa::query;
use nokhwa::utils::{ApiBackend, CameraInfo};

pub fn capture_devices() -> Result<Vec<CameraInfo>> {
    let devices = query(ApiBackend::Auto).context("枚举采集设备失败")?;
    Ok(filter(devices))
}

#[cfg(target_os = "linux")]
fn filter(mut devices: Vec<CameraInfo>) -> Vec<CameraInfo> {
    use nokhwa::utils::CameraIndex;
    use v4l::capability::Flags;

    devices.retain(|info| {
        let CameraIndex::Index(index) = info.index() else {
            return true;
        };
        // 问不到就留着：多半是没权限，那就让打开那一步去报，那里的错误信息更具体
        match v4l::Device::new(*index as usize).and_then(|device| device.query_caps()) {
            Ok(caps) => caps
                .capabilities
                .intersects(Flags::VIDEO_CAPTURE | Flags::VIDEO_CAPTURE_MPLANE),
            Err(_) => true,
        }
    });
    devices.sort_by_key(|info| info.index().as_index().unwrap_or(u32::MAX));
    devices
}

#[cfg(not(target_os = "linux"))]
fn filter(devices: Vec<CameraInfo>) -> Vec<CameraInfo> {
    devices
}
