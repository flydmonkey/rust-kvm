//! 音频探测：确认 cpal 在 Windows 上看到哪些输入设备、它们自报什么格式，
//! 然后录一段 WAV 供试听。
//!
//! 之所以要先探一把而不是直接搭管线，是因为这张卡的音频**不是它自报的样子**。
//! 实测它只支持一种配置：**96000 Hz 单声道**。而 HDMI 音频基本都是 48kHz 立体声，
//! 两者的数据率一模一样（96000×1 = 48000×2）。所以最可能的情况是：设备把一路
//! 48k 立体声原封不动地贴上了「96k 单声道」的标签，样本其实是 L R L R 交替的。
//!
//! 这解释了 Linux 内核为什么只给 `0x534d:0x2109` 设了 `stream_offset_adj = 2`
//! ——2 字节正好是一个 16 位样本，偏一个样本就是把左右声道调了个个儿。要是像我
//! 一开始猜的那样是「字节错位成噪声」，那点偏移量是不够解释的。
//!
//! 于是要分辨三种可能，办法是比较滞后 1 和滞后 2 的自相关：
//!
//! | 现象 | 结论 |
//! |------|------|
//! | 两个都高，且滞后 2 更高 | 48k 立体声被贴了 96k 单声道的标签 |
//! | 两个都高，且滞后 1 更高 | 真的是 96k 单声道 |
//! | 幅度不小但两个都接近 0 | 样本本身是错位的垃圾 |
//!
//! 道理：如果是交替的立体声，相邻两个样本是左和右（相关但不一样），而隔一个的
//! 两个样本是同一声道 48k 下的连续两点（几乎一样）。真单声道的话正好相反，
//! 越近越像。
//!
//! 同时写出两个 WAV——按自报格式的和按 48k 立体声重新解释的——哪个放出来对就是哪个。
//!
//! **所有这些都要求被控机正在出声**：全静音时样本全是 0，什么都分辨不出来。

use std::fs::File;
use std::io::{BufWriter, Write};
use std::sync::mpsc;
use std::time::{Duration, Instant};

use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use cpal::{SampleFormat, SupportedStreamConfig};

const RECORD_SECONDS: u64 = 5;
const DEVICE_HINT: &str = "Digital Audio Interface";
/// 按设备自报的格式写
const OUT_AS_REPORTED: &str = "audio-probe-asreported.wav";
/// 同一批样本，按「48k 立体声被贴了 96k 单声道标签」重新解释后写
const OUT_AS_STEREO: &str = "audio-probe-stereo.wav";

fn main() {
    let host = cpal::default_host();

    println!("=== 输入设备 ===");
    let devices: Vec<_> = match host.input_devices() {
        Ok(devices) => devices.collect(),
        Err(e) => {
            eprintln!("枚举输入设备失败: {e}");
            return;
        }
    };
    if devices.is_empty() {
        eprintln!("没有找到任何音频输入设备");
        return;
    }
    for device in &devices {
        // 0.18 起没有 name()，设备名走 Display
        let name = device.to_string();
        match device.default_input_config() {
            Ok(config) => println!(
                "  {name}\n      默认 {} Hz {} 声道 {:?}",
                config.sample_rate(),
                config.channels(),
                config.sample_format()
            ),
            Err(e) => println!("  {name}\n      默认配置读不出来: {e}"),
        }
    }

    let picked = devices
        .iter()
        .find(|device| device.to_string().contains(DEVICE_HINT))
        .unwrap_or(&devices[0]);
    println!("\n选中: {picked}");

    let config = match picked.default_input_config() {
        Ok(config) => config,
        Err(e) => {
            eprintln!("读默认输入配置失败: {e}");
            return;
        }
    };

    list_configs(picked);
    record(picked, config);
}

/// 把设备支持的采样率范围列出来。如果这里出现 96000 而默认是 48000（或者反过来），
/// 那采样率 quirk 的怀疑就有了落点。
fn list_configs(device: &cpal::Device) {
    println!("\n=== 支持的输入配置 ===");
    match device.supported_input_configs() {
        Ok(configs) => {
            for range in configs {
                println!(
                    "  {} 声道 {:?} {}–{} Hz",
                    range.channels(),
                    range.sample_format(),
                    range.min_sample_rate(),
                    range.max_sample_rate()
                );
            }
        }
        Err(e) => eprintln!("  查询失败: {e}"),
    }
}

