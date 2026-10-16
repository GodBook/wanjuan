# 验证记录

本文件由实际执行结果更新；不将源码中的测试用例等同于已经通过。

## 0.1.3 验证（2026-10-15 至 10-16）

本轮落实 U01–U08。最终生产源码与 `3fd100b` 一致；随后的小屏修正只涉及设备测试。JVM 14 项及 Debug/Release Lint 已通过；发布证书 SHA-256 保持 `d034cee01f15886cc4fb6ca20250ee49449f4f9bd3c64254e0e7a12788b4339c`。

| 验证 | 已取得的结果 |
| --- | --- |
| 小米 9 SE / Android 15 最终离线回归 | 24 用例，19 执行通过、5 个发布探针按条件跳过，49.054 秒 |
| Android 10 / API 29 完整联网回归 | 最终 26 用例、21 执行通过、5 个发布探针跳过，133.426 秒；320×640、关闭动画 |
| 恢复后实际离线阅读 | 缺少原书源时正文仍显示，包含的离线标记继续受缓存清理保护 |
| 正向流式网络传输 | 8MiB 上传与下载，逐次核对字节数及完整 SHA-256 |
| 五阶段真实进程退出恢复 | 手机及 API 29 全部通过，核对文件、数据库书名/分组/位置以及 DataStore 设置 |
| GitHub Android checks | `38849816066` 通过；标准 API 29 共 26 用例，20 执行通过、6 条件跳过，45.890 秒 |
| 手机连续两小时阅读 | 通过；实际 7,200.510 秒，JUnit `OK (1 test)`，结果 `complete=true` |
| 确切签名 APK 与手机覆盖升级 | 签名工作流 `38850341215`、API 29 验收和手机正式 102→103 覆盖升级全部通过 |

功能回归覆盖新旧备份格式、可选离线正文及保护标记、旧内嵌进度、下载暂停继续与网络策略、换源状态及笔记保护、连续搜索归属、历史排序、并行操作忙碌状态、持续错误与重试、100MiB 流式备份、下载限额和存储删除前引用重检。

恢复故障阶段为 `journal`、`old-moved`、`files-installed`、`settings-written`、`committed`。每个阶段主动退出进程，再由新进程触发恢复，分别确认得到完整旧状态或新状态；这包含数据库与设置差异，不能仅以恢复后文件存在作为通过依据。

### 小屏测试诊断

320×640 下，存储入口测试曾反复失败。触控诊断确认事件送达了正确的 Activity，点击坐标位于正确行内，但列表 bounds 在点击时仍逐帧变化；惯性滚动消费了第一次点击。只等待可访问性空闲不足以证明滚动停止。

无效的列表布局试改已撤回，临时触控日志与可访问性点击诊断也已撤除。测试改为拖动到终点后保持手指静止再松开，避免 fling，然后执行原物理点击、分类展开、书名章节标识及路径按需显示的断言。最终本地完整回归及标准 CI 均通过；没有跳过或弱化该用例。

### 手机规模与备份样本

小米 9 SE 的 Debug 包连续阅读 **7,200.510 秒**，四种阅读模式循环，定期检查 Ready 状态、保存位置及退出重进；发出 **7,242 次左右翻页操作**，滚动操作另计。共 242 个 PSS 样本，范围 **157,498–185,722KiB**，最后一次为 **167,714KiB**。结果 `complete=true`，JUnit 正常通过。此数据来自仪器测试及固定测试文本，不是正式 APK 的耗电、全部场景内存上限或帧率结论。

100 本、每本 1,000 章；Debug 包内同设备依次执行旧流程（解码/编码完整 Book 后写入）与独立进度写入，预热一次后各采集 30 次。单次写入中位数分别为 **69,127µs 与 13,178µs**，记录负载为 **90,967 与 118 字节**。这只比较局部写入，不代表不同正式 APK 的整机、启动或耗电性能；同一 records 表仍触发 Room 查询刷新，但未变化的目录不重复解码。

500MiB 样本由 5 个各 100MiB 文件组成，执行生成、导出、删除原文件、恢复、逐文件哈希对照、超限导出拒绝及取消清理，总计 **34,370ms**，ZIP 为 **524,455,090 字节**。PSS 基线 **92,685KiB**，250ms 采样峰值 **95,001KiB（约 92.8MiB）**。采样可能遗漏短峰，不能推广为全局内存上限。

### 正式包验证

最终签名包来自 GitHub Actions `38850341215`，目标提交 `249f4d121c99ae34233cd744782ade84e94cd8f6`；该提交的 Android checks `38849816066` 通过。包名 `io.github.godbook.wanjuan`，versionCode 103 / versionName 0.1.3，**5,079,237 字节**，原发布签名一致。更新清单与 SHA256SUMS 均与实际 APK 匹配。

