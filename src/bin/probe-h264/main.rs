//! 探针：确认这台机器上的 H.264 编码 MFT 能不能用，以及 JPEG→NV12 那一步对不对。
//!
//! 存在的理由：整个低带宽模式压在「编码器能开、能吃系统内存的 NV12、能吐 SPS」
//! 这三件事上。先用一个小程序确认，而不是写完一整条管线之后才发现前提不成立。
//!
//! 编码那部分**直接用服务端那份 `h264::Encoder`**，不另写一份——探针的价值就在于
//! 验的是真代码。
//!
//! 三个模式：
//!
//! - `cargo run --bin probe-h264` —— 编码器探针
//! - `cargo run --bin probe-h264 -- --grab` —— 从采集卡抓一帧存成 `frame.jpg`
//! - `cargo run --bin probe-h264 -- --jpeg frame.jpg` —— 解成 NV12，把 Y 平面存成
//!   `frame-y.pgm` 肉眼看几何对不对，并横向采样报 Y/U/V
//!
//! **采集卡被服务占着时抓不到帧**，先 `rust-kvm.exe stop`。

use anyhow::Result;

// 二进制之间不能互相 use，所以直接把模块源码包进来。项目里的探针本来就是这个
// 路数（各自独立、不进服务端），比为此新开一个 lib target 轻。
//
// 这个探针做成目录（`src/bin/probe-h264/main.rs`）而不是单文件，是因为 cargo 会把
// `src/bin/` 下的**每个** .rs 都当成一个独立二进制——子模块放在那儿会因为「没有
// main 函数」编译失败。
#[path = "../../h264.rs"]
mod h264;
#[path = "../../nv12.rs"]
mod nv12;

mod jpeg_probe;

fn main() -> Result<()> {
    let args: Vec<String> = std::env::args().skip(1).collect();
    match args.first().map(String::as_str) {
        Some("--grab") => jpeg_probe::grab("frame.jpg"),
        Some("--jpeg") => {
            let path = args.get(1).map(String::as_str).unwrap_or("frame.jpg");
            jpeg_probe::to_pgm(path, "frame-y.pgm")
        }
        Some(other) => anyhow::bail!("不认识的参数 {other}（只有 --grab 和 --jpeg）"),
        None => encoder_probe(),
    }
}

#[cfg(not(windows))]
fn encoder_probe() -> Result<()> {
    anyhow::bail!("编码器探针只在 Windows 上有意义");
}

#[cfg(windows)]
fn encoder_probe() -> Result<()> {
    use std::time::Instant;

    use anyhow::{Context, bail};
    use windows::Win32::System::Com::{COINIT_MULTITHREADED, CoInitializeEx};

    const WIDTH: u32 = 1920;
    const HEIGHT: u32 = 1080;
    const FPS: u32 = 30;
    const BITRATE_KBPS: u32 = 4000;
    const FRAMES: usize = 60;

    // 服务端是在 spawn_blocking 的线程上跑编码的，那里 tokio 不会替我们初始化 COM
    unsafe { CoInitializeEx(None, COINIT_MULTITHREADED) }
        .ok()
        .context("CoInitializeEx")?;

    // 空手而归和「有但开不起来」在诊断上是两回事，所以枚举和创建分开报
    let found = h264::list_encoders()?;
    println!("找到 {} 个 H.264 编码器 MFT：", found.len());
    for (i, name) in found.iter().enumerate() {
        println!("  [{i}] {name}");
    }
    if found.is_empty() {
        bail!("一个都没有。低带宽模式在这台机器上没有出路。");
    }
    println!();

    let mut encoder = h264::Encoder::new(h264::EncoderConfig {
        width: WIDTH,
        height: HEIGHT,
        fps: FPS,
        bitrate_kbps: BITRATE_KBPS,
    })?;
    println!("编码器已就绪（NV12 进 / H.264 出，低延迟参数已设）");

    let mut frame = synthetic_frame(WIDTH, HEIGHT, 0);
    println!("喂 {FRAMES} 帧 {WIDTH}x{HEIGHT} 合成画面…");
    println!();

    let mut bytes = 0usize;
    let mut packets = 0usize;
    let mut keyframes = 0usize;
    let mut first: Option<bytes::Bytes> = None;
    let started = Instant::now();

    for i in 0..FRAMES {
        // GOP 是 10 秒，60 帧里本来只该有开头那一个关键帧。中途要一个，用来验强制
        // IDR 真的管用——新观看者进场全靠它，不管用的话那人就永远是黑屏。
        if i == FRAMES / 2 {
            encoder.request_keyframe();
        }
        // 每帧都把图案挪一格：**整屏都在动**，是码率的最坏情况。码率控制用的是
        // 「恒定质量 + 上限」，静止时几乎不要钱，所以要验的恰恰是上限管不管用
        // ——不管用的话画面一动就冲上去，WAN 那头整件事就白做了。
        shift_pattern(&mut frame, i);
        for packet in encoder.encode(&frame)? {
            bytes += packet.data.len();
            packets += 1;
            if packet.keyframe {
                keyframes += 1;
            }
            if first.is_none() {
                first = Some(packet.data);
            }
        }
    }
    let elapsed = started.elapsed();

    let seconds = FRAMES as f64 / FPS as f64;
    println!("单帧平均 {:.2} ms", elapsed.as_secs_f64() * 1000.0 / FRAMES as f64);
    println!("产出 {packets} 个包（其中关键帧 {keyframes} 个），共 {bytes} 字节");
    println!(
        "实际码率 {:.2} Mbps（目标 {:.1}）",
        bytes as f64 * 8.0 / seconds / 1e6,
        BITRATE_KBPS as f64 / 1000.0
    );

    let Some(packet) = first else {
        bail!("一个包都没吐出来");
    };
    println!(
        "首包 {} 字节，前 16 字节：{:02x?}",
        packet.len(),
        &packet[..packet.len().min(16)]
    );

    // 客户端 VideoDecoder.configure 既要这个字符串，也要码流里带 SPS 才起得来。
    // 拿不到就说明这条路走不通。
    let Some(codec) = encoder.codec_string() else {
        bail!("码流里没有 SPS。客户端配置不出解码器，这条路不通。");
    };
    if keyframes == 0 {
        bail!("一个关键帧都没有。新观看者进场会什么都画不出来。");
    }
    // 开头一个 + 中途强制的那个
    if keyframes < 2 {
        bail!("只有 {keyframes} 个关键帧，强制 IDR 没起作用。新观看者进场会一直黑屏。");
    }
    println!();
    println!("含 SPS，codec 字符串 {codec} —— 前提成立，可以往下走");
    Ok(())
}

/// 一张有内容的假图：纯色的话编码器几乎不产码率，量不出真实开销。
fn synthetic_frame(width: u32, height: u32, phase: usize) -> nv12::Nv12Frame {
    let mut frame = nv12::Nv12Frame::new(width, height);
    // 色度给中性灰，只是别让它是 0（那是绿色，看日志时容易误判）
    frame.data[(width * height) as usize..].fill(128);
    shift_pattern(&mut frame, phase);
    frame
}

fn shift_pattern(frame: &mut nv12::Nv12Frame, phase: usize) {
    let w = frame.width as usize;
    for y in 0..frame.height as usize {
        for x in 0..w {
            frame.data[y * w + x] = ((x * 7 + y * 13 + phase * 11) % 256) as u8;
        }
    }
}
