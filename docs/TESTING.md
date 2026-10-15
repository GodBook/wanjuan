# 验证记录

本文件由实际执行结果更新；不将源码中的测试用例等同于已经通过。

## 0.1.1 回归验证（2026-10-15）

- JVM：14 项通过，含古登堡硬换行、分章、卷首制作说明、特殊数字、错误页面与维基导航清理 / 表格正文保留回归。
- Android Lint Debug：通过，无错误；弃用 API 等警告仍保留。
- 小米 9 SE / Android 15：10 项仪器测试全部通过，46.087 秒。包含真实联网书源验证。
- Android 10 / API 29：10 项全部通过，59.325 秒，本轮也显式启用并通过真实联网书源检查。
- 阅读界面覆盖三种分页模式的快速往返翻页、上下滚动、书签、设置及重进后的进度；Android 10 的自动化明确等待正文排版完成再点击。
- 真机视觉检查确认深浅色主题、搜索入口、状态栏与系统导航图标可读；阅读页按正文背景单独切换系统栏图标颜色。
- 内置书源初始化、关闭后不重新启用、删除后不自动恢复、手动补回、导出识别与备份恢复通过验证。

真实联网检查：维基文库《红楼梦》120 回、《西游记》100 回、《三国演义》120 回、《儒林外史》56 回；古登堡《红楼梦》120 回、《西游记》100 回。六个版本均验证目录数量及首章、中间章、末章正文。只代表当次网络环境可用，不等于所有地区、所有时段可用。

本地证据：`artifacts/011-phone-release-gate.log`、`artifacts/011-android10-release-gate.log`、`artifacts/011-system-bars-build.log`，以及 JVM XML。联网测试默认跳过，发布验收时使用 `am instrument -w -r -e liveSources true io.github.godbook.wanjuan.debug.test/androidx.test.runner.AndroidJUnitRunner` 显式启用。

### 0.1.1 正式包与同机性能对比

发布包来自 GitHub Actions `38738980553`，生产源码提交 `9d2fb53`；该签名构建及 Android checks `38738978460` 均成功，包含 Release Lint 检查。包名不变，versionCode 101 / versionName 0.1.1。APK 为 4,997,317 字节，比 0.1.0 增加 32 KiB。

APK SHA-256：`a542c9820152bf725b94850a9f62f033e7aceacfb6a2dfd620e324bf464d79ea`。证书 SHA-256 与 0.1.0 相同：`d034cee01f15886cc4fb6ca20250ee49449f4f9bd3c64254e0e7a12788b4339c`。

正式签名包还通过实际界面的“搜索 → 选择来源 → 开始阅读”分别打开维基文库及古登堡《红楼梦》，确认正文可见且无目录导航残留。

本轮通过 adb 同签名覆盖安装正式包，确认版本升至 101，书架仍保留《山间来信》，三轮往返翻页后均回到原第 1 章第 2 / 11 页。0.1.0 的真实应用内更新验收记录保留在下方；本轮覆盖安装不冒充再次执行了在线安装器流程。

对比方法：同一台小米 9 SE、1080×2340、默认排版与覆盖翻页、同一部自编测试小说《山间来信》；每轮强制停止应用后启动，从书架进入原位置，等待 2 秒，重置 `gfxinfo`，执行 10 组“下一页→上一页”（共 20 次翻页，每次后等待 0.3 秒），随后读取 `gfxinfo` 与 `meminfo`。每版独立执行三轮，均采集 240 帧。表中展示三轮中位数，并保留全部样本：

| 指标 | 0.1.0 | 0.1.1 |
| --- | --- | --- |
| 翻页后 PSS | 217,317 KiB（约 212.2 MiB） | 73,167 KiB（约 71.5 MiB） |
| 翻页后 RSS | 322,476 KiB | 177,552 KiB |
| 帧耗时 P95 | 20 ms | 17 ms |
| Janky frames | 20 / 240（8.33%） | 17 / 240（7.08%） |
| 冷启动 TotalTime | 346 ms | 374 ms |

PSS 约下降 66.3%，P95 约下降 15%。冷启动没有改善，不能宣传为全方位提速。原始三轮 PSS 分别为旧版 `216444 / 217317 / 217531`、新版 `74746 / 73163 / 73167` KiB；P95 为 `21 / 20 / 20` 和 `17 / 16 / 17` ms；冷启动为 `353 / 335 / 346` 和 `453 / 374 / 374` ms。

