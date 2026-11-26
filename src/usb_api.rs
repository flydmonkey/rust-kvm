//! `/api/usb*`：本机 USB 口上的光驱和 U 盘。查状态、挂盘弹盘、上传删除镜像。

use std::collections::HashSet;
use std::path::{Path as FsPath, PathBuf};
use std::sync::{Arc, Mutex};

use anyhow::{Context, Result};
use axum::Json;
use axum::Router;
use axum::body::Body;
use axum::extract::{DefaultBodyLimit, Path, State};
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post, put};
use futures_util::StreamExt;
use serde::{Deserialize, Serialize};
use tokio::io::AsyncWriteExt;
use tracing::{info, warn};

use crate::media::{self, ImageDir, ImageInfo};
use crate::usb_gadget::{Availability, Slot, SlotState, UsbGadget};

pub struct UsbService {
    gadget: Option<Arc<UsbGadget>>,
    reason: Option<String>,
    images: ImageDir,
    uploading: Mutex<HashSet<String>>,
}

impl UsbService {
    pub fn new(availability: &Availability, images: ImageDir) -> Arc<Self> {
        let (gadget, reason) = match availability {
            Availability::Ready(gadget) => (Some(gadget.clone()), None),
            Availability::Unavailable(reason) => (None, Some(reason.clone())),
            Availability::Unsupported => (None, None),
        };
        Arc::new(Self { gadget, reason, images, uploading: Mutex::new(HashSet::new()) })
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Overview {
    available: bool,
    reason: Option<String>,
    slots: Option<Slots>,
    images: Vec<ImageInfo>,
}

#[derive(Serialize)]
struct Slots {
    cdrom: SlotView,
    disk: SlotView,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct SlotView {
    image: Option<String>,
    read_only: bool,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct MountRequest {
    image: String,
    #[serde(default)]
    read_only: bool,
}

pub fn router<S: Clone + Send + Sync + 'static>(service: Arc<UsbService>) -> Router<S> {
    Router::new()
        .route("/api/usb", get(overview))
        .route("/api/usb/{slot}/mount", post(mount))
        .route("/api/usb/{slot}/eject", post(eject))
        // ISO 动辄几个 GB，默认的 2 MB 上限在这里没有意义
        .route(
            "/api/usb/images/{name}",
            put(upload).delete(delete_image).layer(DefaultBodyLimit::disable()),
        )
        .with_state(service)
}

impl UsbService {
    fn view(&self, state: SlotState) -> SlotView {
        SlotView {
            image: state.image.map(|path| self.images.name_of(&path)),
            read_only: state.read_only,
        }
    }

    fn is_mounted(&self, path: &FsPath) -> bool {
        self.gadget.as_ref().is_some_and(|gadget| {
            Slot::ALL.iter().any(|slot| gadget.slot(*slot).image.as_deref() == Some(path))
        })
    }

    fn unavailable(&self) -> Response {
        let reason = self.reason.as_deref().unwrap_or("这台宿主机不支持本机 USB 口");
        (StatusCode::SERVICE_UNAVAILABLE, reason.to_string()).into_response()
    }
}

async fn overview(State(service): State<Arc<UsbService>>) -> Json<Overview> {
    let slots = service.gadget.as_ref().map(|gadget| Slots {
        cdrom: service.view(gadget.slot(Slot::Cdrom)),
        disk: service.view(gadget.slot(Slot::Disk)),
    });
    Json(Overview {
        available: service.gadget.is_some(),
        reason: service.reason.clone(),
        slots,
        // 目录没建（不可用的板子上就不建）时就是空列表
        images: service.images.list().unwrap_or_default(),
    })
}

async fn mount(
    State(service): State<Arc<UsbService>>,
    Path(slot): Path<Slot>,
    Json(request): Json<MountRequest>,
) -> Response {
    let Some(gadget) = service.gadget.clone() else {
        return service.unavailable();
    };
    let path = match service.images.path(&request.image) {
        Ok(path) => path,
        Err(message) => return (StatusCode::BAD_REQUEST, message).into_response(),
    };
    if slot == Slot::Cdrom && !media::is_iso(&request.image) {
        return (StatusCode::BAD_REQUEST, "光驱只能挂 .iso").into_response();
    }
    if !path.is_file() {
        return (StatusCode::NOT_FOUND, format!("镜像目录里没有 {}", request.image)).into_response();
    }
    if service.uploading.lock().expect("上传表锁").contains(&request.image) {
        return (StatusCode::CONFLICT, "这个镜像正在上传，传完再挂").into_response();
    }
    if gadget.slot(slot.other()).image.as_deref() == Some(path.as_path()) {
        return (StatusCode::CONFLICT, "这个镜像已经挂在另一个槽上了").into_response();
    }
    let read_only = request.read_only;
    let mounted = tokio::task::spawn_blocking(move || gadget.mount(slot, &path, read_only)).await;
    match mounted {
        Ok(Ok(())) => {
            info!("{slot:?} 挂上了 {}", request.image);
            overview(State(service)).await.into_response()
        }
        Ok(Err(error)) => (StatusCode::INTERNAL_SERVER_ERROR, format!("{error:#}")).into_response(),
        Err(error) => {
            (StatusCode::INTERNAL_SERVER_ERROR, format!("挂盘线程异常退出：{error}")).into_response()
        }
    }
}

async fn eject(State(service): State<Arc<UsbService>>, Path(slot): Path<Slot>) -> Response {
    let Some(gadget) = service.gadget.clone() else {
        return service.unavailable();
    };
    match tokio::task::spawn_blocking(move || gadget.eject(slot)).await {
        Ok(Ok(())) => {
            info!("{slot:?} 已弹出");
            overview(State(service)).await.into_response()
        }
        Ok(Err(error)) => (StatusCode::INTERNAL_SERVER_ERROR, format!("{error:#}")).into_response(),
        Err(error) => {
            (StatusCode::INTERNAL_SERVER_ERROR, format!("弹盘线程异常退出：{error}")).into_response()
        }
    }
}

/// 占着「正在上传」这个名字，并且在没传完时删掉 `.part`。用 Drop 而不是在出错分支里删：
/// 浏览器中途关掉时，处理函数的 future 直接被丢弃，出错分支根本走不到。
struct UploadGuard {
    service: Arc<UsbService>,
    name: String,
    part: PathBuf,
    done: bool,
}

impl Drop for UploadGuard {
    fn drop(&mut self) {
        if !self.done {
            let _ = std::fs::remove_file(&self.part);
        }
        self.service.uploading.lock().expect("上传表锁").remove(&self.name);
    }
}

async fn upload(
    State(service): State<Arc<UsbService>>,
    Path(name): Path<String>,
    body: Body,
) -> Response {
    if service.gadget.is_none() {
        return service.unavailable();
    }
    let (target, part) = match (service.images.path(&name), service.images.part_path(&name)) {
        (Ok(target), Ok(part)) => (target, part),
        (Err(message), _) | (_, Err(message)) => {
            return (StatusCode::BAD_REQUEST, message).into_response();
        }
    };
    if service.is_mounted(&target) {
        return (StatusCode::CONFLICT, "这个镜像正挂着，先弹出再覆盖").into_response();
    }
    if !service.uploading.lock().expect("上传表锁").insert(name.clone()) {
        return (StatusCode::CONFLICT, "同名文件正在上传").into_response();
    }
    let mut guard = UploadGuard { service: service.clone(), name: name.clone(), part: part.clone(), done: false };
    match write_upload(body, &part, &target).await {
        Ok(size) => {
            guard.done = true;
            drop(guard);
            info!("上传了 {name}（{size} 字节）");
            overview(State(service)).await.into_response()
        }
        Err(error) => {
            warn!("上传 {name} 失败：{error:#}");
            (StatusCode::INTERNAL_SERVER_ERROR, format!("{error:#}")).into_response()
        }
    }
}

/// 先写 `.part`、落盘、再改名：列表里永远不会出现一个只传了一半的镜像。
async fn write_upload(body: Body, part: &FsPath, target: &FsPath) -> Result<u64> {
    let mut file = tokio::fs::File::create(part)
        .await
        .with_context(|| format!("建 {} 失败", part.display()))?;
    let mut stream = body.into_data_stream();
    let mut size = 0u64;
    while let Some(chunk) = stream.next().await {
        let chunk = chunk.context("上传中断")?;
        size += chunk.len() as u64;
        file.write_all(&chunk).await.context("写镜像失败")?;
    }
    file.sync_all().await.context("镜像落盘失败")?;
    drop(file);
    tokio::fs::rename(part, target)
        .await
        .with_context(|| format!("改名成 {} 失败", target.display()))?;
    Ok(size)
}

async fn delete_image(State(service): State<Arc<UsbService>>, Path(name): Path<String>) -> Response {
    if service.gadget.is_none() {
        return service.unavailable();
    }
    let target = match service.images.path(&name) {
        Ok(target) => target,
        Err(message) => return (StatusCode::BAD_REQUEST, message).into_response(),
    };
    if service.is_mounted(&target) {
        return (StatusCode::CONFLICT, "这个镜像正挂着，先弹出再删").into_response();
    }
    match tokio::fs::remove_file(&target).await {
        Ok(()) => {
            info!("删了镜像 {name}");
            overview(State(service)).await.into_response()
        }
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            (StatusCode::NOT_FOUND, format!("镜像目录里没有 {name}")).into_response()
        }
        Err(error) => (StatusCode::INTERNAL_SERVER_ERROR, format!("删除失败：{error}")).into_response(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::usb_gadget::test_support;

    struct Setup {
        _fixture: test_support::Fixture,
        service: Arc<UsbService>,
        images: PathBuf,
    }

    fn setup() -> Setup {
        let (fixture, gadget) = test_support::gadget();
        let images = fixture.temp.path().join("images");
        std::fs::create_dir_all(&images).unwrap();
        std::fs::write(images.join("win.iso"), "iso").unwrap();
        std::fs::write(images.join("drivers.img"), "img").unwrap();
        let service = UsbService::new(&Availability::Ready(gadget), ImageDir::new(images.clone()));
        Setup { _fixture: fixture, service, images }
    }

    async fn json(response: Response) -> serde_json::Value {
        let bytes = axum::body::to_bytes(response.into_body(), usize::MAX).await.unwrap();
        serde_json::from_slice(&bytes).unwrap()
    }

    fn request(image: &str, read_only: bool) -> Json<MountRequest> {
        Json(MountRequest { image: image.into(), read_only })
    }

    #[tokio::test]
    async fn 挂上之后概览里是镜像名() {
        let s = setup();
        let response = mount(State(s.service.clone()), Path(Slot::Cdrom), request("win.iso", false)).await;
        assert_eq!(response.status(), StatusCode::OK);
        let body = json(response).await;
        assert_eq!(body["slots"]["cdrom"]["image"], "win.iso");
        assert_eq!(body["slots"]["cdrom"]["readOnly"], true);
        assert_eq!(body["slots"]["disk"]["image"], serde_json::Value::Null);
        assert_eq!(body["images"].as_array().unwrap().len(), 2);
    }

    #[tokio::test]
    async fn 光驱不收_img() {
        let s = setup();
        let response = mount(State(s.service), Path(Slot::Cdrom), request("drivers.img", false)).await;
        assert_eq!(response.status(), StatusCode::BAD_REQUEST);
    }

    #[tokio::test]
    async fn 不存在的镜像回_404() {
        let s = setup();
        let response = mount(State(s.service), Path(Slot::Disk), request("nope.img", false)).await;
        assert_eq!(response.status(), StatusCode::NOT_FOUND);
    }

    #[tokio::test]
    async fn 同一个镜像不能挂两个槽() {
        let s = setup();
        mount(State(s.service.clone()), Path(Slot::Cdrom), request("win.iso", false)).await;
        let response = mount(State(s.service), Path(Slot::Disk), request("win.iso", false)).await;
        assert_eq!(response.status(), StatusCode::CONFLICT);
    }

    #[tokio::test]
    async fn 弹出后槽是空的() {
        let s = setup();
        mount(State(s.service.clone()), Path(Slot::Disk), request("drivers.img", true)).await;
        let body = json(eject(State(s.service), Path(Slot::Disk)).await).await;
        assert_eq!(body["slots"]["disk"]["image"], serde_json::Value::Null);
    }

    #[tokio::test]
    async fn 上传后改名_不留_part() {
        let s = setup();
        let response = upload(State(s.service), Path("new.img".into()), Body::from("hello")).await;
        assert_eq!(response.status(), StatusCode::OK);
        assert_eq!(std::fs::read_to_string(s.images.join("new.img")).unwrap(), "hello");
        assert!(!s.images.join("new.img.part").exists());
    }

    #[tokio::test]
    async fn 非法文件名回_400() {
        let s = setup();
        let response = upload(State(s.service), Path("../x.iso".into()), Body::from("x")).await;
        assert_eq!(response.status(), StatusCode::BAD_REQUEST);
    }

    #[tokio::test]
    async fn 正挂着的镜像不能删也不能覆盖() {
        let s = setup();
        mount(State(s.service.clone()), Path(Slot::Disk), request("drivers.img", false)).await;
        let response = delete_image(State(s.service.clone()), Path("drivers.img".into())).await;
        assert_eq!(response.status(), StatusCode::CONFLICT);
        let response = upload(State(s.service), Path("drivers.img".into()), Body::from("x")).await;
        assert_eq!(response.status(), StatusCode::CONFLICT);
        assert_eq!(std::fs::read_to_string(s.images.join("drivers.img")).unwrap(), "img");
    }

    #[tokio::test]
    async fn 删除镜像() {
        let s = setup();
        let response = delete_image(State(s.service), Path("win.iso".into())).await;
        assert_eq!(response.status(), StatusCode::OK);
        assert!(!s.images.join("win.iso").exists());
    }

    #[tokio::test]
    async fn 不可用时说明原因() {
        let temp = tempfile::tempdir().unwrap();
        let service = UsbService::new(
            &Availability::Unavailable("没有 USB 设备控制器".into()),
            ImageDir::new(temp.path().to_path_buf()),
        );
        let body = json(overview(State(service.clone())).await.into_response()).await;
        assert_eq!(body["available"], false);
        assert_eq!(body["reason"], "没有 USB 设备控制器");
        let response = mount(State(service), Path(Slot::Disk), request("a.img", false)).await;
        assert_eq!(response.status(), StatusCode::SERVICE_UNAVAILABLE);
    }
}