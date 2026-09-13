//! 验 JPEG→NV12 那一步：抓一帧真图，转完把 Y 平面存成灰度图肉眼看。
//!
//! 为什么要肉眼这一环：单元测试能证明「Y 取每第 3 个字节、UV 按 2x2 取平均」这个
//! 算术对，但证明不了跨距、行序、尺寸这些**几何**对——那类错误的表现是斜切、
//! 上下颠倒、半张图偏移，而它们在算术上全都「对」。指望靠 H.264 码流反推更是没
//! 指望：编码器对着一张斜掉的图照样编得出合法码流。

use std::io::Write;

use anyhow::{Context, Result, bail};
use nokhwa::pixel_format::RgbFormat;
use nokhwa::utils::{
    ApiBackend, CameraFormat, FrameFormat, RequestedFormat, RequestedFormatType, Resolution,
};
use nokhwa::{Camera, query};

use crate::nv12;

/// 从采集卡抓一帧 MJPEG 原始字节存成文件。
pub fn grab(path: &str) -> Result<()> {
    let devices = query(ApiBackend::Auto).context("枚举采集设备失败")?;
    let device = devices
        .iter()
        .find(|d| d.human_name().contains("USB Video"))
        .or_else(|| devices.first())
        .context("一个采集设备都没有")?;
    println!("用设备：{}", device.human_name());

    let wanted = CameraFormat::new(Resolution::new(1920, 1080), FrameFormat::MJPEG, 30);
    let request = RequestedFormat::new::<RgbFormat>(RequestedFormatType::Closest(wanted));
    let mut camera = Camera::new(device.index().clone(), request)
        .context("打开采集卡失败（服务在跑的话先 rust-kvm.exe stop）")?;
    camera.open_stream().context("开流失败")?;

    // 头几帧常是黑的或者没曝光好，丢掉再取
    for _ in 0..5 {
        let _ = camera.frame_raw();
    }
    let raw = camera.frame_raw().context("取帧失败")?;
    if raw.len() < 4 || raw[0] != 0xFF || raw[1] != 0xD8 {
        bail!("取到的不是 JPEG（前两字节 {:02x?}）", &raw[..raw.len().min(2)]);
    }
    std::fs::write(path, &raw).with_context(|| format!("写 {path} 失败"))?;
    println!("已存 {path}（{} 字节）", raw.len());
    Ok(())
}

/// 解一帧 JPEG 转成 NV12，把 Y 平面存成 PGM。
pub fn to_pgm(jpeg_path: &str, pgm_path: &str) -> Result<()> {
    let jpeg = std::fs::read(jpeg_path).with_context(|| format!("读 {jpeg_path} 失败"))?;
    // 尺寸随便给，decode_jpeg_to_nv12 会按 JPEG 头重算
    let mut frame = nv12::Nv12Frame::new(16, 16);
    nv12::decode_jpeg_to_nv12(&jpeg, &mut frame)?;

    let (w, h) = (frame.width, frame.height);
    println!("解出 {w}x{h}，NV12 共 {} 字节", frame.data.len());

    // 灰度图看不出色度对不对，而「色度全中性」是个很容易发生又很难发现的错法：
    // 画面看着正常，只是没有颜色，等到编码之后才发现就晚了。所以横着采八个点
    // 报出 Y/U/V——对着彩条源看，黄条该是 U 低 V 中、蓝条该是 U 高 V 低。
    let y_size = (w * h) as usize;
    let half_w = w.div_ceil(2) as usize;
    let mid = h as usize / 2;
    println!("中间一行横向采样（x: Y U V）：");
    for i in 0..8 {
        let x = (w as usize * (2 * i + 1)) / 16;
        let y = frame.data[mid * w as usize + x];
        let uv = y_size + ((mid / 2) * half_w + x / 2) * 2;
        println!("  {x:>4}: {y:>3} {:>3} {:>3}", frame.data[uv], frame.data[uv + 1]);
    }

    // PGM 而不是 PNG：五行就写出来了，不用引编码库，而任何看图软件都能开
    let mut file = std::fs::File::create(pgm_path)?;
    write!(file, "P5\n{w} {h}\n255\n")?;
    file.write_all(&frame.data[..(w * h) as usize])?;
    println!("已存 {pgm_path} —— 打开看：画面正常、没斜切、没上下颠倒即为通过");
    Ok(())
}
