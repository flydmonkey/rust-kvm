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

/// 打开设备前先把帧间隔摆成整数帧率。
///
/// nokhwa 的 V4L2 后端开设备时先读**当前**帧间隔，帧率不是整数就直接报错，请求的
/// 格式根本来不及设下去。UGREEN 25173 的 240 档是 `20833/5000000`（240.004），设置页
/// 按这档实测过一次，设备就停在那儿，之后谁来开都失败，直到拔插。
///
/// 失败不报：设不上的话 nokhwa 那一步会给出更具体的错误。
#[cfg(target_os = "linux")]
#[allow(dead_code)]
pub fn settle_frame_interval(index: &nokhwa::utils::CameraIndex, fps: u32) {
    use v4l::video::Capture;
    use v4l::video::capture::Parameters;

    let Ok(index) = index.as_index() else {
        return;
    };
    if let Ok(device) = v4l::Device::new(index as usize) {
        let _ = device.set_params(&Parameters::with_fps(fps.max(1)));
    }
}

#[cfg(not(target_os = "linux"))]
#[allow(dead_code)]
pub fn settle_frame_interval(_index: &nokhwa::utils::CameraIndex, _fps: u32) {}

/// 一档 MJPEG。`rates` 是能力表里写的帧率，从高到低，**不能当实测**——
/// MS2109 的 1080p 也写着 60，真画面只有 30。
///
/// 探针用 `#[path]` 把这个文件另编一份，只用设备枚举，下面这些用不上。
#[derive(Debug, Clone, PartialEq, Eq)]
#[allow(dead_code)]
pub struct MjpegMode {
    pub width: u32,
    pub height: u32,
    pub rates: Vec<u32>,
}

/// 这张卡的 MJPEG 能力表。Linux 上只做 `ENUM_FRAMESIZES`，不改格式、不开流，
/// 采集正进行时也能问。别的平台走 nokhwa，那会短暂打开设备。
#[allow(dead_code)]
pub fn mjpeg_modes(index: u32) -> Result<Vec<MjpegMode>> {
    let mut modes = Vec::new();
    for (width, height, rates) in list_mjpeg(index)? {
        push_mode(&mut modes, width, height, rates);
    }
    modes.sort_by(|a, b| {
        (b.width as u64 * b.height as u64)
            .cmp(&(a.width as u64 * a.height as u64))
            .then(b.width.cmp(&a.width))
    });
    Ok(modes)
}

#[allow(dead_code)]
fn push_mode(modes: &mut Vec<MjpegMode>, width: u32, height: u32, mut rates: Vec<u32>) {
    rates.retain(|fps| *fps > 0);
    rates.sort_unstable();
    rates.dedup();
    rates.sort_unstable_by(|a, b| b.cmp(a));
    if let Some(existing) = modes
        .iter_mut()
        .find(|mode| mode.width == width && mode.height == height)
    {
        for fps in rates {
            if !existing.rates.contains(&fps) {
                existing.rates.push(fps);
            }
        }
        existing.rates.sort_unstable_by(|a, b| b.cmp(a));
        return;
    }
    modes.push(MjpegMode {
        width,
        height,
        rates,
    });
}

/// 帧间隔是秒。`1/60` → 60 fps。分子或分母为 0 的项丢掉。
#[allow(dead_code)]
fn fps_from_interval(numerator: u32, denominator: u32) -> Option<u32> {
    if numerator == 0 || denominator == 0 {
        return None;
    }
    Some((f64::from(denominator) / f64::from(numerator)).round().max(1.0) as u32)
}

