package io.github.godbook.wanjuan.data

import android.util.AtomicFile
import androidx.room.withTransaction
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import org.json.*
import java.io.*
import java.util.zip.*

class Backup(private val library: Library, private val checkpoint: (String)->Unit = {}) {
    suspend fun create(includeBooks: Boolean = true,includeOffline: Boolean = true,progress: (String)->Unit = {}): File =
        withContext(Dispatchers.IO) {library.fileOperations.withLock {library.mutation.withLock {createLocked(includeBooks,includeOffline,progress)}}}

    private suspend fun createLocked(includeBooks: Boolean,includeOffline: Boolean,progress: (String)->Unit): File {
        val all=library.dao.all()
        val positions=all.filter {it.kind=="progress"}.associate {it.id to ReadingProgress.decode(it.payload)}
        val books=all.filter {it.kind=="book"}.map {Book.decode(it.payload).let {b -> positions[b.id]?.apply(b) ?: b}}
        val chapters=books.flatMap {it.chapters}.map {it.id}.toSet()
        val offline=if(includeOffline) all.filter {it.kind=="offline" && it.id in chapters && library.file("cache/${it.id}.txt").isFile} else emptyList()
        val protected=offline.map {"cache/${it.id}.txt"}.toSet()
        val records=all.filter {it.kind !in setOf("download","offline","restore","progress","book")}+
            books.map {Record("book",it.id,it.encode())}+offline
        val state=JSONObject().put("version",2).put("created",System.currentTimeMillis()).put("includeBooks",includeBooks).put("includeOffline",includeOffline)
            .put("profile",library.profile.first().json()).put("preferences",library.preferences.first())
            .put("records",JSONArray(records.map {JSONObject().put("kind",it.kind).put("id",it.id).put("payload",it.payload)}))
        val stateBytes=state.toString().toByteArray()
        require(stateBytes.size<=MAX_STATE) {"书库元数据超过备份限制"}
        val files=library.root.walkTopDown().filter {it.isFile}.map {it.relativeTo(library.root).invariantSeparatorsPath to it}
            .filter {(path,_) -> if(path.startsWith("cache/")) path in protected else includeBooks || !path.startsWith("books/")}.toList()
        require(files.size+2<=MAX_ENTRIES) {"备份条目过多，请先清理未使用文件"}
        require(files.all {it.second.length()<=MAX_FILE}) {"单个文件超过 100MB，无法备份"}
        require(stateBytes.size.toLong()+files.sumOf {it.second.length()}<=MAX_TOTAL) {"备份解压内容超过 512MB，请减少下载或清理文件"}
        val target=File(library.context.cacheDir,"wanjuan-${uid()}.zip")
        try {
            val hashes=JSONObject()
            ZipOutputStream(target.outputStream().buffered()).use {zip ->
                zip.putNextEntry(ZipEntry("state.json"));zip.write(stateBytes);zip.closeEntry();hashes.put("state.json",sha256(stateBytes))
                files.forEachIndexed {index,(path,file) ->
                    progress("备份 ${index+1} / ${files.size}")
                    zip.putNextEntry(ZipEntry("library/$path"))
                    hashes.put("library/$path",file.inputStream().use {it.copyChecked(zip,MAX_FILE).second})
                    zip.closeEntry()
                }
                val manifest=hashes.toString().toByteArray()
                require(manifest.size<=MAX_MANIFEST) {"备份清单过大"}
                zip.putNextEntry(ZipEntry("manifest.json"));zip.write(manifest);zip.closeEntry()
            }
            require(target.length()<=MAX_TOTAL) {"备份压缩文件超过 512MB"}
            return target
        } catch(e: Throwable) {target.delete();throw e}
    }

    suspend fun inspect(file: File): String = withContext(Dispatchers.IO) {
        require(file.length()<=MAX_TOTAL) {"备份超过 512MB"}
        ZipFile(file).use {zip ->
            require(zip.size()<=MAX_ENTRIES) {"备份条目过多"}
            val state=readState(zip)
            val records=state.getJSONArray("records").objects()
            val offline=records.count {it.getString("kind")=="offline"}
            "书籍 ${records.count {it.getString("kind")=="book"}} 本 · 书源 ${records.count {it.getString("kind")=="source"}} 个\n"+
                "含进度、笔记、阅读设置和预设。\n"+
                (if(state.optBoolean("includeBooks",true)) "包含本地书籍文件。\n" else "不含本地书籍正文，恢复后需要重新导入。\n")+
                (if(offline>0) "包含 $offline 章已下载正文，可断网阅读。\n" else "不含在线离线正文，在线书籍恢复后需要联网。\n")+
                "恢复会替换当前书架与设置，并保留恢复前快照；下载任务不会恢复。"
        }
    }
    private fun readState(zip: ZipFile): JSONObject {
        val state=JSONObject(zip.getInputStream(zip.getEntry("state.json") ?: error("不是万卷备份")).use {it.readBytesLimited(MAX_STATE).toString(Charsets.UTF_8)})
        require(state.getInt("version") in 1..2) {"不支持此备份版本"}
        return state
    }

