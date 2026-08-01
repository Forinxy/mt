# MT论坛签到助手 更新流程提示词

以下提示词可直接复制使用，用于请求一次完整的版本更新。

## 使用方式

1. 复制下方提示词到对话中
2. 如需同时改功能/UI，在提示词末尾追加具体需求描述

## 更新提示词模板

```
请对 MT论坛签到助手 进行一次版本更新，按以下流程执行：

1. 版本号递增：
   - 当前版本：v{旧版本号}（versionCode {旧code}）
   - 新版本：v{新版本号}（versionCode {新code}）
   - 在 app/build.gradle.kts 中修改 versionCode 和 versionName
   - 规则：versionCode 每次 +1；versionName 按主版本.次版本.修订 递增

2. 若我提供了功能/UI 需求，先实现代码改动

3. 构建 APK：
   export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
   export ANDROID_HOME=/root/Android/Sdk
   cd "MT论坛签到应用" && ./gradlew assembleDebug
   确认 BUILD SUCCESSFUL，并用 aapt 确认 versionName/versionCode 正确

4. 保存 APK：
   - 复制为新文件（不覆盖旧版）：MT论坛签到助手-v{新版本号}-debug.apk
   - 同步更新 MT论坛签到应用/dist/app-debug.apk（该文件被 gitignore）

5. 提交并推送：
   - git add 相关源码文件 + 新版本 APK（只 add 本次改动的文件）
   - commit 信息格式：release: v{新版本号} {改动摘要}
   - 推送命令：
     env -u GIT_CONFIG_COUNT -u GIT_CONFIG_KEY_0 -u GIT_CONFIG_VALUE_0 \
       -u GIT_CONFIG_KEY_1 -u GIT_CONFIG_VALUE_1 \
       git -c credential.helper='store' push origin main

6. 创建新 Release（不覆盖旧版本）：
   - 打新 tag：git tag v{新版本号} && git push origin v{新版本号}
   - 创建 Release：
     gh release create v{新版本号} --title "MT论坛签到助手 v{新版本号}" \
       --notes "{版本说明}" /tmp/生成的apk文件路径
   - 保留历史版本，绝不覆盖已有 tag/release/APK 文件

7. 汇报结果：
   - 新版本号、versionCode
   - 改动摘要
   - Release 链接
   - 根目录 APK 文件名
```

## 版本号递增规则

- versionCode：整数，每次发布 +1（1 → 2 → 3 ...）
- versionName：语义化版本，参考变更大小
  - 修复/小改动：修订位 +1（1.0.0 → 1.0.1 → 1.0.2）
  - 新增功能：次版本 +1，修订归零（1.0.x → 1.1.0）
  - 大版本重构：主版本 +1（1.x.x → 2.0.0）

## 已发布版本

| 版本 | versionCode | 说明 | Release |
|------|------------|------|---------|
| v1.0.0 | 1 | 签到、手动刷新排名 | 保留 |
| v1.0.1 | 2 | 简化签到状态卡按钮布局 | 保留 |