fn record(device: &cpal::Device, supported: SupportedStreamConfig) {
    let sample_rate = supported.sample_rate();
    let channels = supported.channels();
    let sample_format = supported.sample_format();

    println!("\n=== 协商结果 ===");
    println!("  {sample_rate} Hz  {channels} 声道  {sample_format:?}");

    // 每个回调的帧数也值得看一眼：它决定了一块 PCM 有多少毫秒，
    // 直接影响到后面 /ws/audio 的包大小和抖动缓冲要开多大。
    let (tx, rx) = mpsc::channel::<(Vec<i16>, usize)>();
    let (err_tx, err_rx) = mpsc::channel::<String>();

    let error_callback = move |e: cpal::Error| {
        let _ = err_tx.send(format!("{e}"));
    };

    let config = supported.config();
    let stream = match sample_format {
        SampleFormat::I16 => {
            let tx = tx.clone();
            device.build_input_stream(
                config,
                move |data: &[i16], _: &_| {
                    let _ = tx.send((data.to_vec(), data.len()));
                },
                error_callback,
                None,
            )
        }
        SampleFormat::F32 => {
            let tx = tx.clone();
            device.build_input_stream(
                config,
                move |data: &[f32], _: &_| {
                    let pcm = data.iter().map(|s| f32_to_i16(*s)).collect();
                    let _ = tx.send((pcm, data.len()));
                },
                error_callback,
                None,
            )
        }
        other => {
            eprintln!("  暂不支持的采样格式 {other:?}，先在这儿停一下");
            return;
        }
    };

    let stream = match stream {
        Ok(stream) => stream,
        Err(e) => {
            eprintln!("建立输入流失败: {e}");
            return;
        }
    };
    if let Err(e) = stream.play() {
        eprintln!("开流失败: {e}");
        return;
    }

    println!("\n=== 录 {RECORD_SECONDS} 秒（请让被控机出点声）===");
    drop(tx);

    let started = Instant::now();
    let mut pcm: Vec<i16> = Vec::new();
    let mut callbacks = 0u32;
    let mut callback_samples: Vec<usize> = Vec::new();
    while started.elapsed() < Duration::from_secs(RECORD_SECONDS) {
        match rx.recv_timeout(Duration::from_millis(200)) {
            Ok((chunk, len)) => {
                callbacks += 1;
                if callback_samples.len() < 8 {
                    callback_samples.push(len);
                }
                pcm.extend_from_slice(&chunk);
            }
            Err(mpsc::RecvTimeoutError::Timeout) => {}
            Err(mpsc::RecvTimeoutError::Disconnected) => break,
        }
    }
    let elapsed = started.elapsed().as_secs_f64();
    drop(stream);

    while let Ok(message) = err_rx.try_recv() {
        eprintln!("  流错误: {message}");
    }

    if pcm.is_empty() {
        eprintln!("  一个样本都没收到。设备可能被独占，或者 Windows 的麦克风隐私设置挡住了。");
        return;
    }

    let frames = pcm.len() / channels as usize;
    let measured_rate = frames as f64 / elapsed;
    println!("  回调 {callbacks} 次，前几次每次样本数 {callback_samples:?}");
    if let Some(first) = callback_samples.first() {
        let ms = (*first as f64 / channels as f64) / sample_rate as f64 * 1000.0;
        println!("  一块约 {ms:.1} ms");
    }
    println!("  实际帧率 {measured_rate:.0} 帧/秒（自报 {sample_rate}）");
    if (measured_rate - sample_rate as f64).abs() / sample_rate as f64 > 0.05 {
        println!("  <- 和自报值差出 5% 以上，采样率可疑");
    }

    report_level(&pcm);
    report_interleave(&pcm, channels as usize);
    write_wavs(&pcm, sample_rate, channels);
}

/// 按自报格式写一个，再按「其实是一半采样率的立体声」写一个。
/// 单声道才有后一个：多声道的话这个假设本身就不成立。
fn write_wavs(pcm: &[i16], sample_rate: u32, channels: u16) {
    println!();
    match write_wav(OUT_AS_REPORTED, pcm, sample_rate, channels) {
        Ok(()) => println!("已写出 {OUT_AS_REPORTED}（{sample_rate} Hz {channels} 声道，自报格式）"),
        Err(e) => eprintln!("写 {OUT_AS_REPORTED} 失败: {e}"),
    }

    if channels != 1 {
        return;
    }
    // 样本一个都不用动：同一串 L R L R 换个头部声明就是立体声了
    match write_wav(OUT_AS_STEREO, pcm, sample_rate / 2, 2) {
        Ok(()) => println!(
            "已写出 {OUT_AS_STEREO}（{} Hz 立体声，重新解释）",
            sample_rate / 2
        ),
        Err(e) => eprintln!("写 {OUT_AS_STEREO} 失败: {e}"),
    }
    println!("\n两个都放一下，对的那个就是真实格式：");
    println!("  正常 → 该格式正确");
    println!("  变调（快放/慢放）→ 采样率错了，换另一个");
    println!("  嘶嘶声、金属感、左右怪异 → 声道解释错了，换另一个");
}

