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

@Entity(tableName = "records", primaryKeys = ["kind", "id"])
data class Record(val kind: String, val id: String, val payload: String)
@Dao interface RecordDao {
    @Query("SELECT * FROM records WHERE kind = :kind") fun observe(kind: String): Flow<List<Record>>
    @Query("SELECT * FROM records WHERE kind = :kind") suspend fun list(kind: String): List<Record>
    @Query("SELECT * FROM records WHERE kind = :kind AND id = :id") suspend fun get(kind: String, id: String): Record?
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
    init { recoverRestore(); root.mkdirs() }
    val mutation = Mutex()
    val books = dao.observe("book").map { r -> r.map { Book.decode(it.payload) }.sortedWith(compareByDescending<Book> { it.pinned }.thenByDescending { it.updated }) }
    val sources = dao.observe("source").map { r -> r.map { Source.decode(it.payload) }.sortedBy { it.name } }
    val marks = dao.observe("mark").map { r -> r.map { Mark.decode(it.payload) } }
    val profile = context.settings.data.map { Profile.from(it[stringPreferencesKey("profile")] ?: "{}") }
    val preferences = context.settings.data.map { org.json.JSONObject(it[stringPreferencesKey("preferences")] ?: "{}") }
    suspend fun preference(name: String,value: String) { context.settings.edit { values -> val p=org.json.JSONObject(values[stringPreferencesKey("preferences")] ?: "{}");p.put(name,value);values[stringPreferencesKey("preferences")]=p.toString() } }
    suspend fun replacePreferences(value: String) { context.settings.edit {it[stringPreferencesKey("preferences")]=value} }
    suspend fun saveProfile(p: Profile) { context.settings.edit { it[stringPreferencesKey("profile")] = p.json().toString() } }
    suspend fun save(book: Book) { mutation.withLock { dao.put(Record("book", book.id, book.encode())) } }
    suspend fun save(source: Source) { mutation.withLock { dao.put(Record("source", source.id, source.encode())) } }
    suspend fun save(mark: Mark) { mutation.withLock { dao.put(Record("mark", mark.id, mark.encode())) } }
    suspend fun book(id: String) = dao.get("book", id)?.let { Book.decode(it.payload) }
    suspend fun source(id: String) = dao.get("source", id)?.let { Source.decode(it.payload) }
    suspend fun updateBook(id: String, update: (Book)->Book) { mutation.withLock { book(id)?.let { dao.put(Record("book",id,update(it).encode())) } } }
    suspend fun removeBook(id: String) { mutation.withLock { db.withTransaction {
        dao.delete("book",id)
        dao.list("mark").filter { Mark.decode(it.payload).bookId == id }.forEach { dao.delete("mark",it.id) }
    } } }
    suspend fun progress(id: String, chapter: Int, offset: Int, anchor: String) = updateBook(id) {
        it.copy(chapter = chapter, offset = offset, anchor = anchor, updated = System.currentTimeMillis())
    }
    fun file(relative: String): File {
        require(relative.isNotBlank()) { "文件路径为空" }
        val f = File(root, relative).canonicalFile
        require(f.path.startsWith(root.canonicalPath + File.separator)) { "非法文件路径" }
        return f
    }
    suspend fun putText(relative: String, text: String) = withContext(Dispatchers.IO) { mutation.withLock {
        val target = file(relative); target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + "." + uid() + ".tmp")
        temp.writeText(text)
        try { java.nio.file.Files.move(temp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
        finally { temp.delete() }
    } }
    private fun recoverRestore() {
        val journal=android.util.AtomicFile(File(context.filesDir,"restore-journal.json"))
        if(!journal.baseFile.exists()) return
        val j=org.json.JSONObject(journal.openRead().use { it.readBytes().toString(Charsets.UTF_8) })
        val old=File(context.filesDir,j.getString("previous")).canonicalFile
        require(old.parentFile==context.filesDir.canonicalFile && old.name.startsWith("restore-previous-"))
        kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            val committed=dao.get("restore","marker")?.payload==j.getString("token")
            if(!committed && old.exists()) {
                root.deleteRecursively();check(old.renameTo(root));saveProfile(Profile.from(j.getString("profile")));replacePreferences(j.optString("preferences","{}"))
            } else if(committed) old.deleteRecursively()
        }
        journal.delete()
    }
    suspend fun text(relative: String) = withContext(Dispatchers.IO) { file(relative).takeIf { it.exists() }?.readText() }
    suspend fun markDownloaded(chapterId: String) = dao.put(Record("offline", chapterId, "{}"))
    suspend fun trimCache(maxBytes: Long = 300L * 1024 * 1024) = withContext(Dispatchers.IO) {
        val protected = dao.list("offline").map { it.id }.toSet()
        val files = File(root,"cache").listFiles()?.filter { it.extension == "txt" }?.sortedBy { it.lastModified() } ?: return@withContext
        var total = files.sumOf { it.length() }
        for (f in files) if (total > maxBytes && f.nameWithoutExtension !in protected) { val n = f.length(); if (f.delete()) total -= n }
    }
}
