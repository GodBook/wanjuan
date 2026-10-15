package io.github.godbook.wanjuan.data

import android.content.Context
import androidx.room.*
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import org.json.JSONObject

@Entity(tableName = "records", primaryKeys = ["kind", "id"])
data class Record(val kind: String, val id: String, val payload: String)
@Dao interface RecordDao {
    @Query("SELECT * FROM records WHERE kind = :kind") fun observe(kind: String): Flow<List<Record>>
    @Query("SELECT * FROM records WHERE kind = :kind") suspend fun list(kind: String): List<Record>
    @Query("SELECT * FROM records WHERE kind = :kind AND id = :id") suspend fun get(kind: String, id: String): Record?
    @Query("SELECT EXISTS(SELECT 1 FROM records WHERE kind = :kind AND id = :id)") suspend fun contains(kind: String,id: String): Boolean
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(record: Record)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putAll(records: List<Record>)
    @Query("DELETE FROM records WHERE kind = :kind AND id = :id") suspend fun delete(kind: String, id: String)
    @Query("SELECT * FROM records") suspend fun all(): List<Record>
    @Query("DELETE FROM records") suspend fun clear()
}
@Database(entities = [Record::class], version = 1, exportSchema = true)
abstract class LibraryDb: RoomDatabase() { abstract fun records(): RecordDao }
private val Context.settings by preferencesDataStore("reader")

