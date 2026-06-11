# bzzq

`bzzq` 是一个基于 `libxposed API 101` 的 Android Xposed 模块，面向 Bilibili 客户端提供轻量的功能修补、广告净化和小型实用工具。

当前模块仅在以下框架环境中启用：

- `NPatch`
- `Vector`
- `LSPosed` 版本码大于 `7700`

若不满足上述条件，模块会在载入后保持停用，不向目标应用安装 hook。

## 目标包名

```text
tv.danmaku.bili
com.bilibili.app.in
tv.danmaku.bilibilihd
com.bilibili.app.blue
```

## 当前功能

- 跳过开屏广告
- 解锁部分视频功能
- 视频详情页自动点赞
- 修正直播画质 URL
- 跳过小游戏奖励广告
- 屏蔽直播预约卡片
- 净化竖屏视频广告
- 复制最近捕获到的 `access_key`
- 在 Bilibili 的“其它设置”页注入 `bzzq` 模块设置入口

## 模块设置

模块设置页会显示所有当前已接入的功能，包括默认启用的项目。

当前默认开启的功能：

- 跳过开屏广告
- 解锁视频功能
- 跳过小游戏奖励广告

其余功能默认关闭，需要手动开启。

## 使用方式

1. 安装并启用受支持的 Xposed 框架。
2. 安装本模块 APK。
3. 在框架管理器中启用本模块。
4. 将作用域授予目标 Bilibili 应用。
5. 重启目标应用。

启用后，可通过以下方式打开模块设置：

- 桌面上的 `bzzq` 启动图标
- Bilibili `设置 -> 其它设置 -> bzzq`

如果未看到内部入口，通常说明当前客户端版本的设置页结构与已适配版本不同，需要继续调整 hook。

## 构建

### 环境要求

- JDK `21`
- Android Gradle Plugin `8.13.1`
- Kotlin `2.3.21`
- Gradle Wrapper `8.14.4`

### Debug 构建

```powershell
.\gradlew.bat assembleDebug
```

输出文件：

```text
app/build/outputs/apk/debug/app-debug.apk
```

### Release 构建

`release` 也属于正式支持的构建产物，不只是 `debug`。

```powershell
.\gradlew.bat assembleRelease
```

若已在 `gradle.properties` / 本地环境中配置签名参数，构建完成后会生成 release APK，并额外复制一份带版本号的产物：

```text
app/build/outputs/apk/release/app-release.apk
app/build/outputs/apk/release/bzzq_<version>.apk
```

当前版本名格式为 `v<releaseName>-<commitCount>`，
其中 `releaseName` 来自 `gradle.properties`，`versionCode` 也仅跟随本地 commit 数增长。

## 项目特性

- 使用 `libxposed API 101`
- 使用静态 scope 声明目标包名
- 使用 Java/Kotlin `21`
- `libxposed api` 以 `compileOnly` 方式引入
- 主要通过反射和动态 hook 适配客户端结构变化

## 兼容性说明

- 这是一个针对 Bilibili 客户端行为做修改的 Xposed 模块，兼容性会受到 App 版本、混淆变化和框架实现影响。
- 某些 hook 失效时，通常是类名、方法签名或字段结构发生变化，不一定代表整个模块不可用。
- “其它设置”入口采用了参考 `BiliRoaming` / `BiliRoamingX` 的注入思路，但不同版本客户端的设置页结构并不完全一致。

## 授权

本项目使用木兰公共许可证第 2 版（Mulan PubL v2）。

完整授权内容见 [LICENSE](LICENSE)。
官方文本请参考：

<http://license.coscl.org.cn/MulanPubL-2.0>
