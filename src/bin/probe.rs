//! 采集卡探测：验证 MJPEG 能否原样取出，并实测帧率与帧大小。
//!
//! 整套方案的地基是「服务端不解码、把采集卡吐出的 JPEG 原样转发给浏览器」。
//! 但 Media Foundation 在某些配置下会自己插一个解码器，把 MJPEG 转成 NV12
//! 再交给调用方——那样 frame_raw() 拿到的就不是 JPEG 了，视频链路得整个重做。
//! 所以先用这个程序确认三件事：拿到的字节是不是以 FFD8 开头、1080p 下的真实
//! 帧率、以及单帧多大（用来估带宽）。

use std::time::{Duration, Instant};

use nokhwa::Camera;
use nokhwa::pixel_format::RgbFormat;
use nokhwa::utils::{
    CameraFormat, CameraIndex, FrameFormat, RequestedFormat, RequestedFormatType, Resolution,
};

// 和服务端用同一份枚举，否则 Linux 上探针会去开 UVC 的元数据节点
#[path = "../camera.rs"]
mod camera;

const PROBE_SECONDS: u64 = 5;
const TARGET: Resolution = Resolution {
    width_x: 1920,
    height_y: 1080,
};
const TARGET_FPS: u32 = 30;

fn main() {
    let devices = match camera::capture_devices() {
        Ok(devices) => devices,
        Err(e) => {
            eprintln!("{e:#}");
            return;
        }
    };

    println!("=== 设备列表 ===");
    for info in &devices {
        println!("  [{}] {}", info.index(), info.human_name());
    }
    if devices.is_empty() {
        eprintln!("没有找到任何摄像头设备");
        return;
    }

    // 名字里带 "USB Video" 的就是那张 MS2109 采集卡；找不到就退回第一个设备，
    // 这样换机器时不用改代码。
    let picked = devices
        .iter()
        .find(|info| info.human_name().contains("USB Video"))
        .unwrap_or(&devices[0]);
    println!("\n选中: [{}] {}", picked.index(), picked.human_name());

    let index = picked.index().clone();
    list_formats(&index);
    probe_mjpeg(&index);
}

/// 把设备支持的 MJPEG 组合列出来，和 ffmpeg -list_options 的结果对一下，
/// 顺便确认 nokhwa 自己看到的能力表和 DirectShow 那边是否一致。
fn list_formats(index: &CameraIndex) {
    let request = RequestedFormat::new::<RgbFormat>(RequestedFormatType::AbsoluteHighestFrameRate);
    let mut camera = match Camera::new(index.clone(), request) {
        Ok(camera) => camera,
        Err(e) => {
            eprintln!("打开设备失败（列格式阶段）: {e}");
            return;
        }
    };

    println!("\n=== 支持的 MJPEG 格式 ===");
    match camera.compatible_camera_formats() {
        Ok(formats) => {
            let mut mjpeg: Vec<_> = formats
                .into_iter()
                .filter(|f| f.format() == FrameFormat::MJPEG)
                .collect();
            mjpeg.sort_by_key(|f| (f.width(), f.height(), f.frame_rate()));
            for format in mjpeg {
                println!(
                    "  {}x{} @ {}fps",
                    format.width(),
                    format.height(),
                    format.frame_rate()
                );
            }
        }
        Err(e) => eprintln!("  查询失败: {e}"),
    }
}

