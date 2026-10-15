package io.github.godbook.wanjuan.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

data class StorageEntry(val path: String,val bytes: Long,val category: String,val removable: Boolean)

class Storage(private val library: Library) {
    suspend fun scan(): List<StorageEntry> = withContext(Dispatchers.IO) {library.fileOperations.withLock {library.mutation.withLock {scanLocked()}}}
    private suspend fun scanLocked(): List<StorageEntry> {
        val books=library.dao.list("book").map {Book.decode(it.payload)}
        val bookDirectories=books.flatMap {it.chapters}.filter {it.file.startsWith("books/")}.map {it.file.split('/').take(2).joinToString("/")+"/"}.toSet()
        val offline=library.dao.list("offline").map {it.id}.toSet()
        val assets=mutableSetOf<String>()
        fun reference(raw: String) {
            if(raw.isBlank()) return
            val json=JSONObject(raw)
            listOf("font","background").forEach {key -> json.optString(key).takeIf {it.isNotBlank()}?.let {
                val f=File(it).canonicalFile
                if(f.path.startsWith(library.root.canonicalPath+File.separator)) assets+=f.relativeTo(library.root.canonicalFile).invariantSeparatorsPath
            }}
        }
        reference(library.profile.first().json().toString());books.forEach {reference(it.profile)}
        library.dao.list("preset").forEach {reference(it.payload)}
        return library.root.walkTopDown().filter {it.isFile}.map {file ->
            val path=file.relativeTo(library.root).invariantSeparatorsPath
            val category=when {
                path.startsWith("cache/") -> if(file.nameWithoutExtension in offline) "已下载正文" else "临时缓存"
                path.startsWith("books/") -> if(bookDirectories.any {path.startsWith(it)}) "本地书籍" else "未使用书籍文件"
                path in assets -> "正在使用的字体与背景"
                else -> "未使用资源"
            }
            StorageEntry(path,file.length(),category,category !in setOf("本地书籍","正在使用的字体与背景"))
        }.toList()
    }
    /** Re-evaluate references under the same lock immediately before deleting selected paths. */
    suspend fun delete(paths: Set<String>): Long = withContext(Dispatchers.IO) {library.fileOperations.withLock {library.mutation.withLock {
        val entries=scanLocked().filter {it.path in paths}
        require(entries.size==paths.size && entries.all {it.removable}) {"文件状态已变化，请刷新后重新选择"}
        var removed=0L
        entries.forEach {entry ->
            val f=library.file(entry.path)
            if(f.delete()) {
                removed+=entry.bytes
                if(entry.path.startsWith("cache/")) library.dao.delete("offline",f.nameWithoutExtension)
            }
        }
        removed
    }}}
}
