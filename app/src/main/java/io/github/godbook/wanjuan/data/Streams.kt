package io.github.godbook.wanjuan.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** A bounded stream and digest; the buffer size is independent of the input size. */
suspend fun InputStream.copyChecked(output: OutputStream, limit: Long, progress: (Long)->Unit = {}): Pair<Long,String> {
    val digest=MessageDigest.getInstance("SHA-256")
    val buffer=ByteArray(64*1024)
    var total=0L
    while(true) {
        currentCoroutineContext().ensureActive()
        val n=read(buffer)
        if(n<0) break
        total+=n
        require(total<=limit) { "内容超过大小限制" }
        output.write(buffer,0,n);digest.update(buffer,0,n);progress(total)
    }
    return total to digest.digest().joinToString("") {"%02x".format(it)}
}

suspend fun fileHash(file: File): String = file.inputStream().use {it.copyChecked(object: OutputStream() {
    override fun write(b: Int) {}
    override fun write(b: ByteArray,off: Int,len: Int) {}
},Long.MAX_VALUE).second}
