# Task 8 报告：H.264（MediaCodec）

## 实现

- 新增 `AnnexBNalScanner`，识别三字节与四字节 Annex-B 起始码，并返回 NAL 类型及边界。
- 新增 `H264Decoder`：缓存 SPS/PPS，等首个 IDR 后以 `video/avc` 配置 `MediaCodec`，将 H.264 access unit 完整送入解码器并把输出渲染到 `Surface`。
- `VideoPipeline` 按 `mjpeg`/`h264` 分支；codec 或 `avcCodec` 变化时串行释放旧 H.264 解码器，管线结束时也会释放。
- H.264 输入使用阻塞式 input buffer 获取，不主动丢弃 P 帧。

## 测试与验证

- TDD RED：扫描器测试首次运行因 `AnnexBNalScanner` 尚不存在而按预期编译失败。
- 单元测试覆盖三/四字节起始码、前导垃圾、尾随空起始码、相邻起始码、NAL 类型掩码及无完整 NAL。
- `.\gradlew.bat :client:testDebugUnitTest :app:assembleDebug` — BUILD SUCCESSFUL。
- MediaCodec 真机/仪器测试：SKIPPED（当前没有可用 Android 设备）；MediaCodec 保持薄封装，纯字节扫描逻辑由 JVM 单测覆盖。
