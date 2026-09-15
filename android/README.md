# rust-kvm Android 客户端

Gradle 多模块工程骨架：`app`、`client`、`core-protocol`、`host`。

## 打开与构建

- 使用 Android Studio 打开 `android/` 目录
- 或在命令行：

```bash
cd android
./gradlew :app:assembleDebug
```

## 联调

联调需 Windows 主机端监听 `:8443`。