#[cfg(target_os = "linux")]
#[allow(dead_code)]
fn list_mjpeg(index: u32) -> Result<Vec<(u32, u32, Vec<u32>)>> {
    use v4l::video::Capture;
    use v4l::{Device, FourCC};

    let device = Device::new(index as usize).context("打开采集设备失败")?;
    let sizes = device
        .enum_framesizes(FourCC::new(b"MJPG"))
        .context("枚举 MJPEG 分辨率失败")?;
    let mut listed = Vec::new();
    for size in sizes {
        let discrete: Vec<_> = match size.size {
            v4l::framesize::FrameSizeEnum::Discrete(discrete) => {
                vec![(discrete.width, discrete.height)]
            }
            // 步进范围展开会是成千上万档，设置页用不上。两端就够标「最大 / 最小」
            v4l::framesize::FrameSizeEnum::Stepwise(stepwise) => vec![
                (stepwise.min_width, stepwise.min_height),
                (stepwise.max_width, stepwise.max_height),
            ],
        };
        for (width, height) in discrete {
            let rates = match device.enum_frameintervals(FourCC::new(b"MJPG"), width, height) {
                Ok(intervals) => intervals
                    .into_iter()
                    .flat_map(|interval| -> Vec<u32> {
                        match interval.interval {
                            v4l::frameinterval::FrameIntervalEnum::Discrete(fraction) => {
                                fps_from_interval(fraction.numerator, fraction.denominator)
                                    .into_iter()
                                    .collect()
                            }
                            v4l::frameinterval::FrameIntervalEnum::Stepwise(stepwise) => {
                                // 间隔越短帧率越高
                                [
                                    fps_from_interval(
                                        stepwise.min.numerator,
                                        stepwise.min.denominator,
                                    ),
                                    fps_from_interval(
                                        stepwise.max.numerator,
                                        stepwise.max.denominator,
                                    ),
                                ]
                                .into_iter()
                                .flatten()
                                .collect()
                            }
                        }
                    })
                    .collect(),
                Err(_) => Vec::new(),
            };
            listed.push((width, height, rates));
        }
    }
    Ok(listed)
}

#[cfg(not(target_os = "linux"))]
#[allow(dead_code)]
fn list_mjpeg(index: u32) -> Result<Vec<(u32, u32, Vec<u32>)>> {
    use nokhwa::pixel_format::RgbFormat;
    use nokhwa::utils::{CameraIndex, FrameFormat, RequestedFormat, RequestedFormatType};
    use nokhwa::Camera;

    let request = RequestedFormat::new::<RgbFormat>(RequestedFormatType::AbsoluteHighestFrameRate);
    let mut camera = Camera::new(CameraIndex::Index(index), request).context("打开采集设备失败")?;
    let formats = camera
        .compatible_camera_formats()
        .context("枚举采集格式失败")?;
    let mut listed = Vec::new();
    for format in formats {
        if format.format() != FrameFormat::MJPEG {
            continue;
        }
        listed.push((format.width(), format.height(), vec![format.frame_rate()]));
    }
    Ok(listed)
}

#[cfg(test)]
mod tests {
    use super::fps_from_interval;

    #[test]
    fn interval_in_seconds_becomes_fps() {
        assert_eq!(fps_from_interval(1, 60), Some(60));
        assert_eq!(fps_from_interval(1, 30), Some(30));
        assert_eq!(fps_from_interval(0, 60), None);
        assert_eq!(fps_from_interval(1, 0), None);
    }

    /// 机器上插着采集卡才跑。只枚举、不开流，不该打断正在看的画面。
    #[test]
    fn 插着采集卡时能列出_mjpeg() {
        let Ok(devices) = super::capture_devices() else {
            return;
        };
        let Some(info) = devices
            .iter()
            .find(|info| info.human_name().contains("USB Video"))
        else {
            return;
        };
        let Ok(index) = info.index().as_index() else {
            return;
        };
        let modes = super::mjpeg_modes(index).expect("枚举 MJPEG");
        let hd = modes
            .iter()
            .find(|mode| mode.width == 1920 && mode.height == 1080)
            .expect("1080p");
        assert!(hd.rates.contains(&30));
        assert!(hd.rates.contains(&60));
        assert!(
            modes
                .iter()
                .any(|mode| mode.width == 1280 && mode.height == 720)
        );
    }
}