fn f32_to_i16(sample: f32) -> i16 {
    (sample.clamp(-1.0, 1.0) * i16::MAX as f32) as i16
}

/// 先确认到底有没有信号。全静音的话下面的对齐检测没有意义。
fn report_level(pcm: &[i16]) {
    let peak = pcm.iter().map(|s| s.unsigned_abs()).max().unwrap_or(0);
    let energy: f64 = pcm.iter().map(|s| (*s as f64).powi(2)).sum();
    let rms = (energy / pcm.len() as f64).sqrt();
    let full = i16::MAX as f64;
    println!(
        "  电平: 峰值 {peak} ({:.1} dBFS), RMS {rms:.0} ({:.1} dBFS)",
        20.0 * (peak as f64 / full).max(1e-9).log10(),
        20.0 * (rms / full).max(1e-9).log10()
    );
    if peak < 64 {
        println!("  <- 基本是静音，下面的对齐检测不可信，请让被控机出声后重跑");
    }
}

/// 判断这串样本到底是「96k 单声道」还是「48k 立体声换了个标签」。
/// 判据见模块头的表格：比滞后 1 和滞后 2 的自相关，哪个高说明哪种解释对。
fn report_interleave(pcm: &[i16], channels: usize) {
    if channels != 1 {
        println!("  设备报的是 {channels} 声道，交错假设不适用，跳过");
        return;
    }

    let lag1 = autocorrelation(pcm, 1);
    let lag2 = autocorrelation(pcm, 2);
    println!("  自相关: 滞后1 {lag1:.3}, 滞后2 {lag2:.3}");

    if lag1.abs() < 0.3 && lag2.abs() < 0.3 {
        println!("  <- 两个都接近 0，样本本身像是错位的垃圾（或者信号就是噪声）");
    } else if lag2 > lag1 + 0.05 {
        println!("  <- 隔一个比相邻更像，是 L R L R 交替：真实格式为 48k 立体声");
    } else {
        println!("  <- 相邻比隔一个更像，看着是货真价实的 96k 单声道");
    }
}

/// 滞后 `lag` 的自相关系数。分母用方差归一，这样不同信号强度之间能直接比。
fn autocorrelation(pcm: &[i16], lag: usize) -> f64 {
    if pcm.len() <= lag + 1 {
        return 0.0;
    }
    let mean = pcm.iter().map(|s| *s as f64).sum::<f64>() / pcm.len() as f64;
    let mut cov = 0.0;
    let mut var = 0.0;
    for i in 0..pcm.len() - lag {
        cov += (pcm[i] as f64 - mean) * (pcm[i + lag] as f64 - mean);
        var += (pcm[i] as f64 - mean).powi(2);
    }
    if var <= 0.0 { 0.0 } else { cov / var }
}

fn write_wav(path: &str, pcm: &[i16], sample_rate: u32, channels: u16) -> std::io::Result<()> {
    let mut out = BufWriter::new(File::create(path)?);
    let data_bytes = (pcm.len() * 2) as u32;
    let byte_rate = sample_rate * channels as u32 * 2;

    out.write_all(b"RIFF")?;
    out.write_all(&(36 + data_bytes).to_le_bytes())?;
    out.write_all(b"WAVEfmt ")?;
    out.write_all(&16u32.to_le_bytes())?; // fmt 块长度
    out.write_all(&1u16.to_le_bytes())?; // PCM
    out.write_all(&channels.to_le_bytes())?;
    out.write_all(&sample_rate.to_le_bytes())?;
    out.write_all(&byte_rate.to_le_bytes())?;
    out.write_all(&(channels * 2).to_le_bytes())?; // 每帧字节数
    out.write_all(&16u16.to_le_bytes())?; // 位深
    out.write_all(b"data")?;
    out.write_all(&data_bytes.to_le_bytes())?;
    for sample in pcm {
        out.write_all(&sample.to_le_bytes())?;
    }
    out.flush()
}
