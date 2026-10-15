package io.github.godbook.wanjuan.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.godbook.wanjuan.source.Network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

data class DavConfig(val url: String, val username: String, val password: String, val allowPrivate: Boolean = false)
class WebDav(private val context: Context) {
    private val deviceId: String get() {
        val prefs=context.getSharedPreferences("backup-device",Context.MODE_PRIVATE)
        return prefs.getString("id",null) ?: uid().replace("-","").take(12).also {prefs.edit().putString("id",it).apply()}
    }
    private val credentials=File(context.filesDir,"webdav.enc")
    private fun key(): java.security.Key {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if(!store.containsAlias("wanjuan-dav")) KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("wanjuan-dav",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
        return store.getKey("wanjuan-dav",null)
    }
    fun save(config: DavConfig) {
        require(config.url.toHttpUrl().isHttps) { "WebDAV 必须使用 HTTPS" }
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key()) }
        val bytes=JSONObject().put("url",config.url).put("username",config.username).put("password",config.password).put("private",config.allowPrivate).toString().toByteArray()
        credentials.writeBytes(cipher.iv+cipher.doFinal(bytes))
    }
    fun load(): DavConfig? = if(!credentials.exists()) null else runCatching {
        val bytes=credentials.readBytes()
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12))) }
        val j=JSONObject(cipher.doFinal(bytes.copyOfRange(12,bytes.size)).toString(Charsets.UTF_8))
        DavConfig(j.getString("url"),j.getString("username"),j.getString("password"),j.optBoolean("private"))
    }.getOrNull()
    private fun base(c: DavConfig)=c.url.trimEnd('/')+"/"
    private fun headers(c: DavConfig)=mapOf("Authorization" to Credentials.basic(c.username,c.password))
    suspend fun upload(file: File): String = withContext(Dispatchers.IO) {
        val c=load() ?: error("请先配置 WebDAV")
        val name="wanjuan-${System.currentTimeMillis()}-${deviceId}.zip"
        val url=base(c)+name
        val bytes=file.readBytes()
        val network=Network(privateHost=c.allowPrivate)
        network.bytes(url,headers(c),"PUT",bytes.toRequestBody("application/zip".toMediaType()))
        val remote=network.bytes(url,headers(c),limit=512L*1024*1024)
        require(sha256(bytes)==sha256(remote)) { "远端备份校验失败" }
        name
    }
    suspend fun list(): List<String> = withContext(Dispatchers.IO) {
        val c=load() ?: error("请先配置 WebDAV")
        val xml=Network(privateHost=c.allowPrivate).text(base(c),headers(c)+("Depth" to "1"),"PROPFIND","<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:displayname/></d:prop></d:propfind>".toRequestBody("application/xml".toMediaType()))
        Jsoup.parse(xml,"",Parser.xmlParser()).allElements.filter { it.tagName().substringAfter(':') == "href" }.map { it.text().substringAfterLast('/') }
            .filter { it.matches(Regex("wanjuan-[0-9]+-[a-zA-Z0-9]+\\.zip")) }.distinct().sortedDescending()
    }
    suspend fun download(name: String): File = withContext(Dispatchers.IO) {
        require(name.matches(Regex("wanjuan-[0-9]+-[a-zA-Z0-9]+\\.zip")))
        val c=load() ?: error("请先配置 WebDAV")
        File(context.cacheDir,"restore-webdav.zip").apply { writeBytes(Network(privateHost=c.allowPrivate).bytes(base(c)+name,headers(c),limit=512L*1024*1024)) }
    }
    suspend fun retainLatest(count: Int=10) {
        val c=load() ?: return
        list().filter {it.endsWith("-$deviceId.zip")}.drop(count).forEach {name -> Network(privateHost=c.allowPrivate).bytes(base(c)+name,headers(c),"DELETE")}
    }
}
