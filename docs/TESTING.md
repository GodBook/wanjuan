# 验证记录

本文件由实际执行结果更新；不将源码中的测试用例等同于已经通过。

## 0.1.1 回归验证（2026-10-15）

- JVM：14 项通过，含古登堡硬换行、分章、卷首制作说明、特殊数字、错误页面与维基导航清理 / 表格正文保留回归。
- Android Lint Debug：通过，无错误；弃用 API 等警告仍保留。
- 小米 9 SE / Android 15：10 项仪器测试全部通过，38.104 秒。包含真实联网书源验证。
- Android 10 / API 29：10 项全部通过，66.866 秒，本轮也显式启用并通过真实联网书源检查。
- 阅读界面覆盖三种分页模式的快速往返翻页、上下滚动、书签、设置及重进后的进度；Android 10 的自动化明确等待正文排版完成再点击。
- 内置书源初始化、关闭后不重新启用、删除后不自动恢复、手动补回、导出识别与备份恢复通过验证。

真实联网检查：维基文库《红楼梦》120 回、《西游记》100 回、《三国演义》120 回、《儒林外史》56 回；古登堡《红楼梦》120 回、《西游记》100 回。六个版本均验证目录数量及首章、中间章、末章正文。只代表当次网络环境可用，不等于所有地区、所有时段可用。

本地证据：`artifacts/011-phone-final.log`、`artifacts/011-android10-live-final.log`、`artifacts/011-final-source-build.log`，以及 JVM XML。联网测试默认跳过，发布验收时使用 `am instrument -w -r -e liveSources true io.github.godbook.wanjuan.debug.test/androidx.test.runner.AndroidJUnitRunner` 显式启用。

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
