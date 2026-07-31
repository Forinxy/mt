# MT论坛签到助手

一个用于MT论坛 (https://bbs.binmt.cc) 自动签到的Android应用。

## 功能特性

| 功能 | 说明 |
|-----|------|
| 多账号管理 | 添加、删除、启用/禁用多个账号 |
| 一键签到 | 所有账号批量签到 |
| 定时签到 | 每天自动签到 (可配置时间) |
| 状态显示 | 签到排名、奖励、历史记录 |
| 密码加密 | 使用Android KeyStore加密存储密码 |
| 签到提醒 | 通知栏提醒签到结果 |
| Material Design 3 | 现代化UI设计 |

## 技术栈

- **UI框架**: Jetpack Compose + Material Design 3
- **架构**: MVVM + Repository
- **依赖注入**: Hilt
- **数据库**: Room
- **网络请求**: OkHttp
- **后台任务**: WorkManager
- **加密存储**: Android KeyStore + AES-GCM

## 项目结构

```
app/
├── app/src/main/java/com/example/mtsignin/
│   ├── App.kt                    # Application类
│   ├── MainActivity.kt          # 主Activity
│   ├── data/
│   │   ├── local/               # 本地数据层
│   │   │   ├── AppDatabase.kt
│   │   │   ├── AccountDao.kt
│   │   │   └── AccountEntity.kt
│   │   ├── model/               # 数据模型
│   │   │   └── SignInResult.kt
│   │   └── repository/          # 数据仓库
│   │       └── SignRepository.kt
│   ├── ui/                      # UI层
│   │   ├── MainScreen.kt
│   │   ├── MainViewModel.kt
│   │   ├── AddAccountDialog.kt
│   │   └── AccountListItem.kt
│   ├── network/                 # 网络层
│   │   └── MTForumApi.kt
│   ├── service/                 # 后台服务
│   │   └── SignInWorker.kt
│   ├── di/                      # 依赖注入
│   │   ├── AppModule.kt
│   │   └── DatabaseModule.kt
│   └── util/                    # 工具类
│       └── CryptoUtils.kt
├── build.gradle.kts
└── AndroidManifest.xml
```

## 构建要求

- Android Studio Hedgehog 或更高版本
- JDK 17
- Android SDK 34
- Gradle 8.2

## 构建APK

```bash
# Debug版本
./gradlew assembleDebug

# Release版本
./gradlew assembleRelease
```

## 使用方法

1. 安装应用后，点击右下角 **+** 按钮添加账号
2. 输入MT论坛的用户名和密码
3. 点击账号卡片上的 **签到按钮** 进行单账号签到
4. 或点击顶部 **全部签到** 按钮批量签到所有账号
5. 点击账号卡片展开查看签到详情和操作选项

## 安全说明

- 密码使用 **Android KeyStore** 生成密钥
- 采用 **AES-GCM** 加密算法
- 加密数据存储在本地数据库中
- 不上传任何用户数据到服务器

## 定时签到

应用会在每天固定时间自动执行签到：

- 默认签到时间：每天早上8点
- 签到完成后会在通知栏显示结果
- 可以在系统设置中关闭通知权限

## 已知问题

- 部分网络环境下可能签到失败，请检查网络连接
- 如果论坛更新签到接口，可能需要更新应用版本

## 更新日志

### v1.0.0
- 初始版本
- 支持多账号管理
- 支持手动和定时签到
- Material Design 3 UI

## 许可证

MIT License