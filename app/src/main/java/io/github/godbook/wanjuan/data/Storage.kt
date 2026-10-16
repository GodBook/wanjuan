package io.github.godbook.wanjuan.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

data class StorageEntry(val path: String,val bytes: Long,val category: String,val removable: Boolean,val label: String,val references: String = "")

class Storage(private val library: Library) {
    suspend fun scan(): List<StorageEntry> = withContext(Dispatchers.IO) {library.fileOperations.withLock {library.mutation.withLock {scanLocked()}}}
    private suspend fun scanLocked(): List<StorageEntry> {
        val books=library.dao.list("book").map {Book.decode(it.payload)}
        val localChapters=books.flatMap {book -> book.chapters.filter {it.file.isNotBlank()}.map {it.file to (book to it)}}.toMap()
        val chapters=books.flatMap {book -> book.chapters.map {it.id to (book to it)}}.toMap()
        val bookDirectories=localChapters.filterKeys {it.startsWith("books/")}.map {(path,owner) -> path.split('/').take(2).joinToString("/")+"/" to owner.first}.toMap()
        val offline=library.dao.list("offline").map {it.id}.toSet()
        val assets=mutableMapOf<String,MutableSet<String>>()
        fun reference(raw: String,owner: String) {
            if(raw.isBlank()) return
            val json=JSONObject(raw)
            listOf("font","background").forEach {key -> json.optString(key).takeIf {it.isNotBlank()}?.let {
                val f=File(it).canonicalFile
                if(f.path.startsWith(library.root.canonicalPath+File.separator)) assets.getOrPut(f.relativeTo(library.root.canonicalFile).invariantSeparatorsPath) {mutableSetOf()}.add(owner)
            }}
        }
        reference(library.profile.first().json().toString(),"全局阅读设置");books.forEach {reference(it.profile,"书籍：${it.title}")}
        library.dao.list("preset").forEach {reference(it.payload,"预设：${it.id}")}
        return library.root.walkTopDown().filter {it.isFile}.map {file ->
            val path=file.relativeTo(library.root).invariantSeparatorsPath
            val local=localChapters[path]
            val bookOwner=bookDirectories.entries.firstOrNull {path.startsWith(it.key)}?.value
            val cached=if(path.startsWith("cache/")) chapters[file.nameWithoutExtension] else null
            val category=when {
                local!=null || bookOwner!=null -> "本地书籍"
                path.startsWith("cache/") -> if(file.nameWithoutExtension in offline) "已下载正文" else "临时缓存"
                path.startsWith("books/") -> "未使用书籍文件"
                path in assets -> "正在使用的字体与背景"
                else -> "未使用资源"
            }
            val label=when {
                local!=null -> "${local.first.title} · ${local.second.title}"
                bookOwner!=null -> "${bookOwner.title} · ${if(file.name.startsWith("original.")) "原始 ${file.extension.uppercase()} 文件" else "书内图片与资源"}"
                cached!=null -> "${cached.first.title} · ${cached.second.title}"
                file.name.startsWith("font-") || file.extension in setOf("ttf","otf") -> "自定义字体"
                file.name.startsWith("background-") -> "阅读背景"
                category=="未使用书籍文件" -> when {
                    file.name.startsWith("original.") -> "已移出书架的原始 ${file.extension.uppercase()} 文件"
                    file.nameWithoutExtension.toIntOrNull()!=null -> "未使用章节 · 第 ${file.nameWithoutExtension.toInt()+1} 节"
                    else -> "未使用的书内图片与资源"
                }
                category in setOf("已下载正文","临时缓存") -> "未关联书架的正文缓存"
                else -> "未使用的阅读资源"
            }
            StorageEntry(path,file.length(),category,category !in setOf("本地书籍","正在使用的字体与背景"),label,assets[path]?.joinToString("、").orEmpty())
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
