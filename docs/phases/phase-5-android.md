# Phase 5: Android 客户端

> 预计耗时：3 天  
> 目标：能登录、看到设备、控制设备的 APK  
> 分支：`feat/phase-5-android`

## 🎯 完成标志

- [ ] Android Studio 能打开工程并构建
- [ ] APK 能装到真机（Android 8+）
- [ ] 用户登录 / 注册工作
- [ ] Token 自动保存、自动登录
- [ ] 设备列表按 domain 分组显示
- [ ] 设备详情页能控制（开关/亮度/温度等）
- [ ] 状态变化实时更新（WebSocket）
- [ ] 网络断开自动重连

## 📁 项目结构

```
android/
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/yourname/cloudlink/
│       │   ├── CloudLinkApp.kt
│       │   ├── MainActivity.kt
│       │   ├── data/
│       │   │   ├── api/
│       │   │   │   ├── ApiService.kt
│       │   │   │   └── RetrofitClient.kt
│       │   │   ├── ws/
│       │   │   │   └── DeviceWebSocket.kt
│       │   │   ├── local/
│       │   │   │   └── TokenStore.kt
│       │   │   └── model/
│       │   │       ├── User.kt
│       │   │       ├── Device.kt
│       │   │       └── Token.kt
│       │   ├── di/
│       │   │   └── AppModule.kt
│       │   ├── ui/
│       │   │   ├── theme/
│       │   │   │   ├── Color.kt
│       │   │   │   ├── Theme.kt
│       │   │   │   └── Type.kt
│       │   │   ├── login/
│       │   │   │   ├── LoginScreen.kt
│       │   │   │   └── LoginViewModel.kt
│       │   │   ├── home/
│       │   │   │   ├── HomeScreen.kt
│       │   │   │   └── HomeViewModel.kt
│       │   │   ├── device/
│       │   │   │   ├── DeviceDetailScreen.kt
│       │   │   │   └── DeviceDetailViewModel.kt
│       │   │   └── components/
│       │   │       └── ...
│       │   └── nav/
│       │       └── NavGraph.kt
│       └── res/
│           ├── strings.xml
│           ├── colors.xml
│           └── ...
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── README.md
```

## 🛠️ 实施步骤

### 步骤 5.1：创建项目

```bash
# Android Studio → New Project
# - Name: CloudLink
# - Package: com.yourname.cloudlink
# - Language: Kotlin
# - Min SDK: 26
# - Build: Gradle Kotlin DSL
# - 选择 "Empty Compose Activity"
```

### 步骤 5.2：build.gradle.kts（app 级别）

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.yourname.cloudlink"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yourname.cloudlink"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")  // 正式发布要换成 release keystore
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose")
    implementation("androidx.lifecycle:lifecycle-runtime-compose")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.8.4")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.52")
    ksp("com.google.dagger:hilt-compiler:2.52")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Retrofit + OkHttp
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Moshi
    implementation("com.squareup.moshi:moshi:1.15.1")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")
    ksp("com.squareup.moshi:moshi-kotlin-codegen:1.15.1")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Coil
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Test
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
```

### 步骤 5.3：AndroidManifest.xml

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

    <application
        android:name=".CloudLinkApp"
        android:allowBackup="false"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.CloudLink"
        android:usesCleartextTraffic="false">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

### 步骤 5.4：数据层

#### ApiService.kt

```kotlin
interface ApiService {
    @POST("api/auth/login")
    suspend fun login(@Body req: LoginReq): TokenResp

    @POST("api/auth/register")
    suspend fun register(@Body req: RegisterReq): TokenResp

    @POST("api/auth/refresh")
    suspend fun refresh(@Body req: RefreshReq): TokenResp

    @GET("api/devices")
    suspend fun listDevices(): List<Device>

