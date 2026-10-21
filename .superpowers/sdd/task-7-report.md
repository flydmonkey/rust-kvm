# Task 7 报告：MJPEG 显示

## 实现

- 新增 `MjpegDecoder`，通过 `BitmapFactory.decodeByteArray` 解码 MJPEG 帧。
- 新增 `VideoPipeline`，在后台协程消费视频 Text/Binary 流；仅在 codec 为 `mjpeg` 时解码并等比居中绘制到 `Surface`，`Idle` 不刷新，codec 切换会更新解码状态。
- 新增 `ControlScreen`，使用 `AndroidView(SurfaceView)` 管理 Surface 生命周期，进入页面后用已持久化的 TOFU 信任记录创建并连接 `KvmSession`，页面离开或 Surface 销毁时关闭会话。
- 控制页重连若意外收到首次信任或证书变更请求，会提示返回连接页重新确认。

## 测试与验证

- TDD RED：`MjpegDecoderTest` 首次运行因 `MjpegDecoder` 尚不存在而按预期编译失败。
- GREEN：1×1 JPEG 常量成功解码为 1×1 Bitmap。
- `.\gradlew.bat :client:testDebugUnitTest :app:assembleDebug` — BUILD SUCCESSFUL。
- 局域网 MJPEG 真机冒烟：SKIPPED（当前没有可用服务端）。
