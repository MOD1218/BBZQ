# BBZQ

BBZQ 是一个基于 `libxposed API 102` 的 Bilibili 模块，当前主线以 `api102` 入口、宿主内设置页和轻量 roaming hooks 为核心，重点放在可维护的模块启动流程与少量稳定功能。

## 当前状态

- 模块入口已经切换到 `META-INF/xposed/java_init.list`
- 运行时主入口为 `io.github.bbzq.BbzqModule`
- 作用域使用静态声明
- 当前设置入口不再使用对话框，而是打开完整的 `SettingsActivity`
- 项目识别统一为 `BBZQ / bbzq`

## 支持的目标包

```text
tv.danmaku.bili
com.bilibili.app.in
tv.danmaku.bilibilihd
com.bilibili.app.blue
```

## 当前已接入功能

当前 `api102` 主线实际挂载的 hooks 以 [RoamingRuntime.kt](/E:/GitHubRepo/BiliVIPhz/app/src/main/java/io/github/bbzq/roaming/RoamingRuntime.kt:1) 为准：

- 设置入口注入
  在 Bilibili 设置页注入“高级设置”，点击后打开模块自己的设置页面
- 净化分享
  将 `b23.tv` / `bili2233.cn` 短链还原为普通链接，并尽量保留 `p`、`t` 等定位参数
- 普通链接分享
  关闭小程序式分享时，尽量将分享结果转成更普通的链接形式
- 跳过视频激励广告
  针对 `RewardAdActivity` 做轻量处理
- 净化竖屏视频广告
  按标签过滤广告、购物、短剧、电视剧、纪录片、娱乐、电影、音乐、话题等内容

## 当前设置页

当前设置页为宿主内页面式 UI，风格保持 BBZQ 现有卡片布局，不使用设置对话框。页面目前分为三组：

- 分享与链接
  `净化分享`
  `普通链接分享`
- 播放净化
  `跳过视频激励广告`
- 竖屏视频净化
  `净化竖屏视频广告`
  标签筛选
  拦截统计

对应实现位于 [SettingsContentFactory.kt](/E:/GitHubRepo/BiliVIPhz/app/src/main/java/io/github/bbzq/SettingsContentFactory.kt:1)。

## 使用方式

1. 安装模块 APK。
2. 在支持 `libxposed API 102` 的框架中启用模块。
3. 给目标 Bilibili 应用授予作用域。
4. 重启目标应用。
5. 打开 Bilibili 设置页，进入模块注入的“高级设置”。

桌面图标对应的是模块自己的介绍页，不是单独的调试工具。

## 构建

环境要求：

- JDK `21`
- Android Gradle Plugin `8.13.1`
- Kotlin `2.3.21`
- Gradle Wrapper `8.14.4`

Debug 构建：

```powershell
.\gradlew.bat assembleDebug
```

输出：

```text
app/build/outputs/apk/debug/app-debug.apk
```

Release 构建：

```powershell
.\gradlew.bat assembleRelease
```

若已配置签名参数，构建后会生成：

```text
app/build/outputs/apk/release/app-release.apk
app/build/outputs/apk/release/bbzq_v<releaseName>-<commitCount>.apk
```

## 项目结构

- [app/src/main/java/io/github/bbzq/BbzqModule.kt](/E:/GitHubRepo/BiliVIPhz/app/src/main/java/io/github/bbzq/BbzqModule.kt:1)
  `libxposed API 102` 模块入口
- [app/src/main/java/io/github/bbzq/roaming/](/E:/GitHubRepo/BiliVIPhz/app/src/main/java/io/github/bbzq/roaming)
  `api102` 反射与 hook helper、runtime、核心 roaming hooks
- [app/src/main/java/io/github/bbzq/SettingsActivity.kt](/E:/GitHubRepo/BiliVIPhz/app/src/main/java/io/github/bbzq/SettingsActivity.kt:1)
  模块设置页 Activity
- [app/src/main/java/io/github/bbzq/ModuleSettingsProvider.kt](/E:/GitHubRepo/BiliVIPhz/app/src/main/java/io/github/bbzq/ModuleSettingsProvider.kt:1)
  模块与宿主进程之间的设置桥接入口

## 已知限制

- README 只描述当前已经接上 `api102` 主线的功能，不代表旧 `hooks/` 目录里的所有历史能力都仍在实际挂载。
- 直播画质、番剧解析等更重的 roaming 功能目前没有完整迁回 `api102` 主线。
- 设置入口依赖宿主设置页结构，Bilibili 更新后可能需要重新适配。
- 目前仍保留 `io.github.bbzq` 这一包名作为模块识别，不再使用旧的 `bzzq` 拼写。

## 授权

本项目使用木兰公共许可证第 2 版（Mulan PubL v2）。

完整授权见 [LICENSE](/E:/GitHubRepo/BiliVIPhz/LICENSE)。
