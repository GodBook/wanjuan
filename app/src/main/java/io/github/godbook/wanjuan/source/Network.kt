package io.github.godbook.wanjuan.source

import io.github.godbook.wanjuan.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resumeWithException
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.io.File
import java.io.InputStream
import io.github.godbook.wanjuan.data.copyChecked

class Network(private val http: Boolean = false, private val privateHost: Boolean = false) {
    private fun validate(url: HttpUrl) {
        require(url.isHttps || http) { "此书源使用 HTTP，请在书源管理中允许明文连接" }
        require(url.username.isEmpty() && url.password.isEmpty()) { "地址不能包含用户名或密码" }
    }
    val client: OkHttpClient get() = if(privateHost) privateClient else publicClient
    companion object {
    private val publicClient by lazy { buildClient(false) }
    private val privateClient by lazy { buildClient(true) }
    private fun buildClient(privateHost: Boolean) = OkHttpClient.Builder().connectTimeout(10,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS)
        .callTimeout(30,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
        .dns(object: Dns { override fun lookup(hostname: String): List<InetAddress> = Dns.SYSTEM.lookup(hostname).also { addresses ->
            if (!privateHost && !(BuildConfig.DEBUG && hostname in listOf("localhost","127.0.0.1"))) {
                require(addresses.none { it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress || it.isSiteLocalAddress || it.isMulticastAddress || (it.address.size == 16 && (it.address[0].toInt() and 0xfe) == 0xfc) }) { "书源不能访问本地或私有网络" }
            }
        } }).build()
    }
    private suspend fun <T> stream(url: String, headers: Map<String,String>, method: String, body: RequestBody?, transfer: Boolean = false, consume: suspend (InputStream)->T): T = withContext(Dispatchers.IO) {
        var current = url.toHttpUrl()
        var currentHeaders = headers
        repeat(6) {
            validate(current)
            val req = Request.Builder().url(current).method(method,body).apply { currentHeaders.forEach { (k,v) -> header(k,v) } }.build()
            val response = suspendCancellableCoroutine<Response> { cont ->
                val activeClient=if(transfer) client.newBuilder().callTimeout(10,TimeUnit.MINUTES).build() else client
                val call=activeClient.newCall(req)
                cont.invokeOnCancellation { call.cancel() }
                call.enqueue(object: Callback {
                    override fun onFailure(call: Call,e: java.io.IOException) { if(cont.isActive) cont.resumeWithException(e) }
                    override fun onResponse(call: Call,response: Response) { cont.resume(response) { response.close() } }
                })
            }
            response.use {
                if (response.code in listOf(301,302,303,307,308)) {
                    require(method == "GET" || method == "HEAD") { "写入操作不自动跟随重定向" }
                    val next = current.resolve(response.header("Location") ?: error("重定向缺少地址")) ?: error("重定向地址无效")
                    if (next.host != current.host || next.port != current.port || next.scheme != current.scheme) currentHeaders = emptyMap()
                    current = next
                } else {
                    require(response.isSuccessful) { "HTTP ${response.code}：${if(response.code == 429) "请求过于频繁，请稍后重试" else "服务器请求失败"}" }
                    val stream = response.body?.byteStream() ?: error("响应为空")
                    return@withContext consume(stream)
                }
            }
        }
        error("重定向次数过多")
    }
    suspend fun bytes(url: String, headers: Map<String,String> = emptyMap(), method: String = "GET", body: RequestBody? = null, limit: Long = 8L*1024*1024): ByteArray =
        stream(url,headers,method,body) {input -> java.io.ByteArrayOutputStream().also {input.copyChecked(it,limit)}.toByteArray()}

    suspend fun download(url: String, file: File, headers: Map<String,String> = emptyMap(), limit: Long = 512L*1024*1024, progress: (Long)->Unit = {}): String =
        stream(url,headers,"GET",null,true) {input ->
            val temp=File(file.parentFile,file.name+".partial")
            try {
                val hash=temp.outputStream().buffered().use {input.copyChecked(it,limit,progress).second}
                java.nio.file.Files.move(temp.toPath(),file.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                hash
            } finally {temp.delete()}
        }
    suspend fun upload(url: String,file: File,headers: Map<String,String>): Unit = stream(url,headers,"PUT",file.asRequestBody("application/zip".toMediaType()),true) {input ->
        input.copyChecked(object: java.io.OutputStream() {override fun write(b: Int) {};override fun write(b: ByteArray,off: Int,len: Int) {}},8L*1024*1024)
        Unit
    }
    suspend fun text(url: String, headers: Map<String,String> = emptyMap(), method: String = "GET", body: RequestBody? = null): String {
        val b = bytes(url,headers,method,body)
        val head = b.take(2048).toByteArray().toString(Charsets.ISO_8859_1)
        val charset = Regex("charset\\s*=\\s*[\"']?([a-zA-Z0-9_-]+)",RegexOption.IGNORE_CASE).find(head)?.groupValues?.get(1)
        return b.toString(runCatching { java.nio.charset.Charset.forName(charset ?: "UTF-8") }.getOrDefault(Charsets.UTF_8))
    }
}
