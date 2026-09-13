//! JPEG → NV12。编码器要 NV12，采集卡给 MJPEG，中间这一步在这儿。
//!
//! 让 zune-jpeg 直出 YCbCr 而不是 RGB，省掉的是那个色彩矩阵——这是主要收益。
//! 但它给的是**交织的 4:4:4**（每像素 3 字节，色度已升采样），不是平面，所以转
//! NV12 仍要自己做：Y 取每第 3 个字节，UV 按 2x2 方块取平均再交织。
//!
//! 1080p 一帧读 6.2 MB、写 3.1 MB，30fps 约 280 MB/s。对 DDR 无所谓，但这也是
//! 为什么不值得再为「省一次拷贝」去折腾硬件解码 MFT 的零拷贝路径。

use anyhow::{Result, bail};
use zune_core::bytestream::ZCursor;
use zune_core::colorspace::ColorSpace;
use zune_core::options::DecoderOptions;
use zune_jpeg::JpegDecoder;

/// NV12：Y 平面在前，紧跟 U/V 交织的半尺寸平面。
///
/// 复用同一个实例是为了不在每帧都分配三兆——那会让分配器成为热点。
pub struct Nv12Frame {
    pub width: u32,
    pub height: u32,
    pub data: Vec<u8>,
    /// zune-jpeg 的输出缓冲，同样为了复用而留在这儿
    ycbcr: Vec<u8>,
}

impl Nv12Frame {
    pub fn new(width: u32, height: u32) -> Self {
        let mut frame = Self {
            width: 0,
            height: 0,
            data: Vec::new(),
            ycbcr: Vec::new(),
        };
        frame.resize(width, height);
        frame
    }

    fn resize(&mut self, width: u32, height: u32) {
        if self.width == width && self.height == height {
            return;
        }
        self.width = width;
        self.height = height;
        let y = (width * height) as usize;
        // 色度平面按向上取整的半尺寸，奇数分辨率也不越界
        let uv = (width.div_ceil(2) * height.div_ceil(2) * 2) as usize;
        self.data.clear();
        self.data.resize(y + uv, 0);
        self.ycbcr.clear();
        self.ycbcr.resize(y * 3, 0);
    }
}

/// 交织 YCbCr 4:4:4 → NV12。
pub fn from_interleaved_ycbcr(
    src: &[u8],
    width: u32,
    height: u32,
    out: &mut Nv12Frame,
) -> Result<()> {
    let (w, h) = (width as usize, height as usize);
    if src.len() < w * h * 3 {
        bail!(
            "YCbCr 数据只有 {} 字节，{width}x{height} 需要 {}",
            src.len(),
            w * h * 3
        );
    }
    out.resize(width, height);

    let (y_plane, uv_plane) = out.data.split_at_mut(w * h);
    for (i, y) in y_plane.iter_mut().enumerate() {
        *y = src[i * 3];
    }

    let half_w = width.div_ceil(2) as usize;
    for by in 0..height.div_ceil(2) as usize {
        for bx in 0..half_w {
            // 边界上的方块可能只有一两个像素，取实际存在的那些平均
            let mut cb = 0u32;
            let mut cr = 0u32;
            let mut n = 0u32;
            for dy in 0..2 {
                let y = by * 2 + dy;
                if y >= h {
                    continue;
                }
                for dx in 0..2 {
                    let x = bx * 2 + dx;
                    if x >= w {
                        continue;
                    }
                    let p = (y * w + x) * 3;
                    cb += src[p + 1] as u32;
                    cr += src[p + 2] as u32;
                    n += 1;
                }
            }
            let at = (by * half_w + bx) * 2;
            uv_plane[at] = (cb / n) as u8;
            uv_plane[at + 1] = (cr / n) as u8;
        }
    }
    Ok(())
}

/// 解一帧 MJPEG 并转成 NV12。
pub fn decode_jpeg_to_nv12(jpeg: &[u8], out: &mut Nv12Frame) -> Result<()> {
    let options = DecoderOptions::new_fast().jpeg_set_out_colorspace(ColorSpace::YCbCr);
    let mut decoder = JpegDecoder::new_with_options(ZCursor::new(jpeg), options);
    decoder
        .decode_headers()
        .map_err(|e| anyhow::anyhow!("解 JPEG 头失败: {e:?}"))?;
    let (width, height) = decoder
        .dimensions()
        .ok_or_else(|| anyhow::anyhow!("JPEG 头里没有尺寸"))?;

    out.resize(width as u32, height as u32);
    // 解码器要的缓冲大小以它自己报的为准：4:4:4 应当正好是 w*h*3，但别赌
    let needed = decoder
        .output_buffer_size()
        .ok_or_else(|| anyhow::anyhow!("解码器说不出要多大缓冲"))?;
    // 借出来单独用，免得和 out 的可变借用打架
    let mut ycbcr = std::mem::take(&mut out.ycbcr);
    if ycbcr.len() < needed {
        ycbcr.resize(needed, 0);
    }

    let outcome = match decoder.decode_into(&mut ycbcr) {
        Ok(()) => from_interleaved_ycbcr(&ycbcr, width as u32, height as u32, out),
        Err(e) => Err(anyhow::anyhow!("解 JPEG 失败: {e:?}")),
    };
    out.ycbcr = ycbcr;
    outcome
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 造一张 2x2 的交织 YCbCr：Y 各不相同，色度四个角不同值，这样能同时验
    /// 「Y 逐像素照搬」和「色度按 2x2 取平均」。
    #[test]
    fn 亮度逐像素照搬色度按方块取平均() {
        // (Y, Cb, Cr) x 4，行优先
        let src: Vec<u8> = vec![
            10, 100, 200, 20, 108, 208, //
            30, 116, 216, 40, 124, 224,
        ];
        let mut out = Nv12Frame::new(2, 2);
        from_interleaved_ycbcr(&src, 2, 2, &mut out).unwrap();

        assert_eq!(&out.data[..4], &[10, 20, 30, 40], "Y 平面应当逐像素照搬");
        // Cb 平均 = (100+108+116+124)/4 = 112，Cr = (200+208+216+224)/4 = 212
        assert_eq!(&out.data[4..6], &[112, 212], "UV 应当是 2x2 均值且 U 在前");
    }

    /// 奇数尺寸会让 2x2 方块在右边/下边越界。KVM 的分辨率基本都是偶数，但越界读
    /// 是内存安全问题，不能靠「基本上」。
    #[test]
    fn 奇数尺寸不越界() {
        let src = vec![128u8; 3 * 3 * 3];
        let mut out = Nv12Frame::new(3, 3);
        from_interleaved_ycbcr(&src, 3, 3, &mut out).unwrap();
        assert_eq!(out.data.len(), 9 + 2 * 2 * 2, "UV 平面按向上取整的半尺寸算");
    }

    #[test]
    fn 输入太短要报错而不是崩() {
        let src = vec![0u8; 5];
        let mut out = Nv12Frame::new(2, 2);
        assert!(from_interleaved_ycbcr(&src, 2, 2, &mut out).is_err());
    }

    /// 复用同一个 Nv12Frame 换尺寸时缓冲区必须跟着重算。不重算的话新尺寸会写进
    /// 旧大小的缓冲里——要么越界，要么留下上一帧的残留。
    #[test]
    fn 换尺寸时缓冲重算() {
        let mut out = Nv12Frame::new(2, 2);
        let src = vec![128u8; 4 * 4 * 3];
        from_interleaved_ycbcr(&src, 4, 4, &mut out).unwrap();
        assert_eq!(out.width, 4);
        assert_eq!(out.data.len(), 16 + 8);
    }
}
