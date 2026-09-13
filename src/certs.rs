//! 自签名证书。
//!
//! HTTPS 在这里不是为了安全，而是硬性依赖：Keyboard Lock、剪贴板读取、
//! Pointer Lock 这些 API 都只在 secure context 下可用，而局域网 IP 拿不到
//! 正式证书，所以自己签一张。
//!
//! 证书会缓存到磁盘，但如果本机 IP 变了（换网络、插拔网卡）就得重签，
//! 否则浏览器会因为 SAN 不匹配直接拒连，而且报错信息很难看懂。所以把签发
//! 时用的名字列表一起存下来做比对。

use std::fs;
use std::net::IpAddr;
use std::path::Path;

use anyhow::{Context, Result};
use tracing::info;

pub struct Cert {
    pub cert_pem: Vec<u8>,
    pub key_pem: Vec<u8>,
    /// 证书覆盖的地址，用来在日志里打出可访问的 URL。
    pub names: Vec<String>,
}

pub fn load_or_create(dir: &Path) -> Result<Cert> {
    let cert_path = dir.join("cert.pem");
    let key_path = dir.join("key.pem");
    let names_path = dir.join("sans.txt");

    let names = subject_alt_names();
    let fingerprint = names.join("\n");

    if let (Ok(cert_pem), Ok(key_pem), Ok(saved)) = (
        fs::read(&cert_path),
        fs::read(&key_path),
        fs::read_to_string(&names_path),
    ) {
        if saved == fingerprint {
            info!("复用已有证书 {}", cert_path.display());
            return Ok(Cert {
                cert_pem,
                key_pem,
                names,
            });
        }
        info!("本机地址已变化，重新签发证书");
    }

    let generated = rcgen::generate_simple_self_signed(names.clone())
        .context("生成自签名证书失败")?;
    let cert_pem = generated.cert.pem().into_bytes();
    let key_pem = generated.signing_key.serialize_pem().into_bytes();

    fs::create_dir_all(dir).with_context(|| format!("创建 {} 失败", dir.display()))?;
    fs::write(&cert_path, &cert_pem).context("写入证书失败")?;
    fs::write(&key_path, &key_pem).context("写入私钥失败")?;
    fs::write(&names_path, &fingerprint).context("写入 SAN 列表失败")?;
    info!("已签发新证书 {}", cert_path.display());

    Ok(Cert {
        cert_pem,
        key_pem,
        names,
    })
}

/// 证书要覆盖所有可能被用来访问的地址：localhost、本机主机名、以及全部
/// 非回环 IPv4。少一个，从那个地址访问时浏览器就会报 SAN 不匹配。
fn subject_alt_names() -> Vec<String> {
    let mut names = vec!["localhost".to_string(), "127.0.0.1".to_string()];

    if let Ok(hostname) = std::env::var("COMPUTERNAME") {
        let hostname = hostname.trim().to_lowercase();
        if !hostname.is_empty() {
            names.push(hostname);
        }
    }

    if let Ok(interfaces) = local_ip_address::list_afinet_netifas() {
        for (_, ip) in interfaces {
            if let IpAddr::V4(v4) = ip
                && !v4.is_loopback()
                && !v4.is_link_local()
            {
                names.push(v4.to_string());
            }
        }
    }

    names.sort();
    names.dedup();
    names
}
