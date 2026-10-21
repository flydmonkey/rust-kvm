# Task 9 实施报告：音频播放

## 完成内容

- 新增 `PcmPlayer`：按 `s16le` 格式头创建/重建流式 `AudioTrack`，支持单/双声道、30ms 固定短缓冲、静音丢帧与资源释放。
- `ControlScreen` 并行消费音频格式头和 PCM 数据，提供默认开启的静音切换按钮，并展示服务端音频错误。
- 音频 WebSocket 关闭不再终止视频/串口会话；`audio.enabled=false` 的错误消息可显示且不会导致控制页退出。
- 新增缓冲计算和音频错误解析单元测试。

## 验证

- `JAVA_HOME=JDK17; .\gradlew.bat :client:testDebugUnitTest :app:assembleDebug`：通过。
- 实机听声及窄带切换联调：按要求跳过。
