# CloudLink Android Client

> Native Android app for controlling HA devices via CloudLink cloud

## ✨ 功能

- ✅ 用户登录 / 注册
- ✅ 设备列表（按 domain 分组：💡灯、🔌开关、📊传感器 等）
- ✅ 设备控制（开关 toggle / turn_on / turn_off / 按钮按）
- ✅ 实时状态同步（WebSocket）
- ✅ 退出登录
- 🔜 设备详情页（亮度滑块、颜色拾取等高级控件）

## 🛠️ 技术栈

- Kotlin + Jetpack Compose
- Material 3
- Hilt（依赖注入）
- Retrofit + OkHttp（REST API）
- OkHttp WebSocket（实时同步）
- Moshi（JSON 解析）
- DataStore Preferences（Token 存储）
- Navigation Compose

## 📁 项目结构

```
app/src/main/java/com/yourname/cloudlink/
├── CloudLinkApp.kt              # Application 类（Hilt 入口）
├── MainActivity.kt              # 单 Activity
├── data/
│   ├── api/
│   │   ├── ApiService.kt        # REST 接口
│   │   └── RetrofitClient.kt    # Retrofit + OkHttp DI 模块
│   ├── local/
│   │   └── TokenStore.kt        # DataStore 包装
│   ├── model/
│   │   ├── Auth.kt              # 登录/注册/Token 数据类
│   │   └── Device.kt            # Device / ActionRequest
│   └── ws/
│       └── DeviceWebSocket.kt   # WS 客户端
├── di/                          # （用 RetrofitClient 内的 Module）
├── nav/
│   └── NavGraph.kt              # 导航
└── ui/
    ├── theme/Theme.kt           # Material 3 主题
    ├── login/                   # 登录页
    │   ├── LoginScreen.kt
    │   └── LoginViewModel.kt
    └── home/                    # 设备列表页
        ├── HomeScreen.kt
        └── HomeViewModel.kt
```

## 🚀 打开工程

```bash
# 1. 用 Android Studio 打开 android/ 目录
open -a "Android Studio" /path/to/cloudlink/android

# 2. 等 Gradle 同步完成
# 3. 连接真机或模拟器，点 Run ▶
```

## ⚙️ 后端地址配置

**当前默认**：`http://118.31.225.109:8000`（你的云服务器公网 IP）

修改位置：
- `app/src/main/java/com/yourname/cloudlink/data/api/RetrofitClient.kt` 第 30 行 `BASE_URL`
- `app/src/main/java/com/yourname/cloudlink/ui/home/HomeViewModel.kt` 中 `ws.connect(...)` 调用

## 🧪 手工测试场景

1. 打开 app → 看到登录页
2. 输入 `guoyubing123@163.com` + 你的密码 → 登录
3. 跳到 Home 页，看到 96 个设备（按 domain 分组）
4. 切一个灯/开关 → HA 那边应该响应
5. 改 HA 端状态 → App 几秒内更新

## 📦 构建

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## 🔒 安全注意

- Token 存在 DataStore（明文，但 OS 级别隔离）
- 当前用 `usesCleartextTraffic="true"` 因为服务器是 HTTP（生产应改 HTTPS）
- 没做证书锁定（pin），依赖系统 CA
