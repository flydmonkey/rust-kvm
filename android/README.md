# rust-kvm Android 客户端

Gradle 多模块工程骨架：`app`、`client`、`core-protocol`、`host`。

## 打开与构建

- 使用 Android Studio 打开 `android/` 目录
- **JDK 17**（勿用 Android Studio 自带 JBR 25；构建前设置 `JAVA_HOME`）
- 若 Gradle 报 SDK 路径错误，设置 `ANDROID_HOME` 或在 `local.properties` 中配置 `sdk.dir`
- 命令行构建：

```bash
cd android
./gradlew :app:assembleDebug
```

## 联调

联调需 Windows 主机端监听 `:8443`。