/// 按 1080p30 MJPEG 打开，跑几秒钟，统计帧率和帧大小，并检查 JPEG 魔数。
fn probe_mjpeg(index: &CameraIndex) {
    let wanted = CameraFormat::new(TARGET, FrameFormat::MJPEG, TARGET_FPS);
    let request = RequestedFormat::new::<RgbFormat>(RequestedFormatType::Closest(wanted));

    let mut camera = match Camera::new(index.clone(), request) {
        Ok(camera) => camera,
        Err(e) => {
            eprintln!("\n按 MJPEG 打开设备失败: {e}");
            return;
        }
    };

    println!("\n=== 协商结果 ===");
    let negotiated = camera.camera_format();
    println!(
        "  {}x{} @ {}fps, 格式 {:?}",
        negotiated.width(),
        negotiated.height(),
        negotiated.frame_rate(),
        negotiated.format()
    );
    if negotiated.format() != FrameFormat::MJPEG {
        eprintln!("  警告：协商到的不是 MJPEG，透传方案不成立");
    }

    if let Err(e) = camera.open_stream() {
        eprintln!("开流失败: {e}");
        return;
    }

    println!("\n=== 采集 {PROBE_SECONDS} 秒 ===");
    let mut frames = 0u32;
    let mut total_bytes = 0u64;
    let mut min_len = usize::MAX;
    let mut max_len = 0usize;
    let mut jpeg_frames = 0u32;
    let mut first_header: Option<Vec<u8>> = None;
    let mut errors = 0u32;
    // 每帧大小完全一样的话，缓冲区多半是定长的，真正的 JPEG 只占前面一段，
    // 后面全是填充。裁到 EOI 能省带宽，所以这里把两者都统计出来。
    let mut total_trimmed = 0u64;
    let mut min_trimmed = usize::MAX;
    let mut max_trimmed = 0usize;
    let mut no_eoi = 0u32;

    let started = Instant::now();
    while started.elapsed() < Duration::from_secs(PROBE_SECONDS) {
        let raw = match camera.frame_raw() {
            Ok(raw) => raw,
            Err(e) => {
                errors += 1;
                if errors <= 3 {
                    eprintln!("  取帧失败: {e}");
                }
                continue;
            }
        };

        // FFD8 是 JPEG 的 SOI 标记。只要它在，说明 MF 没有偷偷解码，
        // 这些字节可以直接甩给浏览器。
        let looks_like_jpeg = raw.len() >= 2 && raw[0] == 0xFF && raw[1] == 0xD8;
        if looks_like_jpeg {
            jpeg_frames += 1;
        }
        if first_header.is_none() {
            first_header = Some(raw[..raw.len().min(12)].to_vec());
        }

        match jpeg_end(&raw) {
            Some(end) => {
                total_trimmed += end as u64;
                min_trimmed = min_trimmed.min(end);
                max_trimmed = max_trimmed.max(end);
            }
            None => no_eoi += 1,
        }

        frames += 1;
        total_bytes += raw.len() as u64;
        min_len = min_len.min(raw.len());
        max_len = max_len.max(raw.len());
    }
    let elapsed = started.elapsed().as_secs_f64();

    let _ = camera.stop_stream();

    if frames == 0 {
        eprintln!("一帧都没拿到，取帧失败 {errors} 次");
        return;
    }

    println!("  首帧前 12 字节: {:02X?}", first_header.unwrap());
    println!(
        "  JPEG 帧: {jpeg_frames}/{frames}{}",
        if jpeg_frames == frames {
            "  <- 全部是原始 JPEG，透传方案成立"
        } else {
            "  <- 有非 JPEG 帧，需要改用手写 IMFSourceReader"
        }
    );
    println!("  实测帧率: {:.1} fps", frames as f64 / elapsed);
    println!(
        "  缓冲区大小: 平均 {} B, 最小 {min_len} B, 最大 {max_len} B",
        total_bytes / frames as u64
    );
    println!(
        "  码率: {:.1} Mbps",
        total_bytes as f64 * 8.0 / elapsed / 1_000_000.0
    );

    if no_eoi == frames {
        println!("  没有找到 EOI 标记，缓冲区就是 JPEG 本身，无需裁剪");
    } else {
        println!(
            "  裁到 EOI 之后: 平均 {} B, 最小 {min_trimmed} B, 最大 {max_trimmed} B",
            total_trimmed / (frames - no_eoi) as u64
        );
        println!(
            "  裁剪后码率: {:.1} Mbps（省了 {:.0}%）",
            total_trimmed as f64 * 8.0 / elapsed / 1_000_000.0,
            (1.0 - total_trimmed as f64 / total_bytes as f64) * 100.0
        );
        if no_eoi > 0 {
            println!("  有 {no_eoi} 帧没找到 EOI");
        }
    }

    if errors > 0 {
        println!("  取帧失败 {errors} 次");
    }
}

/// 找到 JPEG 的 EOI（FFD9）并返回它之后的长度，也就是有效数据的字节数。
/// 从尾部往前扫：定长缓冲区的填充通常是 0，EOI 一般紧贴着有效数据的末尾。
fn jpeg_end(buf: &[u8]) -> Option<usize> {
    buf.windows(2)
        .rposition(|pair| pair == [0xFF, 0xD9])
        .map(|at| at + 2)
}
