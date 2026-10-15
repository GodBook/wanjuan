package io.github.godbook.wanjuan

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.godbook.wanjuan.data.*
import io.github.godbook.wanjuan.source.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface ReaderLoad {
    data object Loading: ReaderLoad
    data object Ready: ReaderLoad
    data object Empty: ReaderLoad
    data class Failed(val reason: String): ReaderLoad
}

class AppModel(application: Application): AndroidViewModel(application) {
    val app=application as WanJuanApp
    val library=app.library
    val books=library.books.stateIn(viewModelScope,SharingStarted.Eagerly,emptyList())
    val sources=library.sources.stateIn(viewModelScope,SharingStarted.Eagerly,emptyList())
    val marks=library.marks.stateIn(viewModelScope,SharingStarted.Eagerly,emptyList())
    val profile=library.profile.stateIn(viewModelScope,SharingStarted.Eagerly,Profile())
    val reader=MutableStateFlow<Book?>(null)
    val body=MutableStateFlow("")
    val readerLoad=MutableStateFlow<ReaderLoad>(ReaderLoad.Loading)
    val readerGeneration=MutableStateFlow(0)
    val busy=MutableStateFlow(false)
    private var runningOperations=0
    val message=MutableStateFlow<String?>(null)
    val results=MutableStateFlow<List<Book>>(emptyList())
    val searchDone=MutableStateFlow(0)
    val searchErrors=MutableStateFlow<List<String>>(emptyList())
    val searching=MutableStateFlow(false)
    var searchJob: Job?=null
    private var readJob: Job?=null
    private var saveJob: Job?=null
    private var searchGeneration=0
    private val contentLock=Mutex()
    init { viewModelScope.launch(Dispatchers.IO) {
        try { BuiltinSources.install(library) } catch(e: Exception) { message.value="内置书源初始化失败，可在书源管理中重试" }
    } }
    fun run(label: String = "", block: suspend ()->Unit) = viewModelScope.launch {
        runningOperations+=1;busy.value=true
        try { withContext(Dispatchers.IO) { block() }; if(label.isNotBlank()) message.value=label }
        catch(e: CancellationException) { throw e }
        catch(e: Exception) { message.value=e.message ?: "操作失败" }
        finally {runningOperations-=1;busy.value=runningOperations>0}
    }
    fun search(query: String, group: String="全部") {
        val token=++searchGeneration
        searchJob?.cancel(); results.value=emptyList(); searchErrors.value=emptyList(); searchDone.value=0
        searching.value=query.isNotBlank()
        if(query.isBlank()) return
        searchJob=viewModelScope.launch {
            searching.value=true
            try {
                library.rememberSearch(query.trim())
                app.engine.search(query,sources.value.filter { group=="全部" || it.group==group }).collect { event ->
                    if(token!=searchGeneration) return@collect
                    results.value=results.value+event.books
                    searchDone.value+=1
                    if(event.error!=null) searchErrors.value=searchErrors.value+"${event.source.name}：${event.error}"
                }
            } catch(e: CancellationException) {throw e}
            catch(e: Exception) {if(token==searchGeneration) message.value=e.message ?: "搜索失败"}
            finally {if(token==searchGeneration) searching.value=false}
        }
    }
    fun addOnline(book: Book, read: Boolean=true) = run {
        val existing=books.value.find { it.sourceId==book.sourceId && it.url==book.url }
        val source=library.source(book.sourceId) ?: error("书源已删除")
        val loaded=existing ?: app.engine.catalog(book,source)
        library.save(loaded)
        if(read) withContext(Dispatchers.Main) { open(loaded) }
    }
    fun open(book: Book, chapter: Int=book.chapter, offset: Int=book.offset, quote: String=book.anchor) {
        val token=readerGeneration.value+1;readerGeneration.value=token
        readJob?.cancel();body.value="";readerLoad.value=ReaderLoad.Loading
        val index=chapter.coerceIn(0,book.chapters.lastIndex.coerceAtLeast(0))
        reader.value=book.copy(chapter=index,offset=offset,anchor=quote)
        readJob=viewModelScope.launch {
            try {
                val text=content(book,index)
                if(token!=readerGeneration.value) return@launch
                body.value=text;readerLoad.value=if(text.isBlank()) ReaderLoad.Empty else ReaderLoad.Ready
                for(i in index+1..(index+3).coerceAtMost(book.chapters.lastIndex)) try { content(book,i) } catch(e: CancellationException) { throw e } catch(_: Exception) { /* Optional prefetch must not interrupt reading. */ }
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) {if(token==readerGeneration.value) readerLoad.value=ReaderLoad.Failed(e.message ?: "读取失败")}
        }
    }
    suspend fun content(book: Book,index: Int): String = withContext(Dispatchers.IO) {
        val c=book.chapters.getOrNull(index) ?: error("没有可阅读的章节")
        if(c.file.isNotBlank()) return@withContext library.text(c.file) ?: error("本地书籍文件缺失，请重新导入")
        val path="cache/${c.id}.txt"
        library.text(path)?.let { return@withContext it }
        contentLock.withLock {
            library.text(path)?.let { return@withLock it }
            val source=library.source(book.sourceId) ?: error("此书的书源已删除，请换源")
            app.engine.content(c,source).also {library.putChapter(book,c,it);library.trimCache()}
        }
    }
    fun turnChapter(forward: Boolean) {
        val b=reader.value ?: return
        val index=b.chapter+if(forward) 1 else -1
        if(index in b.chapters.indices) { flush(); open(b,index,if(forward) 0 else Int.MAX_VALUE,"") }
        else message.value=if(forward) "已到最后一章" else "已到第一章"
    }
    fun position(offset: Int,anchor: String) {
        val b=reader.value ?: return
        if(b.offset==offset && b.anchor==anchor) return
        reader.value=b.copy(offset=offset,anchor=anchor)
        saveJob?.cancel()
        saveJob=viewModelScope.launch { delay(250); library.progress(b.id,b.chapter,offset,anchor,b) }
    }
    fun flush() { val b=reader.value ?: return; saveJob?.cancel(); viewModelScope.launch { library.progress(b.id,b.chapter,b.offset,b.anchor,b) } }
    fun closeReader() {flush();readerGeneration.value+=1;readJob?.cancel();reader.value=null;body.value=""}
    fun saveProfile(p: Profile, perBook: Boolean) = run {
        val b=reader.value
        if(perBook && b!=null) {
            val values=p.json(); val base=profile.value.json(); val overrides=org.json.JSONObject()
            values.keys().forEach { key -> if(values.get(key).toString()!=base.get(key).toString()) overrides.put(key,values.get(key)) }
            val encoded=overrides.toString()
            library.updateBook(b.id) { it.copy(profile=encoded) }; reader.value=reader.value?.copy(profile=encoded)
        }
        else library.saveProfile(p)
    }
    fun bookmark(note: String="") = run("已保存书签") {
        val b=reader.value ?: return@run
        library.save(Mark(bookId=b.id,chapter=b.chapter,offset=b.offset,quote=b.anchor,note=note).withContext(b))
    }
    fun switchSource(current: Book,candidate: Book, chapter: Int) = run {
        require(chapter in candidate.chapters.indices)
        saveJob?.cancelAndJoin()
        library.progress(current.id,current.chapter,current.offset,current.anchor,current)
        ChapterWorker.cancel(app,current.id)
        val switched=library.replaceEdition(current.id,candidate,chapter)
        withContext(Dispatchers.Main) { open(switched) }
    }
    fun removeBook(book: Book) = run("已移出书架，本地文件保留") {ChapterWorker.cancel(app,book.id);library.removeBook(book.id)}
    fun refreshCatalog(book: Book) = run {
        val source=library.source(book.sourceId) ?: error("书源已删除")
        val updated=app.engine.catalog(book,source)
        ChapterWorker.cancel(app,book.id)
        val result=library.replaceEdition(book.id,updated)
        val added=result.chapters.count {c -> book.chapters.none {it.id==c.id}}
        message.value=if(added>0) "目录已更新，新增 $added 章" else "目录已检查，当前没有新增章节"
    }
    fun relocateMark(mark: Mark,chapter: Int) = run {
        val b=library.book(mark.bookId) ?: error("书籍已移出书架")
        require(chapter in b.chapters.indices)
        val updated=mark.copy(offset=0).withContext(b,chapter)
        library.save(updated)
        withContext(Dispatchers.Main) {open(b,chapter,0,mark.quote)}
    }
}