    @POST("api/devices/{entityId}/action")
    suspend fun controlDevice(
        @Path("entityId") entityId: String,
        @Body action: DeviceAction,
    ): Response<Unit>
}
```

#### RetrofitClient.kt

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides @Singleton
    fun provideOkHttp(tokenStore: TokenStore): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                tokenStore.accessToken?.let { req.header("Authorization", "Bearer $it") }
                chain.proceed(req.build())
            }
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = if (BuildConfig.DEBUG) Level.BODY else Level.NONE
            })
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

    @Provides @Singleton
    fun provideRetrofit(client: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl("https://api.your-domain.com/")  // 改成你的
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create())
            .build()

    @Provides @Singleton
    fun provideApi(retrofit: Retrofit): ApiService =
        retrofit.create(ApiService::class.java)
}
```

### 步骤 5.5：WebSocket

#### DeviceWebSocket.kt

```kotlin
@Singleton
class DeviceWebSocket @Inject constructor(
    private val tokenStore: TokenStore,
) {
    private val _updates = MutableSharedFlow<DeviceUpdate>(replay = 0, extraBufferCapacity = 64)
    val updates: SharedFlow<DeviceUpdate> = _updates

    private var ws: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    fun connect() {
        val token = tokenStore.accessToken ?: return
        val req = Request.Builder()
            .url("wss://api.your-domain.com/ws/client?token=$token")
            .build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val update = Json.decodeFromString<DeviceUpdate>(text)
                _updates.tryEmit(update)
            }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                // 重连
                Handler(Looper.getMainLooper()).postDelayed({ connect() }, 5000)
            }
        })
    }

    fun disconnect() { ws?.close(1000, "bye"); ws = null }
}
```

### 步骤 5.6：UI 层

#### LoginScreen.kt

```kotlin
@Composable
fun LoginScreen(viewModel: LoginViewModel = hiltViewModel(), onSuccess: () -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.success) { if (state.success) onSuccess() }

    Column(Modifier.padding(24.dp).fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Text("CloudLink", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(32.dp))
        OutlinedTextField(email, { email = it }, label = { Text("邮箱") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(password, { password = it }, label = { Text("密码") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(24.dp))
        Button(onClick = { viewModel.login(email, password) }, modifier = Modifier.fillMaxWidth()) {
            Text("登录")
        }
    }
}
```

#### HomeScreen.kt

```kotlin
@Composable
fun HomeScreen(viewModel: HomeViewModel = hiltViewModel(), onDeviceClick: (Device) -> Unit) {
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val grouped = devices.groupBy { it.domain }

    LazyColumn(Modifier.fillMaxSize()) {
        grouped.forEach { (domain, items) ->
            item { Text(domain.replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(16.dp)) }
            items(items) { device ->
                DeviceRow(device, onClick = { onDeviceClick(device) })
            }
        }
    }
}
```

### 步骤 5.7：导航

```kotlin
@Composable
fun AppNavGraph(navController: NavHostController) {
    NavHost(navController, startDestination = "login") {
        composable("login") { LoginScreen(onSuccess = { navController.navigate("home") }) }
        composable("home") {
            HomeScreen(onDeviceClick = { navController.navigate("device/${it.entityId}") })
        }
        composable("device/{entityId}") { entry ->
            DeviceDetailScreen(entityId = entry.arguments?.getString("entityId") ?: "")
        }
    }
}
```

### 步骤 5.8：构建 APK

```bash
./gradlew assembleDebug
# APK 位置：app/build/outputs/apk/debug/app-debug.apk

# 装到真机
adb install app/build/outputs/apk/debug/app-debug.apk
```

## ✅ 验收

- [ ] App 能登录并显示设备
- [ ] 点开关，HA 端有日志显示执行了 service
- [ ] HA 手动改变状态，App 1秒内收到推送
- [ ] 杀掉 App 重开，自动登录
- [ ] 网络断开后恢复，自动重连

## 📦 提交

```bash
git checkout -b feat/phase-5-android
git add android/
git commit -m "feat(android): scaffold project with Compose + Hilt"
git commit -m "feat(android): implement auth screens"
git commit -m "feat(android): implement device list and control UI"
git commit -m "feat(android): integrate WebSocket for real-time updates"
```

## 🚀 下一步

Phase 5 完成后，进入 **Phase 6：端到端联调**。
