package io.github.godbook.wanjuan.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import io.github.godbook.wanjuan.BuildConfig
import io.github.godbook.wanjuan.data.sha256
import io.github.godbook.wanjuan.source.Network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

data class Release(val code: Int, val version: String, val notes: String, val url: String, val sha: String)
class Updater(private val context: Context) {
    val apk get() = File(context.cacheDir,"updates/wanjuan.apk")
    suspend fun check(): Release? = withContext(Dispatchers.IO) {
        val network=Network()
        val api="https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest"
        val json=JSONObject(network.text(api,mapOf("Accept" to "application/vnd.github+json")))
        val tag=json.getString("tag_name")
        require(tag.matches(Regex("v[0-9]+\\.[0-9]+\\.[0-9]+"))) { "发布版本标签无效" }
        val manifestUrl="https://github.com/${BuildConfig.UPDATE_REPO}/releases/download/$tag/update.json"
        parseManifest(network.text(manifestUrl),BuildConfig.VERSION_CODE)
    }
    fun parseManifest(text: String, current: Int): Release? {
        val j=JSONObject(text)
        require(j.getString("packageName") == "io.github.godbook.wanjuan") { "更新包名不匹配" }
        val code=j.getInt("versionCode")
        if(code<=current) return null
        val url=j.getString("apkUrl")
        require(url.startsWith("https://github.com/${BuildConfig.UPDATE_REPO}/releases/download/") && url.endsWith(".apk")) { "更新地址不受信任" }
        val digest=j.getString("sha256").lowercase()
        require(digest.matches(Regex("[0-9a-f]{64}"))) { "更新校验值无效" }
        return Release(code,j.getString("versionName"),j.optString("notes"),url,digest)
    }
    suspend fun download(release: Release): File = withContext(Dispatchers.IO) {
        val bytes=Network().bytes(release.url,limit=100L*1024*1024)
        require(sha256(bytes)==release.sha) { "安装包校验失败，请重新下载" }
        apk.parentFile?.mkdirs()
        val temp=File(apk.parentFile,"download.tmp")
        try {
            temp.writeBytes(bytes)
            verify(temp,release.code)
            java.nio.file.Files.move(temp.toPath(),apk.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            apk
        } finally { temp.delete() }
    }
    fun verify(file: File, expectedCode: Int? = null) {
        val pm=context.packageManager
        val candidate=pm.getPackageArchiveInfo(file.path,PackageManager.GET_SIGNING_CERTIFICATES) ?: error("APK 文件无法解析")
        val own=pm.getPackageInfo(context.packageName,PackageManager.GET_SIGNING_CERTIFICATES)
        require(candidate.packageName == context.packageName) { "APK 包名不匹配；调试版不能安装正式版更新" }
        require(candidate.longVersionCode>own.longVersionCode) { "拒绝安装旧版本" }
        require(expectedCode == null || candidate.longVersionCode == expectedCode.toLong()) { "APK 与版本清单不一致" }
        val current=own.signingInfo?.apkContentsSigners?.map { sha256(it.toByteArray()) }?.toSet().orEmpty()
        val incoming=candidate.signingInfo?.apkContentsSigners?.map { sha256(it.toByteArray()) }?.toSet().orEmpty()
        require(current.isNotEmpty() && current==incoming) { "APK 签名与已安装版本不同" }
    }
    fun install() {
        verify(apk)
        if(!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val uri=FileProvider.getUriForFile(context,context.packageName+".files",apk)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
