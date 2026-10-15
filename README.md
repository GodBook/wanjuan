# 万卷

一款本地优先的 Android 小说阅读器。界面克制，书架、正文和阅读习惯是中心。

- Android 10（API 29）及以后，无需账号。
- 导入 TXT / 文字 EPUB，自动识别章节、保存阅读位置。
- 内置维基文库与 Project Gutenberg 经典小说入口，支持简繁书名和作者搜索。
- 导入多个阅读 / Legado JSON 书源，多源搜索、目录、正文、手动换源。
- 分页与滚动阅读、自定义字体、字号、行距、主题、背景、音量键翻页、单书设置。
- 书签与笔记、离线下载、本地完整备份、HTTPS WebDAV 备份。
- 从本仓库 GitHub Releases 检查更新，验证哈希、包名、版本和签名后交给系统安装。

## 安装

在本仓库 **Releases** 下载 `wanjuan-*.apk`。正式版本使用同一发布签名，可以覆盖升级。更新入口：**设置 → 检查更新**。首次应用内升级需要在系统页面允许万卷安装应用，再返回点击“安装更新”。

内置两个经典书源，收录《红楼梦》《西游记》《三国演义》《儒林外史》的精选版本；不是全站搜索。首次阅读需要联网，也可以继续导入自己的书源。详见 [内置书源说明](docs/BUILTIN_SOURCES.md)。书源兼容是明确的子集，不代表对所有阅读书源完全兼容。参见 [兼容清单](docs/SOURCE_COMPATIBILITY.md)。

## 实机界面

以下为 Android 15 实体手机实测界面，正文展示内置维基文库版本《红楼梦》。

<img src="docs/images/shelf.png" alt="万卷书架" width="240"> <img src="docs/images/reader.png" alt="万卷阅读页" width="240"> <img src="docs/images/dark.png" alt="万卷深色搜索界面" width="240">

0.1.1 已通过 14 项 JVM 单元测试、Android 15 实机与 Android 10 模拟器各 10 项仪器测试，包含两个内置书源的真实联网读取、快速翻页、滚动和备份恢复。同机固定翻页场景三轮采样中，PSS 中位数约 212 → 71 MiB，帧耗时 P95 为 20 → 17 ms；冷启动没有改善。详细条件、范围与未覆盖项见 [验证记录](docs/TESTING.md)。

## 开发

环境：JDK 17、Android SDK 35、Gradle Wrapper 8.14.2。

```shell
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./gradlew :app:assembleDebugAndroidTest
```

调试包名 `io.github.godbook.wanjuan.debug`，发布包名 `io.github.godbook.wanjuan`，二者数据隔离。选择明确的设备序列号运行设备测试，避免误用其他已连接设备。

Windows 可以用 `scripts/test-device.ps1 -Serial <adb序列号>` 安装调试包并执行测试。

发布签名配置不进入 Git。请阅读 [发布与在线更新](docs/RELEASING.md)。

## 文档

- [后续更新与优化建议](docs/UPDATE_PLAN.md)
- [当前更新执行状态](docs/EXECUTION.md)
- [软件设计与实现边界](docs/DESIGN.md)
- [书源规则兼容清单](docs/SOURCE_COMPATIBILITY.md)
- [发布与在线更新](docs/RELEASING.md)
- [验证记录](docs/TESTING.md)
- [隐私说明](PRIVACY.md)

## 已知边界

当前为 **0.1.2 初始迭代版本**。不支持所有 Legado 扩展接口、动态网页和复杂登录；EPUB 支持文字与基础内嵌图片，不还原复杂 CSS 或固定版式。WebDAV 支持手动/每日快照备份，不是实时同步。不提供听书、仿真翻页或插件系统。

完整设计中的后续功能与当前实现差异记录在设计文档中，不将尚未实现的能力标为已完成。

MIT License。
