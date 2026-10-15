package io.github.godbook.wanjuan.source

import io.github.godbook.wanjuan.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resumeWithException
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class Network(private val http: Boolean = false, private val privateHost: Boolean = false) {
    private fun validate(url: HttpUrl) {
        require(url.isHttps || http) { "此书源使用 HTTP，请在书源管理中允许明文连接" }
        require(url.username.isEmpty() && url.password.isEmpty()) { "地址不能包含用户名或密码" }
    }
    val client = OkHttpClient.Builder().connectTimeout(10,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS)
        .callTimeout(30,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
        .dns(object: Dns { override fun lookup(hostname: String): List<InetAddress> = Dns.SYSTEM.lookup(hostname).also { addresses ->
            if (!privateHost && !(BuildConfig.DEBUG && hostname in listOf("localhost","127.0.0.1"))) {
                require(addresses.none { it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress || it.isSiteLocalAddress || it.isMulticastAddress || (it.address.size == 16 && (it.address[0].toInt() and 0xfe) == 0xfc) }) { "书源不能访问本地或私有网络" }
            }
        } }).build()
    suspend fun bytes(url: String, headers: Map<String,String> = emptyMap(), method: String = "GET", body: RequestBody? = null, limit: Long = 8L*1024*1024): ByteArray = withContext(Dispatchers.IO) {
        var current = url.toHttpUrl()
        var currentHeaders = headers
        repeat(6) {
            validate(current)
            val req = Request.Builder().url(current).method(method,body).apply { currentHeaders.forEach { (k,v) -> header(k,v) } }.build()
            val response = suspendCancellableCoroutine<Response> { cont ->
                val call=client.newCall(req)
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
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) { val n = stream.read(buffer); if (n < 0) break; require(output.size().toLong()+n <= limit) { "内容超过大小限制" }; output.write(buffer,0,n) }
                    return@withContext output.toByteArray()
                }
            }
        }
        error("重定向次数过多")
    }
    suspend fun text(url: String, headers: Map<String,String> = emptyMap(), method: String = "GET", body: RequestBody? = null): String {
        val b = bytes(url,headers,method,body)
        val head = b.take(2048).toByteArray().toString(Charsets.ISO_8859_1)
        val charset = Regex("charset\\s*=\\s*[\"']?([a-zA-Z0-9_-]+)",RegexOption.IGNORE_CASE).find(head)?.groupValues?.get(1)
        return b.toString(runCatching { java.nio.charset.Charset.forName(charset ?: "UTF-8") }.getOrDefault(Charsets.UTF_8))
    }
}