这是固定场景的短时采样，不是长期内存上限、所有设备的流畅度保证或耗电结论。正式包、原始结果与执行脚本保存在本地 `artifacts/release-0.1.1/`、`artifacts/before-011*`、`artifacts/after-011*`、`artifacts/011-performance-before.json`、`artifacts/011-performance-after.json` 和 `artifacts/measure-reader.ps1`。

以下保留 0.1.0 的历史验收记录。

## 环境

- 开发：Windows，JDK 17，Android SDK 35，Gradle 8.14.2。
- 主要实体测试设备：小米 9 SE，Android 15 / API 35。
- 最低兼容目标：Android 10 / API 29。

## 测试范围

JVM：TXT 章节切分、长文本无丢失、CSS/JSONPath 规则、元数据往返、阅读锚点、配置限制、SHA-256。

设备：固定 HTTP 响应下搜索→目录→正文；TXT 导入→进度→备份→恢复；EPUB spine 顺序；脚本隔离/超时；路径与更新地址检查。

界面：书架、文件导入、正文翻页、阅读设置、书签、搜索/书源入口、设置、版本检查。

发布：签名 APK 安装、更新清单与 APK 哈希一致、同签名覆盖升级和阅读数据保留。

## 执行结果

验证日期：2026-10-15。验证对象为 0.1.0 初始版本，不代表设计文档中全部 V1 目标已经完成。

| 项目 | 实际结果 |
| --- | --- |
| JVM 单元测试 | 10 项通过 |
| Android Lint | Debug / Release 均通过，仍有警告，无错误 |
| 小米 9 SE / Android 15 实机 | 8 项仪器测试通过，16.975 秒 |
| Android 10 / API 29 模拟器 | 8 项仪器测试通过，72.049 秒 |
| GitHub Android checks | 运行 38730659010 成功 |
| GitHub Signed release | 运行 38730660321 成功 |
| 发布产物 | 正式签名 APK 已公开发布，4,964,549 字节 |

设备测试覆盖固定 HTTP 响应的搜索、目录和正文流程，TXT 导入与阅读进度、备份恢复、损坏备份不破坏已有数据、EPUB 阅读顺序、大文件 UTF-8 导入、脚本隔离与执行限时、恶意路径及更新地址检查，以及实际阅读界面的翻页、书签、设置和退出重进。

### 真实在线覆盖升级

在实体手机上安装同签名基线版本 0.0.9（versionCode 99），导入自编测试小说《山间来信》，阅读至第 1 章第 2 / 11 页。通过应用的“检查更新”读取公开 GitHub Release，下载并校验 0.1.0 APK；在系统设置授予安装来源权限后，由 Android 安装器完成覆盖升级。检查安装版本为 versionCode 100 / versionName 0.1.0，重新打开后书架内容及第 1 章第 2 / 11 页进度均保留。该流程实际使用应用在线更新入口，并非通过 adb 覆盖安装替代。

### 发布标识

- 包名：`io.github.godbook.wanjuan`
- 发布标签：`v0.1.0`；生产源码提交：`5f6ed22`。
- APK SHA-256：`6e82cac1817fbb022f4f3b064c9a5ba9537139d28dfbb4eb2b95331379793dfe`
- 发布证书 SHA-256：`d034cee01f15886cc4fb6ca20250ee49449f4f9bd3c64254e0e7a12788b4339c`

### 性能观察与证据

实体手机正式包一次冷启动观察值为 345 ms；阅读页一次内存采样 PSS 为 65,660 KB（约 64.1 MiB），RSS 为 169,544 KB（约 165.6 MiB）。这些是单次观测，不是 P95、长期稳定性或耗电结论。

本次执行的本地证据保存在忽略提交的 `artifacts/` 下：`phone-final-tests.log`、`android10-acceptance.log`、`release-startup.txt`、`release-memory.txt`，以及升级前后的 `release-before-update.xml`、`update-installer.xml`、`update-state.xml`、`updated-shelf.xml`、`updated-progress.xml`。JVM 结果位于本机构建输出 `app/build/test-results/testDebugUnitTest/`。GitHub Actions 保留对应构建记录；上述本地原始证据不随仓库分发。

## 未覆盖

第三方真实书源稳定性、用户私有 WebDAV 服务、完整 TalkBack 验收、长时间电量与内存测试、所有 Android 厂商系统。
