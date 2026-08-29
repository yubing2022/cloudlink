# CloudLink Android Client

> Native Android app for controlling HA devices via cloud

## 🛠️ 开发环境

- Android Studio Hedgehog (2023.1.1) 或更新
- JDK 17
- Android SDK 26+（minSdk）
- Android SDK 35（compileSdk）

## 🚀 打开工程

```bash
# 用 Android Studio 打开 android/ 目录
open -a "Android Studio" /Users/yourname/cloudlink/android
```

## 🔧 配置后端地址

`app/src/main/java/com/yourname/cloudlink/data/api/RetrofitClient.kt`：

```kotlin
.baseUrl("https://api.your-domain.com/")  // 改成你的
```

`DeviceWebSocket.kt`：

```kotlin
.url("wss://api.your-domain.com/ws/client?token=$token")
```

## 📦 构建 APK

```bash
# Debug
./gradlew assembleDebug
# 位置：app/build/outputs/apk/debug/app-debug.apk

# Release（需要配置签名）
./gradlew assembleRelease
# 位置：app/build/outputs/apk/release/app-release.apk
```

## 📲 装到真机

```bash
adb install app/build/outputs/apk/debug/app-debug.apk

# 或者通过 Android Studio 直接 Run
```

## 📁 项目结构

参见 [Phase 5 文档 §项目结构](../docs/phases/phase-5-android.md)

## 🐛 Debug

打开 Android Studio 的 Logcat，tag 过滤 `CloudLink` 或 `OkHttp`。