    suspend fun restore(file: File,progress: (String)->Unit = {}) = withContext(Dispatchers.IO) {
        inspect(file)
        val stage=File(library.context.filesDir,"restore-${uid()}").apply {mkdirs()}
        try {
            ZipFile(file).use {zip ->
                val manifest=JSONObject(zip.getInputStream(zip.getEntry("manifest.json") ?: error("备份缺少校验清单")).use {it.readBytesLimited(MAX_MANIFEST).toString(Charsets.UTF_8)})
                val seen=mutableSetOf<String>();var total=0L
                val entries=zip.entries().asSequence().toList()
                require(entries.map {it.name}.distinct().size==entries.size) {"备份有重复条目"}
                entries.filter {!it.isDirectory && it.name!="manifest.json"}.forEachIndexed {index,entry ->
                    seen.add(entry.name)
                    require(entry.name=="state.json" || entry.name.startsWith("library/")) {"备份有未知文件"}
                    val target=File(stage,entry.name).canonicalFile
                    require(target.path.startsWith(stage.canonicalPath+File.separator)) {"非法备份路径"}
                    target.parentFile?.mkdirs()
                    progress("校验并恢复 ${index+1} / ${entries.size-1}")
                    val (size,hash)=zip.getInputStream(entry).use {input -> target.outputStream().use {out -> input.copyChecked(out,minOf(MAX_FILE,MAX_TOTAL-total))}}
                    total+=size
                    require(hash==manifest.getString(entry.name)) {"备份校验失败：${entry.name}"}
                }
                require(seen==manifest.keys().asSequence().toSet()) {"备份清单不完整"}
            }
            val state=JSONObject(File(stage,"state.json").inputStream().use {it.readBytesLimited(MAX_STATE).toString(Charsets.UTF_8)})
            fun portableProfile(raw: String): String {
                if(raw.isBlank()) return raw
                val j=JSONObject(raw)
                listOf("font","background").forEach {key -> if(j.has(key)) {
                    val relative=j.optString(key).substringAfter("/library/","")
                    j.put(key,if(relative.isBlank()) "" else library.file(relative).path)
                }}
                return j.toString()
            }
            val records=state.getJSONArray("records").objects().map {
                val kind=it.getString("kind");val raw=it.getString("payload")
                val payload=when(kind) {"book" -> Book.decode(raw).let {b -> b.copy(profile=portableProfile(b.profile)).encode()};"preset" -> portableProfile(raw);else -> raw}
                Record(kind,it.getString("id"),payload)
            }
            require(records.map {it.kind+":"+it.id}.distinct().size==records.size) {"重复数据记录"}
            val chapterIds=records.filter {it.kind=="book"}.flatMap {Book.decode(it.payload).chapters}.map {it.id}.toSet()
            records.forEach {r -> when(r.kind) {
                "book" -> {val b=Book.decode(r.payload);require(b.id==r.id);b.chapters.filter {it.file.isNotBlank()}.forEach {library.file(it.file)}}
                "source" -> require(Source.decode(r.payload).id==r.id)
                "mark" -> require(Mark.decode(r.payload).id==r.id)
                "progress" -> ReadingProgress.decode(r.payload)
                "history","preset" -> Unit
                "offline" -> {require(state.getInt("version")>=2 && r.id in chapterIds);require(File(stage,"library/cache/${r.id}.txt").isFile) {"离线正文缺失"}}
                "builtin" -> require(r.id=="installed-v1" && r.payload=="1") {"内置书源状态无效"}
                else -> error("未知记录类型：${r.kind}")
            }}
            val newProfile=Profile.from(portableProfile(state.getJSONObject("profile").toString()))
            val newPreferences=state.optJSONObject("preferences")?.toString() ?: "{}"
            // Workers are cancelled before acquiring the library lock; all writes use the same lock.
            androidx.work.WorkManager.getInstance(library.context).cancelAllWorkByTag("downloads").result.get()
            library.fileOperations.withLock {library.mutation.withLock {
                currentCoroutineContext().ensureActive()
                val snapshot=createLocked(true,true,progress)
                val previous=File(library.context.filesDir,"before-restore.zip")
                try {java.nio.file.Files.move(snapshot.toPath(),previous.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING)} finally {snapshot.delete()}
                val old=File(library.context.filesDir,"restore-previous-${uid()}")
                val oldProfile=library.profile.first();val oldPreferences=library.preferences.first().toString()
                val staged=File(stage,"library").apply {mkdirs()}
                val token=uid()
                val journal=AtomicFile(File(library.context.filesDir,"restore-journal.json"))
                // The short commit section must complete or leave its journal for recovery.
                withContext(NonCancellable) {
                    progress("正在提交恢复，完成后返回")
                    val stream=journal.startWrite()
                    try {stream.write(JSONObject().put("previous",old.name).put("token",token).put("profile",oldProfile.json().toString()).put("preferences",oldPreferences)
                        .put("newProfile",newProfile.json().toString()).put("newPreferences",newPreferences).toString().toByteArray());journal.finishWrite(stream)}
                    catch(e: Exception) {journal.failWrite(stream);throw e}
                    var committed=false
                    try {
                        checkpoint("journal")
                        check(library.root.renameTo(old)) {"无法暂存原书库"};checkpoint("old-moved")
                        check(staged.renameTo(library.root)) {"无法安装恢复文件"};checkpoint("files-installed")
                        library.db.withTransaction {
                            library.dao.clear();library.dao.putAll(records)
                            library.saveProfileLocked(newProfile);library.replacePreferencesLocked(newPreferences);checkpoint("settings-written")
                            library.dao.put(Record("restore","marker",token))
                        }
                        committed=true;checkpoint("committed")
                        old.deleteRecursively();journal.delete()
                    } catch(e: Exception) {
                        if(!committed) {
                            if(old.exists()) {library.root.deleteRecursively();check(old.renameTo(library.root)) {"无法回退书库，请保留恢复日志"}}
                            library.saveProfileLocked(oldProfile);library.replacePreferencesLocked(oldPreferences);journal.delete()
                        }
                        throw e
                    }
                }
            }}
        } finally {stage.deleteRecursively()}
    }
    companion object {
        const val MAX_FILE=100L*1024*1024
        const val MAX_TOTAL=512L*1024*1024
        const val MAX_ENTRIES=20000
        const val MAX_STATE=16*1024*1024
        const val MAX_MANIFEST=4*1024*1024
    }
}