[v0.1.3](https://github.com/GodBook/wanjuan/releases/tag/v0.1.3) 已于 10-16 公开，并确认为 latest 稳定版本。公开下载的 APK 与确切测试包哈希一致，标签指向上述源码提交。

APK SHA-256：`9e6e7b340984581cc8ae843a28ef27ff2b88efa73f4e4499077a72fcd08828f7`。本地确切产物为 `artifacts/release-0.1.3-verified/`，此前候选不作为最终发布包。

API 29 在先前恢复真实 0.1.2 备份的正式书库上覆盖安装最终 APK，2 本书保留；320×640 下《山间来信》第 1 章第 2/20 页前后一致。最终 APK 的设置入口、存储分类展开、书名与章节、按需路径及正在使用文件保护均经实际界面验收。

手机正式 0.1.2 升级前重新记录书架与第 1 章第 2/11 页，并通过系统文件选择器导出当前格式 1 备份（13,779 字节）。使用上述确切 APK 经 adb 原签名覆盖安装到 0.1.3 / 103，2 本书保留，下一页与上一页操作正常；强制停止后重新打开仍为第 2/11 页。正式包的存储分类及书名章节显示也经手机实际界面验收。

升级后再次导出格式 2 备份（13,817 字节），两份备份均逐条通过 SHA-256 校验。对照确认阅读 profile、所有旧 preferences、书籍身份与元数据、完整目录，以及原有书源、笔记、预设、历史记录均保留。本轮手机没有有效网络，使用 adb 覆盖安装，不将其写成再次完成了应用内在线更新。

验收后已卸载手机临时调试包和测试包，清理临时 UI 抓取文件；正式应用及两份备份保留。本任务模拟器已恢复显示和动画设置并关闭，临时防电脑自动睡眠辅助已退出。

### 环境、失败记录与验证范围

- 手机在 10-16 无有效网络。全联网运行中的维基域名解析及 WorkManager 网络约束等待两项失败已保留；离线套件显式排除这两个依赖网络状态的用例。本轮网络部分由 API 29 模拟器复验，不将不可达环境写成手机联网通过。
- API 29 本地镜像初始 Java 堆增长上限为 16MiB，系统界面反复无响应。仅在本任务模拟器设置 ART 增长上限 256MiB、最大堆 512MiB 并重启运行时；GitHub CI 使用标准 API 29 镜像，手机配置未改。
- 正向传输测试的模拟服务端最初同时保留两份 8MiB 数据，超过该镜像原始 Java 堆。释放已验证上传缓存后，以原数据量和校验标准重跑；未修改应用堆配置。
- 首轮长时探针约 49 分钟处中断，`complete=false`，保存在 `013-soak-interrupted.json`，不能计为两小时通过。
- 首轮发现的 Android canonical 路径别名引用保护错误已修复。并发忙碌状态测试改用显式完成信号，消除固定延时造成的竞态。

六个经典版本的真实联网检查包含目录数量及首、中、末章正文；仅代表测试时网络可用。用户私有 WebDAV 服务、完整 TalkBack、实际磁盘耗尽、长期耗电与所有厂商系统仍未覆盖。

证据保存在忽略提交的 `artifacts/`：`013-phone-publish-final.log`、`013-phone-final-recovery.log`、`013-api29-release-final.log`、`013-api29-final-process-recovery.log`、`013-progress-benchmark.json`、`013-large-backup-benchmark.json`、`013-touch-owner-logcat.txt`、`013-phone-soak-final.log`、`013-phone-soak-completed.json`、`013-official-upgrade-comparison.json`、升级前后备份及界面 XML。正式包位于 `release-0.1.3-verified/`。

复现入口：`scripts/test-device.ps1 -Serial <序列号> -LiveSources`、`scripts/verify-recovery.ps1 -Serial <序列号>`。长时、性能及大备份探针需显式参数，参见 `AcceptanceProbeTest.kt`。恢复及规模测试仅使用独立 `.debug` 包，不覆盖正式书库。

手机无网络时，在安装调试包及测试包后使用以下明确过滤；这两个用例需在有网络约束满足的设备另行通过，不能用过滤后的结果代替：

```shell
adb -s <序列号> shell am instrument -w -r -e notClass 'io.github.godbook.wanjuan.BuiltinSourcesTest#liveBuiltinCatalogsAndChapters,io.github.godbook.wanjuan.UpdateReliabilityTest#pausedDownloadPreservesMobilePolicyAndSkipsCachedChapter' io.github.godbook.wanjuan.debug.test/androidx.test.runner.AndroidJUnitRunner
```

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
