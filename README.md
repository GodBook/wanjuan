# 万卷

一款本地优先的 Android 小说阅读器。界面克制，书架、正文和阅读习惯是中心。

- Android 10（API 29）及以后，无需账号。
- 导入 TXT / 文字 EPUB，自动识别章节、保存阅读位置。
- 导入多个阅读 / Legado JSON 书源，多源搜索、目录、正文、手动换源。
- 分页与滚动阅读、自定义字体、字号、行距、主题、背景、音量键翻页、单书设置。
- 书签与笔记、离线下载、本地完整备份、HTTPS WebDAV 备份。
- 从本仓库 GitHub Releases 检查更新，验证哈希、包名、版本和签名后交给系统安装。

## 安装

在本仓库 **Releases** 下载 `wanjuan-*.apk`。正式版本使用同一发布签名，可以覆盖升级。更新入口：**设置 → 检查更新**。首次应用内升级需要在系统页面允许万卷安装应用，再返回点击“安装更新”。

不内置内容网站或书源。请导入自己有权访问的内容来源。书源兼容是明确的子集，不代表对所有阅读书源完全兼容。参见 [兼容清单](docs/SOURCE_COMPATIBILITY.md)。

## 实机界面

以下为 Android 15 实体手机上的正式版本截图，正文为自编测试小说《山间来信》。

<img src="docs/images/shelf.png" alt="万卷书架" width="270"> <img src="docs/images/reader.png" alt="万卷阅读页" width="270">

0.1.0 已通过 10 项 JVM 单元测试、Android 15 实机与 Android 10 模拟器各 8 项仪器测试，并完成应用内在线覆盖升级及阅读进度保留验证。详细范围与未覆盖项见 [验证记录](docs/TESTING.md)。

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

- [软件设计与实现边界](docs/DESIGN.md)
- [书源规则兼容清单](docs/SOURCE_COMPATIBILITY.md)
- [发布与在线更新](docs/RELEASING.md)
- [验证记录](docs/TESTING.md)
- [隐私说明](PRIVACY.md)

## 已知边界

当前为 **0.1.0 初始版本**。不支持所有 Legado 扩展接口、动态网页和复杂登录；EPUB 支持文字与基础内嵌图片，不还原复杂 CSS 或固定版式。WebDAV 支持手动/每日快照备份，不是实时同步。不提供听书、仿真翻页或插件系统。

完整设计中的后续功能与当前实现差异记录在设计文档中，不将尚未实现的能力标为已完成。

MIT License。
