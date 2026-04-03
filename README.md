# ZenSee Android

ZenSee 是一款面向禅修记录与陪伴场景的 Android 应用，围绕日常打坐、心境记录、提醒通知、数据统计与群组共修展开。

当前仓库用于维护 Android 客户端源码。Android 安装包通过仓库内的稳定下载路径分发，避免每次发版都修改 Web 下载页或 App 内分享链接。

## 功能概览

- 禅修流程：支持设置禅修时长与收功时长，进入专注计时流程
- 每日提醒：支持通知提醒与闹钟提醒，并在重启后自动恢复
- 心境记录：记录每日心境状态并查看历史变化
- 数据统计：查看禅修时长、连续记录、趋势与热力图等统计信息
- 账号体系：支持注册、登录、找回密码与密码重置深链
- 云端同步：基于 Supabase 进行认证与核心数据同步
- 群组共修：支持发现群组、创建群组、加入申请、通知审批与成员管理
- 多语言：当前包含简体中文、繁体中文与日语资源

## 技术栈

- Kotlin
- Android View System + ViewBinding
- Gradle
- Min SDK 26
- Target SDK 35
- Java 17
- Supabase REST/Auth

## 项目结构

```text
app/src/main/java/com/zensee/android
app/src/main/res
app/src/test
gradle/wrapper
```

主要模块包括：

- `MainActivity`：主入口与底部标签页容器
- `MeditationActivity`：禅修进行页
- `MeditationHistoryActivity` / `MoodHistoryActivity`：历史记录
- `ReminderManager`：提醒调度
- `AuthManager`：认证与会话管理
- `data/`：云端数据访问
- `domain/`：倒计时、统计、规则计算
- `widget/`：自定义统计图表与交互控件

## 本地运行

### 环境要求

- Android Studio 最新稳定版
- Android SDK 35
- JDK 17

### 启动步骤

1. 克隆仓库
2. 用 Android Studio 打开项目根目录
3. 在 `local.properties` 中配置本机 `sdk.dir`
4. 参考 `key.properties.example` 创建本地 `key.properties`
5. 等待 Gradle Sync 完成
6. 运行 `app` 模块

也可以使用命令行：

```bash
./gradlew assembleDebug
```

## 测试

仓库包含若干单元测试，覆盖部分认证重试、禅修倒计时、统计与展示规则逻辑。

```bash
./gradlew test
```

## 安全检查

发布前请先过一遍安全检查清单：

- `SECURITY_CHECKLIST.md`

## 发布流程

- 源码托管：GitHub
- Android 安装包稳定链接：
  `https://raw.githubusercontent.com/IvesZhan/zensee-android/main/downloads/latest/ZenSee-android-latest.apk`
- 下载页稳定链接：
  `https://iveszhan.github.io/zensee-web/download/`
  `https://iveszhan.github.io/zensee-web/download/ja/`
  `https://iveszhan.github.io/zensee-web/download/zh-hant/`
  `https://iveszhan.github.io/zensee-web/download/en/`

每次发布新版 Android 包时，直接运行：

```bash
./scripts/publish-release.sh
```

这个脚本会完成以下事情：

- 执行 `assembleRelease`
- 验证 APK 签名
- 更新 `downloads/latest/ZenSee-android-latest.apk`
- 更新 `downloads/latest/SHA256.txt`
- 按当前 `versionName` 同步版本归档目录，例如 `downloads/v1.0.0/`

之后只需要提交并推送 `downloads/latest` 和对应版本目录，Web 下载页和 App 内分享逻辑都不需要修改。

## 说明

- 当前项目已接入 Supabase 相关接口与认证流程
- 三端共享的 Supabase schema、hotfix 与执行说明统一维护在 `../Supabase`
- 本地发布依赖 `key.properties`、签名 keystore 与 Android SDK

## License

暂未指定。若准备公开长期维护，建议补充明确的开源许可证。