class Library(val context: Context) {
    val db = Room.databaseBuilder(context, LibraryDb::class.java, "wanjuan.db").build()
    val dao = db.records()
    val root = File(context.filesDir, "library")
    val mutation = Mutex()
    val fileOperations = Mutex()
    init { kotlinx.coroutines.runBlocking(Dispatchers.IO) { recoverRestore() }; root.mkdirs() }
    private val bookMetadata = dao.observe("book").distinctUntilChanged().map { r -> r.map {Book.decode(it.payload)} }.flowOn(Dispatchers.Default)
    private val positions = dao.observe("progress").distinctUntilChanged().map { r -> r.associate {it.id to ReadingProgress.decode(it.payload)} }.flowOn(Dispatchers.Default)
    val books = combine(bookMetadata,positions) { metadata,progress -> metadata.map {progress[it.id]?.apply(it) ?: it}
        .sortedWith(compareByDescending<Book> {it.pinned}.thenByDescending {it.updated}) }.flowOn(Dispatchers.Default)
    val sources = dao.observe("source").distinctUntilChanged().map { r -> r.map { Source.decode(it.payload) }.sortedBy { it.name } }.flowOn(Dispatchers.Default)
    val marks = dao.observe("mark").distinctUntilChanged().map { r -> r.map { Mark.decode(it.payload) } }.flowOn(Dispatchers.Default)
    val history = dao.observe("history").distinctUntilChanged().map {sortHistory(it)}.flowOn(Dispatchers.Default)
    val profile = context.settings.data.map { Profile.from(it[stringPreferencesKey("profile")] ?: "{}") }
    val preferences = context.settings.data.map { org.json.JSONObject(it[stringPreferencesKey("preferences")] ?: "{}") }
    suspend fun preference(name: String,value: String) = mutation.withLock {
        context.settings.edit { values -> val p=JSONObject(values[stringPreferencesKey("preferences")] ?: "{}");p.put(name,value);values[stringPreferencesKey("preferences")]=p.toString() }; Unit
    }
    suspend fun replacePreferences(value: String) = mutation.withLock {replacePreferencesLocked(value)}
    suspend fun saveProfile(p: Profile) = mutation.withLock {saveProfileLocked(p)}
    internal suspend fun replacePreferencesLocked(value: String) {context.settings.edit {it[stringPreferencesKey("preferences")]=value}}
    internal suspend fun saveProfileLocked(p: Profile) {context.settings.edit {it[stringPreferencesKey("profile")]=p.json().toString()}}
    suspend fun put(record: Record) = mutation.withLock {dao.put(record)}
    suspend fun delete(kind: String,id: String) = mutation.withLock {dao.delete(kind,id)}
    internal suspend fun saveBookLocked(book: Book) {
        dao.put(Record("book",book.id,book.encode()))
        dao.put(Record("progress",book.id,ReadingProgress.from(book).encode()))
    }
    suspend fun save(book: Book) = mutation.withLock {db.withTransaction {saveBookLocked(book)}}
    suspend fun save(source: Source) { mutation.withLock { dao.put(Record("source", source.id, source.encode())) } }
    suspend fun save(mark: Mark) { mutation.withLock { dao.put(Record("mark", mark.id, mark.encode())) } }
    suspend fun book(id: String): Book? {
        val b=dao.get("book",id)?.let {Book.decode(it.payload)} ?: return null
        return dao.get("progress",id)?.let {ReadingProgress.decode(it.payload).apply(b)} ?: b
    }
    suspend fun source(id: String) = dao.get("source", id)?.let { Source.decode(it.payload) }
    suspend fun updateBook(id: String, update: (Book)->Book) = withContext(Dispatchers.IO) { mutation.withLock {db.withTransaction {book(id)?.let {saveBookLocked(update(it))}}} }
    suspend fun removeBook(id: String) { mutation.withLock { db.withTransaction {
        dao.delete("book",id)
        dao.delete("progress",id);dao.delete("download",id)
        dao.list("mark").filter { Mark.decode(it.payload).bookId == id }.forEach { dao.delete("mark",it.id) }
    } } }
    suspend fun progress(id: String, chapter: Int, offset: Int, anchor: String,edition: Book? = null) = withContext(Dispatchers.IO) {mutation.withLock {
        if(dao.contains("book",id)) dao.put(Record("progress",id,ReadingProgress(chapter,offset,anchor,System.currentTimeMillis(),edition?.sourceId,edition?.url,edition?.chapters?.getOrNull(chapter)?.id).encode()))
    }}
    suspend fun rememberSearch(query: String) = mutation.withLock {db.withTransaction {
        val latest=dao.list("history").maxOfOrNull {it.payload.toLongOrNull() ?: 0L} ?: 0L
        dao.put(Record("history",query,maxOf(System.currentTimeMillis(),latest+1).toString()))
        sortHistory(dao.list("history")).drop(12).forEach {dao.delete("history",it.id)}
    }}
    suspend fun clearHistory() = mutation.withLock {dao.list("history").forEach {dao.delete("history",it.id)}}
    suspend fun backupResult(error: String? = null) = mutation.withLock {
        val p=preferences.first().put("lastBackupAttempt",System.currentTimeMillis())
        if(error==null) {p.put("lastAutomaticBackup",System.currentTimeMillis());p.remove("backupError")} else p.put("backupError",error)
        replacePreferencesLocked(p.toString())
    }
    suspend fun replaceEdition(id: String,candidate: Book,targetChapter: Int? = null): Book = mutation.withLock {db.withTransaction {
        val old=book(id) ?: error("书籍已移出书架")
        val changed=old.sourceId!=candidate.sourceId || old.url!=candidate.url
        val previous=old.chapters.getOrNull(old.chapter)
        val matched=candidate.chapters.indexOfFirst {it.id==previous?.id}.takeIf {it>=0}
            ?: candidate.chapters.withIndex().filter {it.value.title==previous?.title}.singleOrNull()?.index
        val index=(targetChapter ?: matched ?: old.chapter).coerceIn(0,candidate.chapters.lastIndex.coerceAtLeast(0))
        val result=candidate.copy(id=old.id,chapter=index,offset=old.offset,anchor=old.anchor,group=old.group,pinned=old.pinned,
            profile=old.profile,finished=old.finished,updated=old.updated,localHash=old.localHash)
        dao.list("mark").map {Mark.decode(it.payload)}.filter {it.bookId==id}.forEach {mark ->
            val contextual=if(mark.chapterId.isBlank()) mark.withContext(old) else mark
            val newIndex=if(changed) null else contextual.indexIn(result)
            val updated=if(newIndex==null) contextual.copy(needsReview=true) else contextual.copy(chapter=newIndex)
            dao.put(Record("mark",mark.id,updated.encode()))
        }
        dao.delete("download",id);saveBookLocked(result);result
    }}
    fun file(relative: String): File {
        require(relative.isNotBlank()) { "文件路径为空" }
        val f = File(root, relative).canonicalFile
        require(f.path.startsWith(root.canonicalPath + File.separator)) { "非法文件路径" }
        return f
    }
    suspend fun putText(relative: String, text: String) = withContext(Dispatchers.IO) { mutation.withLock {
        val target = file(relative); target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + "." + uid() + ".tmp")
        try {temp.writeText(text);java.nio.file.Files.move(temp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
        finally { temp.delete() }
    } }
    internal suspend fun recoverRestore() {
        val journal=android.util.AtomicFile(File(context.filesDir,"restore-journal.json"))
        if(!journal.baseFile.exists()) return
        val j=org.json.JSONObject(journal.openRead().use { it.readBytes().toString(Charsets.UTF_8) })
        val old=File(context.filesDir,j.getString("previous")).canonicalFile
        require(old.parentFile==context.filesDir.canonicalFile && old.name.startsWith("restore-previous-"))
        val committed=dao.get("restore","marker")?.payload==j.getString("token")
        if(!committed) {
            if(old.exists()) {root.deleteRecursively();check(old.renameTo(root))}
            saveProfileLocked(Profile.from(j.getString("profile")));replacePreferencesLocked(j.optString("preferences","{}"))
        } else {
            require(root.isDirectory) {"恢复文件缺失，请保留恢复前快照"}
            if(j.has("newProfile")) saveProfileLocked(Profile.from(j.getString("newProfile")))
            if(j.has("newPreferences")) replacePreferencesLocked(j.getString("newPreferences"))
            old.deleteRecursively()
        }
        journal.delete()
    }
    suspend fun text(relative: String) = withContext(Dispatchers.IO) {mutation.withLock {file(relative).takeIf { it.exists() }?.readText()} }
    suspend fun markDownloaded(chapterId: String) = put(Record("offline", chapterId, "{}"))
    suspend fun putChapter(book: Book,chapter: Chapter,text: String,generation: String? = null) = withContext(Dispatchers.IO) {mutation.withLock {
        val current=this@Library.book(book.id) ?: error("书籍已移出书架")
        require(current.sourceId==book.sourceId && current.url==book.url && current.chapters.any {it.id==chapter.id}) {"来源或目录已变化，请重试"}
        if(generation!=null) {
            val spec=dao.get("download",book.id)?.let {DownloadSpec.decode(it.payload)} ?: error("下载已取消")
            require(spec.generation==generation && !spec.paused) {"下载已暂停或替换"}
        }
        val target=file("cache/${chapter.id}.txt");target.parentFile?.mkdirs()
        val temp=File(target.parentFile,target.name+"."+uid()+".tmp")
        try {temp.writeText(text);java.nio.file.Files.move(temp.toPath(),target.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING)}
        finally {temp.delete()}
        if(generation!=null) dao.put(Record("offline",chapter.id,"{}"))
    }}
    suspend fun trimCache(maxBytes: Long = 300L * 1024 * 1024) = withContext(Dispatchers.IO) {mutation.withLock {
        val protected = dao.list("offline").map { it.id }.toSet()
        val files = File(root,"cache").listFiles()?.filter { it.extension == "txt" }?.sortedBy { it.lastModified() } ?: return@withLock
        var total = files.sumOf { it.length() }
        for (f in files) if (total > maxBytes && f.nameWithoutExtension !in protected) { val n = f.length(); if (f.delete()) total -= n }
    } }
    companion object {
        fun sortHistory(records: List<Record>) = records.sortedWith(compareByDescending<Record> {it.payload.toLongOrNull() ?: 0L}.thenBy {it.id})
    }
}
