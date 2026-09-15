# Task 6 报告：连接页、最近服务器与 TOFU 对话框

## 实现

- 新增 Compose 连接页：地址规范化、HTTPS 客户端连通性校验、错误/加载状态和最近服务器入口。
- 复用 `SharedPrefsCertTrustStore`，将 `NeedsUserTrust` / `Mismatch` 转为挂起式用户确认对话框；Mismatch 同时展示旧、新 SHA-256 指纹和警告。
- 新增 `RecentServersStore`，按最近优先去重并通过 SharedPreferences 持久化，最多保留 8 条。
- 新增 `connect` → `control/{encodedBaseUrl}` 导航；控制页暂显示“控制页占位”。

## 测试与验证

- TDD RED：`RecentServersStoreTest` 首次运行因 `recentServersAfterAdding` 尚不存在而按预期失败。
- GREEN：`.\gradlew.bat :app:testDebugUnitTest` — BUILD SUCCESSFUL。
- 构建：`.\gradlew.bat :app:assembleDebug` — BUILD SUCCESSFUL。
- 真机/模拟器冒烟：SKIPPED；`adb devices` 未发现已连接设备。

## 注意事项

- 首次信任、再次无提示及证书替换 Mismatch 的端到端流程尚需在可用设备和 `:8443` 服务端上验证。
