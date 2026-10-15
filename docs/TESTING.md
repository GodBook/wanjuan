# 验证记录

本文件由实际执行结果更新；不将源码中的测试用例等同于已经通过。

## 0.1.3 发布候选验证（2026-10-15，进行中）

生产源码在本地已通过 14 项 JVM、Debug/Release Lint 以及正式签名构建；发布证书 SHA-256 仍为 `d034cee01f15886cc4fb6ca20250ee49449f4f9bd3c64254e0e7a12788b4339c`。

- 小米 9 SE / Android 15 与 Android 10 / API 29 均完成常规套件：24 用例，19 个实际执行通过，5 个发布专用探针按条件跳过。显式启用 `liveSources=true`，六个经典版本的目录和首、中、末章真实联网读取通过。
- 新增检查覆盖离线备份及保护标记、旧格式备份与内嵌进度、下载暂停继续及网络策略、换源状态与笔记、历史排序、并行操作、持续错误提示、100MiB 流式备份及下载限额。
- API 29 另通过 8MiB 正向文件上传/下载的字节数与完整 SHA-256 对照，以及恢复后缺少原书源时实际显示离线正文的界面检查。
- 五阶段恢复同时检查书库文件、数据库书名/分组/进度与 DataStore 设置。手机和 API 29 已执行真实进程退出重启检查；补充数据库差异的探针仍在收尾，最终结果另行登记。
- 手机正在运行两小时阅读稳定性探针。其余验证通过不能替代这一结果，当前不宣称长时验收完成。

首轮发现并修复了 Android 目录别名导致的共享预设资源引用识别错误。API 29 首次启动曾出现 System UI 无响应弹窗；留存失败证据，处理环境后在主机空闲时整套通过。并发操作测试最初依赖固定延时，后改为两个显式完成信号，验证短任务结束后长任务仍保持忙碌状态。正向传输用例的模拟服务端曾同时保留两份 8MiB 数据，超过该镜像的 16MiB Java 堆；释放已验证上传缓存后以原 8MiB 数据量通过，未修改应用堆配置。

### 手机规模与备份样本

100 本、每本 1,000 章；Debug 包内同设备依次执行旧流程（解码/编码完整 Book 后写入）与独立进度写入，预热一次后采集各 30 次。单次写入中位数分别为 **69,127µs 与 13,178µs**；记录负载为 **90,967 与 118 字节**。这是写入过程的局部对照，不是不同正式 APK 的完整书架、启动或耗电性能对照。通用 records 表仍触发 Room 查询刷新，但内容未变时不重复解码目录。

500MiB 样本由 5 个各 100MiB 文件组成；执行生成、导出、删除原文件、恢复、逐文件哈希对照、超限导出拒绝和取消清理，总计 **34,370ms**，ZIP 为 **524,455,090 字节**。PSS 基线 **92,685KiB**，250ms 采样峰值 **95,001KiB（约 92.8MiB）**。采样可能遗漏短峰，不能推广为所有备份场景的内存上限。

本地证据：`artifacts/013-regression-build.log`、`013-release-gate-build.log`、`013-phone-full.log`、`013-api29-idle-final.log`、`013-api29-added-gates.log`、`013-phone-process-recovery.log`、`013-api29-final-process-recovery.log`、`013-progress-benchmark.json`、`013-large-backup-benchmark.json`。正式版升级前通过系统文件选择器导出旧格式备份 `013-official-pre-upgrade.zip`，并记录《山间来信》原位置为第 1 章第 2 / 11 页。

复现入口：`scripts/test-device.ps1 -Serial <序列号> -LiveSources`；`scripts/verify-recovery.ps1 -Serial <序列号>`。发布专用长时、性能和大备份探针需显式参数，参见 `AcceptanceProbeTest.kt`。恢复与规模测试仅使用独立 `.debug` 包，不对正式书库执行覆盖恢复。

## 0.1.2 文案补丁与真实在线更新（2026-10-15）

0.1.2 仅修正关于页的旧书源说明并提升版本号，生产源码提交 `159594e`。核心书源、阅读与性能实现和 0.1.1 相同；下方设备功能与性能数据来自 0.1.1，不冒充重新测量的 0.1.2 数据。

- GitHub Android checks `38740875171` 与 Signed release `38740877492` 均成功，14 项 JVM 测试和对应 Lint 检查通过。
- 发布包 versionCode 102 / versionName 0.1.2，4,997,317 字节，签名证书与既有发布相同。
- APK SHA-256：`26569182a80794402492becf2eda11065f7bcca541d566f1a212bb27b416872c`。
- 实体手机从 0.1.1 的“检查更新”发现 0.1.2，实际完成在线下载、包校验、点击“安装更新”、Android 系统安装器覆盖安装。此轮没有以 adb 安装代替该流程。
- 安装后确认版本 102；打开《山间来信》仍为第 1 章第 2 / 11 页，书架保留。设置页确实显示“当前版本 0.1.2”及“内置经典书源，支持导入更多来源。”。
- 调试包与测试包已从手机卸载，本任务 Android 10 模拟器已关闭，手机临时亮屏设置已恢复。

本地证据：`artifacts/012-update-found.xml`、`artifacts/012-update-verified.xml`、`artifacts/012-system-installer.xml`、`artifacts/012-install-success.xml`、`artifacts/012-retained-progress.xml`、`artifacts/012-about.xml`；正式包位于 `artifacts/release-0.1.2/`。

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
