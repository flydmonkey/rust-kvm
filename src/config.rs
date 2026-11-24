//! 持久化配置：选哪张采集卡、哪个音频设备、哪个串口。
//!
//! 存成 `config.json` 而不是计划里写的 TOML，因为 `serde_json` 本来就在依赖里，
//! 而配置的主要编辑入口是设置页而非手改文件，TOML 能写注释这个优势用不上。
//!
//! 字段一律用 camelCase：同一个结构既是文件格式也是 `/api/config` 的 JSON，
//! 网页那边直接用，不需要两套命名之间来回映射。

use std::path::{Path, PathBuf};
use std::sync::Mutex;

use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};
use tracing::{info, warn};

/// `serde(default)` 铺在每一层：老版本的配置文件缺字段时补默认值而不是解析失败。
/// 配置文件比程序活得久，加字段是常事。
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct Config {
    pub video: VideoSettings,
    pub audio: AudioSettings,
    pub serial: SerialSettings,
    /// ATX 电源继电器，`None`（JSON 里是 `null`）表示没接
    pub atx: Option<AtxSettings>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct VideoSettings {
    /// 设备名里包含这段文字就算命中；留空或找不到就用第一个设备。
    /// 用「包含」而不是精确相等，是因为设置页存的是枚举到的完整名字，
    /// 而手改配置的人更愿意只写个关键词。
    pub device: String,
    pub width: u32,
    pub height: u32,
    pub fps: u32,
    /// `"mjpeg"` 或 `"h264"`。h264 省带宽（静止画面能到十分之一），代价是多一层
    /// 编解码和宿主机一成 CPU；局域网用 mjpeg 延迟最低。
    pub codec: String,
    /// 只对 h264 有意义。固定码率，不做自适应（理由见低带宽模式的设计文档）。
    pub bitrate_kbps: u32,
}

impl Default for VideoSettings {
    fn default() -> Self {
        Self {
            device: "USB Video".to_string(),
            width: 1920,
            height: 1080,
            fps: 30,
            // 默认 mjpeg：日常是局域网，低带宽是出门时才开的
            codec: "mjpeg".to_string(),
            bitrate_kbps: 4000,
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct AudioSettings {
    pub device: String,
    /// 关掉之后 `/ws/audio` 直接回一条错误，采集根本不会打开。
    /// 和客户端那个声音开关是两回事：这个是「服务端允不允许」。
    pub enabled: bool,
    /// 单声道 24 kHz，带宽降到四分之一（1.5 Mbps → 384 kbps）。
    ///
    /// 只对 48 kHz 的源生效。被控机的系统声单声道完全够用，而在 5 Mbps 的
    /// 预算里裸 PCM 那 1.5 Mbps 占了三成——省下来的是 H.264 之外最大的一块。
    pub narrowband: bool,
}

impl Default for AudioSettings {
    fn default() -> Self {
        Self {
            device: "Digital Audio Interface".to_string(),
            enabled: true,
            // 和 codec 同一个道理：日常是局域网，窄带是出门时才开的
            narrowband: false,
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct SerialSettings {
    pub port: String,
    /// 只是给浏览器的首选值。实际波特率仍由 `ch9329.js` 探测决定——
    /// 芯片上写的是多少只有问过才知道，这里填对了只是省掉一次试错。
    pub baud_rate: u32,
    /// `absolute` 或 `relative`
    pub mouse_mode: String,
}

impl Default for SerialSettings {
    fn default() -> Self {
        Self {
            port: String::new(),
            baud_rate: 115200,
            mouse_mode: "absolute".to_string(),
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct AtxSettings {
    /// 继电器的串口。和 CH9329 多半都是 CH340，靠口名区分，不靠 VID/PID
    pub port: String,
    /// 接电源键的那一路，1–4
    pub channel: u8,
    /// 接复位键的那一路，没接是 `None`；不能和电源键同一路
    pub reset_channel: Option<u8>,
}

impl Default for AtxSettings {
    fn default() -> Self {
        Self {
            port: String::new(),
            channel: 1,
            reset_channel: None,
        }
    }
}

pub struct ConfigStore {
    path: PathBuf,
    current: Mutex<Config>,
}

impl ConfigStore {
    /// 读不到或读坏了都退回默认值并继续跑。配置文件损坏不该让整个服务起不来——
    /// 那会让人连设置页都打不开，也就没法把它改回去。
    pub fn load(path: &Path) -> Self {
        let current = match std::fs::read_to_string(path) {
            Ok(text) => match serde_json::from_str::<Config>(&text) {
                Ok(config) => {
                    info!("已读取配置 {}", path.display());
                    config
                }
                Err(e) => {
                    warn!("配置 {} 解析失败，改用默认值: {e}", path.display());
                    Config::default()
                }
            },
            Err(_) => {
                info!("没有 {}，使用默认配置", path.display());
                Config::default()
            }
        };

        Self {
            path: path.to_path_buf(),
            current: Mutex::new(current),
        }
    }

    pub fn get(&self) -> Config {
        self.current.lock().expect("config 锁").clone()
    }

    /// 先落盘再更新内存：写失败时内存里还是旧值，和磁盘保持一致，
    /// 不会出现「页面显示保存成功、重启后变回去」这种最难查的状态。
    pub fn save(&self, config: Config) -> Result<()> {
        let text = serde_json::to_string_pretty(&config).context("序列化配置失败")?;
        std::fs::write(&self.path, text)
            .with_context(|| format!("写配置 {} 失败", self.path.display()))?;
        *self.current.lock().expect("config 锁") = config;
        info!("配置已保存到 {}", self.path.display());
        Ok(())
    }
}
