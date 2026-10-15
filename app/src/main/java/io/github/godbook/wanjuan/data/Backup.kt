package io.github.godbook.wanjuan.data

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.*
import java.io.*
import java.util.zip.*

class Backup(private val library: Library) {
    suspend fun create(includeBooks: Boolean = true): File = withContext(Dispatchers.IO) { library.mutation.withLock {
        val records = library.dao.all().filter { it.kind !in setOf("download","offline","restore") }
        val state = JSONObject().put("version",1).put("created",System.currentTimeMillis()).put("profile",library.profile.first().json()).put("preferences",library.preferences.first())
            .put("records",JSONArray(records.map { JSONObject().put("kind",it.kind).put("id",it.id).put("payload",it.payload) }))
        val target = File(library.context.cacheDir,"wanjuan-${System.currentTimeMillis()}.zip")
        val hashes = JSONObject()
        ZipOutputStream(target.outputStream()).use { zip ->
            fun add(name: String, bytes: ByteArray) { hashes.put(name,sha256(bytes)); zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            add("state.json",state.toString().toByteArray())
            library.root.walkTopDown().filter { it.isFile }.forEach { f ->
                val path=f.relativeTo(library.root).invariantSeparatorsPath
                if(!path.startsWith("cache/") && (includeBooks || !path.startsWith("books/"))) add("library/$path",f.readBytes())
            }
            zip.putNextEntry(ZipEntry("manifest.json")); zip.write(hashes.toString().toByteArray()); zip.closeEntry()
        }
        target
    } }
    suspend fun inspect(file: File): String = withContext(Dispatchers.IO) {
        ZipFile(file).use { zip ->
            val state=JSONObject(zip.getInputStream(zip.getEntry("state.json") ?: error("不是万卷备份")).use { it.readBytesLimited(16*1024*1024).toString(Charsets.UTF_8) })
            require(state.getInt("version") == 1) { "不支持此备份版本" }
            val records=state.getJSONArray("records").objects()
            "书籍 ${records.count { it.getString("kind") == "book" }} 本 · 书源 ${records.count { it.getString("kind") == "source" }} 个\n恢复会替换当前书架与设置，并保留恢复前快照。"
        }
    }
    suspend fun restore(file: File) = withContext(Dispatchers.IO) {
        inspect(file)
        val stage = File(library.context.filesDir,"restore-${uid()}").apply { mkdirs() }
        try {
            ZipFile(file).use { zip ->
                require(zip.size() <= 20000) { "备份条目过多" }
                val manifest=JSONObject(zip.getInputStream(zip.getEntry("manifest.json") ?: error("备份缺少校验清单")).use { it.readBytesLimited(4*1024*1024).toString(Charsets.UTF_8) })
                val seen=mutableSetOf<String>(); var total=0L
                zip.entries().asSequence().filter { !it.isDirectory && it.name != "manifest.json" }.forEach { entry ->
                    require(seen.add(entry.name)) { "备份有重复条目" }
                    require(entry.name == "state.json" || entry.name.startsWith("library/")) { "备份有未知文件" }
                    val target=File(stage,entry.name).canonicalFile
                    require(target.path.startsWith(stage.canonicalPath+File.separator)) { "非法备份路径" }
                    val bytes=zip.getInputStream(entry).use { it.readBytesLimited(100*1024*1024) }
                    total+=bytes.size; require(total<=512L*1024*1024) { "备份解压超过 512MB" }
                    require(sha256(bytes) == manifest.getString(entry.name)) { "备份校验失败：${entry.name}" }
                    target.parentFile?.mkdirs(); target.writeBytes(bytes)
                }
                require(seen == manifest.keys().asSequence().toSet()) { "备份清单不完整" }
            }
            val state=JSONObject(File(stage,"state.json").readText())
            fun portableProfile(raw: String): String {
                if(raw.isBlank()) return raw
                val j=JSONObject(raw)
                listOf("font","background").forEach {key -> if(j.has(key)) {
                    val oldPath=j.optString(key)
                    val relative=oldPath.substringAfter("/library/","")
                    j.put(key,if(relative.isBlank()) "" else library.file(relative).path)
                } }
                return j.toString()
            }
            val records=state.getJSONArray("records").objects().map {
                val kind=it.getString("kind");val raw=it.getString("payload")
                val payload=when(kind) {"book" -> Book.decode(raw).let {b ->b.copy(profile=portableProfile(b.profile)).encode()};"preset" -> portableProfile(raw);else ->raw}
                Record(kind,it.getString("id"),payload)
            }
            require(records.map { it.kind+":"+it.id }.distinct().size == records.size) { "重复数据记录" }
            records.forEach { r -> when(r.kind) {
                "book" -> { val b=Book.decode(r.payload); require(b.id==r.id); b.chapters.filter { it.file.isNotBlank() }.forEach { library.file(it.file) } }
                "source" -> require(Source.decode(r.payload).id==r.id)
                "mark" -> require(Mark.decode(r.payload).id==r.id)
                "history","preset" -> Unit
                else -> error("未知记录类型：${r.kind}")
            } }
            val snapshot=create()
            val previous=File(library.context.filesDir,"before-restore.zip")
            snapshot.copyTo(previous,true); snapshot.delete()
            library.mutation.withLock {
                val old=File(library.context.filesDir,"restore-previous-${uid()}")
                val oldProfile=library.profile.first()
                val oldPreferences=library.preferences.first().toString()
                val staged=File(stage,"library").apply { mkdirs() }
                val token=uid()
                val journal=android.util.AtomicFile(File(library.context.filesDir,"restore-journal.json"))
                val journalStream=journal.startWrite()
                try {journalStream.write(JSONObject().put("previous",old.name).put("token",token).put("profile",oldProfile.json().toString()).put("preferences",oldPreferences).toString().toByteArray());journal.finishWrite(journalStream)} catch(e:Exception) {journal.failWrite(journalStream);throw e}
                check(library.root.renameTo(old)) { "无法暂存原书库" }
                try {
                    check(staged.renameTo(library.root)) { "无法安装恢复文件" }
                    library.db.withTransaction { library.dao.clear(); library.dao.putAll(records);library.dao.put(Record("restore","marker",token)); library.saveProfile(Profile.from(portableProfile(state.getJSONObject("profile").toString())));library.replacePreferences(state.optJSONObject("preferences")?.toString() ?: "{}") }
                    old.deleteRecursively()
                    journal.delete()
                } catch(e: Exception) {
                    library.root.deleteRecursively(); old.renameTo(library.root); library.saveProfile(oldProfile);library.replacePreferences(oldPreferences);journal.delete(); throw e
                }
            }
        } finally { stage.deleteRecursively() }
    }
}
