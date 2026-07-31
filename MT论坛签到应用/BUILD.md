# MT论坛签到助手 - 本地构建指南

## 环境要求

| 组件 | 版本要求 |
|-----|---------|
| Android Studio | Hedgehog (2023.1.1) 或更高 |
| JDK | 17 |
| Android SDK | API 34 |
| Gradle | 8.2 |
| Kotlin | 1.9.20 |

## 构建步骤

### 方法一：Android Studio 构建（推荐）

1. **下载并解压项目**
   ```bash
   unzip MT论坛签到应用.zip
   ```

2. **打开项目**
   - Android Studio → File → Open
   - 选择解压后的 `MT论坛签到应用` 目录

3. **等待 Gradle 同步**
   - 首次同步会下载依赖，需要几分钟

4. **构建 APK**
   - Build → Build Bundle(s) / APK(s) → Build APK(s)
   - 或按快捷键 `Ctrl+F9`

5. **获取 APK**
   ```
   app/build/outputs/apk/debug/app-debug.apk
   ```

### 方法二：命令行构建

**Linux/macOS:**
```bash
cd MT论坛签到应用
./gradlew assembleDebug
```

**Windows:**
```cmd
cd MT论坛签到应用
gradlew.bat assembleDebug
```

**输出位置:**
```
app/build/outputs/apk/debug/app-debug.apk
```

## Release 版本构建

```bash
# Debug 版本
./gradlew assembleDebug

# Release 版本（需要签名）
./gradlew assembleRelease
```

## 签名配置

创建 `app/keystore.jks` 并在 `app/build.gradle.kts` 添加：

```kotlin
android {
    signingConfigs {
        create("release") {
            storeFile = file("keystore.jks")
            storePassword = "your_password"
            keyAlias = "your_alias"
            keyPassword = "your_key_password"
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
        }
    }
}
```

生成签名密钥：
```bash
keytool -genkey -v -keystore app/keystore.jks \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias mtsignin
```

## 常见问题

### 1. Gradle 同步失败

检查网络连接，确保能访问：
- `https://dl.google.com`
- `https://repo.maven.apache.org`

如果网络受限，配置阿里云镜像（已在 build.gradle.kts 中配置）。

### 2. JDK 版本不匹配

```bash
java -version
# 应显示 17.x.x

# 如版本不对，设置 JAVA_HOME
export JAVA_HOME=/path/to/jdk-17
```

### 3. Android SDK 未安装

在 Android Studio 中：
- Tools → SDK Manager
- 安装 Android 14.0 (API 34)
- 安装 Android SDK Build-Tools 34

### 4. 清理重新构建

```bash
./gradlew clean
./gradlew assembleDebug
```

## 项目结构

```
MT论坛签到应用/
├── app/                           # 应用模块
│   ├── src/main/
│   │   ├── java/com/example/mtsignin/
│   │   │   ├── App.kt             # Application
│   │   │   ├── MainActivity.kt    # 主Activity
│   │   │   ├── data/              # 数据层
│   │   │   │   ├── local/         # Room数据库
│   │   │   │   ├── model/         # 数据模型
│   │   │   │   └── repository/    # 数据仓库
│   │   │   ├── ui/                # UI层 (Compose)
│   │   │   ├── network/           # 网络请求
│   │   │   ├── service/           # 后台服务
│   │   │   ├── di/                # 依赖注入
│   │   │   └── util/              # 工具类
│   │   ├── res/                   # 资源文件
│   │   └── AndroidManifest.xml
│   ├── build.gradle.kts           # 模块配置
│   └── proguard-rules.pro         # 混淆规则
├── gradle/wrapper/                # Gradle Wrapper
├── build.gradle.kts               # 项目配置
├── settings.gradle.kts            # 项目设置
├── gradlew                        # Linux/macOS 脚本
└── gradlew.bat                    # Windows 脚本
```

## 功能清单

| 功能 | 状态 |
|-----|------|
| 多账号管理 | ✅ |
| 密码加密存储 | ✅ |
| 手动签到 | ✅ |
| 定时签到 | ✅ |
| 签到状态显示 | ✅ |
| Material Design 3 | ✅ |

## 技术栈

- **UI**: Jetpack Compose + Material Design 3
- **架构**: MVVM + Repository
- **DI**: Hilt
- **数据库**: Room
- **网络**: OkHttp
- **后台任务**: WorkManager
- **加密**: Android KeyStore + AES-GCM