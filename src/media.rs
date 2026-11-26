//! 镜像目录：资源目录下的 `images/`。光驱挂 `.iso`，U 盘挂 `.iso` 或 `.img`。
//!
//! 不做成配置项：安卓客户端保存配置时会丢掉它不认识的字段。想放到别的盘上，
//! 把 `images` 做成软链接。

use std::io;
use std::path::{Path, PathBuf};

use serde::Serialize;

pub const DIR_NAME: &str = "images";
const PART_SUFFIX: &str = ".part";

/// 只允许一层普通文件名：网页传上来的名字会直接拼进路径，带 `/` 或 `..` 就能写到目录外面。
pub fn validate_name(name: &str) -> Result<(), &'static str> {
    if name.is_empty() || name.len() > 200 {
        return Err("文件名为空或太长");
    }
    if name.starts_with('.') || name.contains(['/', '\\']) || name.chars().any(char::is_control) {
        return Err("文件名只能是一层普通名字，不能带路径、控制字符，也不能以 . 开头");
    }
    let lower = name.to_ascii_lowercase();
    if !(lower.ends_with(".iso") || lower.ends_with(".img")) {
        return Err("只支持 .iso 和 .img");
    }
    Ok(())
}

pub fn is_iso(name: &str) -> bool {
    name.to_ascii_lowercase().ends_with(".iso")
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct ImageInfo {
    pub name: String,
    pub size: u64,
}

pub struct ImageDir {
    root: PathBuf,
}

impl ImageDir {
    pub fn new(root: PathBuf) -> Self {
        Self { root }
    }

    pub fn path(&self, name: &str) -> Result<PathBuf, &'static str> {
        validate_name(name)?;
        Ok(self.root.join(name))
    }

    pub fn part_path(&self, name: &str) -> Result<PathBuf, &'static str> {
        validate_name(name)?;
        Ok(self.root.join(format!("{name}{PART_SUFFIX}")))
    }

    /// 跟着软链接看是不是普通文件：用户可能把大镜像放在别处、在这里链一个过来。
    pub fn list(&self) -> io::Result<Vec<ImageInfo>> {
        let mut images = Vec::new();
        for entry in std::fs::read_dir(&self.root)? {
            let entry = entry?;
            let name = entry.file_name().to_string_lossy().into_owned();
            if validate_name(&name).is_err() {
                continue;
            }
            let Ok(meta) = std::fs::metadata(entry.path()) else {
                continue;
            };
            if meta.is_file() {
                images.push(ImageInfo { name, size: meta.len() });
            }
        }
        images.sort_by(|a, b| a.name.cmp(&b.name));
        Ok(images)
    }

    /// 不在镜像目录里的（手动往 configfs 写的）原样显示全路径。
    pub fn name_of(&self, image: &Path) -> String {
        match (image.parent(), image.file_name()) {
            (Some(parent), Some(name)) if parent == self.root => name.to_string_lossy().into_owned(),
            _ => image.display().to_string(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn 合法的文件名() {
        for name in ["win11.iso", "Drivers.IMG", "ubuntu 24.04.iso", "驱动盘.img"] {
            assert_eq!(validate_name(name), Ok(()), "{name}");
        }
    }

    #[test]
    fn 不合法的文件名() {
        for name in ["", "../x.iso", "a/b.iso", "a\\b.iso", ".hidden.iso", "x.exe", "x.iso.part", "a\nb.iso", "a\tb.iso", "a\u{7f}b.iso", "a\0b.iso", "a\u{85}b.iso"] {
            assert!(validate_name(name).is_err(), "{name}");
        }
    }

    #[test]
    fn 只列镜像并按名字排序() {
        let temp = tempfile::tempdir().unwrap();
        for name in ["b.iso", "a.img", "c.txt", "d.iso.part"] {
            std::fs::write(temp.path().join(name), "12345").unwrap();
        }
        std::fs::create_dir(temp.path().join("e.iso")).unwrap();
        let images = ImageDir::new(temp.path().to_path_buf()).list().unwrap();
        assert_eq!(
            images,
            vec![
                ImageInfo { name: "a.img".into(), size: 5 },
                ImageInfo { name: "b.iso".into(), size: 5 },
            ]
        );
    }

    #[test]
    fn 目录不存在时列表为错() {
        let temp = tempfile::tempdir().unwrap();
        assert!(ImageDir::new(temp.path().join("missing")).list().is_err());
    }

    #[test]
    fn 槽里的路径换回镜像名() {
        let dir = ImageDir::new(PathBuf::from("/srv/kvm/images"));
        assert_eq!(dir.name_of(Path::new("/srv/kvm/images/win.iso")), "win.iso");
        assert_eq!(dir.name_of(Path::new("/mnt/other/x.img")), "/mnt/other/x.img");
    }
}
