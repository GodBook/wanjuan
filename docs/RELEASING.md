# 发布与在线更新

正式包名：`io.github.godbook.wanjuan`。最低 API 29，目标 / 编译 API 35。

## 签名

每次升级必须使用同一发布密钥。密钥在仓库外生成和备份，不能重新生成后覆盖旧密钥。`signing.properties`、`.jks`、`.keystore` 已列入忽略规则；PKCS12 文件同样不得提交。

本地 `signing.properties` 格式：

```properties
storeFile=/absolute/private/path/wanjuan-release.p12
storePassword=YOUR_LOCAL_SECRET
keyAlias=wanjuan
keyPassword=YOUR_LOCAL_SECRET
```

发布构建：

```shell
./gradlew :app:assembleRelease -PappVersionCode=100 -PappVersionName=0.1.0
```

每次发布提升 `versionCode`，版本名与 Git 标签一致（如 `v0.1.0`）。使用 `scripts/release.ps1` 生成 APK 校验文件和更新清单。推送签名构建所对应的源码提交。

## 更新协议

1. GET GitHub Releases latest API。
2. 验证稳定版本标签，读取该标签下 `update.json`。
3. 比较 `versionCode`，只接受大于当前版本的发布。
4. 下载本仓库该 Release 的 APK。
5. 校验 SHA-256、APK 实际包名、实际版本和当前安装签名。
6. 通过 FileProvider 和系统安装器发起升级；用户明确确认安装。

不静默安装，不信任其他仓库的 APK，不允许降级。调试签名不能升级正式包，测试在线更新必须用同一发布签名构建低版本。

`update.json`：

```json
{
  "packageName": "io.github.godbook.wanjuan",
  "versionCode": 100,
  "versionName": "0.1.0",
  "apkUrl": "https://github.com/GodBook/wanjuan/releases/download/v0.1.0/wanjuan-0.1.0.apk",
  "sha256": "由发布脚本计算的64位十六进制SHA256",
  "notes": "版本说明"
}
```

## GitHub Actions

CI 构建调试包、运行 JVM 测试和 Android Lint。发布工作流使用仓库 Secrets：`ANDROID_KEYSTORE_BASE64`、`ANDROID_KEY_PASSWORD`。密钥别名为 `wanjuan`，密钥与仓库密码相同。工作流只接受手动指定的版本，构建后上传草稿 Release APK 与清单。完成设备验证后发布草稿，应用才会收到更新。

PR 不接触发布密钥。删除或更换 Secrets 前先验证有可恢复的离线密钥副本。不要删除旧版 Release，现有用户可能仍需下载。
