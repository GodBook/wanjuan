@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package io.github.godbook.wanjuan.ui

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.godbook.wanjuan.*
import io.github.godbook.wanjuan.data.*
import io.github.godbook.wanjuan.source.*
import io.github.godbook.wanjuan.update.*
import io.github.godbook.wanjuan.update.Updater
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.text.font.FontFamily
import org.json.JSONArray
import java.io.File

val Ink=Color(0xff243c32)
val Muted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant

@Composable fun WanJuan(model: AppModel, activity: MainActivity) {
    val preferences by model.library.preferences.collectAsState(org.json.JSONObject())
    val darkMode=preferences.optString("theme","0").toIntOrNull() ?: 0
    val dark=darkMode==2 || (darkMode==0 && isSystemInDarkTheme())
    MaterialTheme(colorScheme=if(dark) darkColorScheme(primary=Color(0xffb6cbbb),background=Color(0xff171c19),surface=Color(0xff171c19),onSurface=Color(0xffe3e8df),onSurfaceVariant=Color(0xffaeb9af),secondaryContainer=Color(0xff293b31),onSecondaryContainer=Color(0xffd8e9db)) else lightColorScheme(primary=Ink,background=Color(0xfffaf9f5),surface=Color(0xfffaf9f5),onSurface=Color(0xff252f29),onSurfaceVariant=Color(0xff626e65),secondaryContainer=Color(0xffe7ece2),onSecondaryContainer=Ink), typography=Typography()) {
        val reading by remember(model) { model.reader.map { it!=null }.distinctUntilChanged() }.collectAsState(false)
        val busy by model.busy.collectAsState()
        val message by model.message.collectAsState()
        val snack=remember { SnackbarHostState() }
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var page by rememberSaveable { mutableStateOf("") }
        var prompt by remember { mutableStateOf<Prompt?>(null) }
        var imports by remember { mutableStateOf<List<ImportCandidate>?>(null) }
        var restore by remember { mutableStateOf<Pair<File,String>?>(null) }
        var release by remember { mutableStateOf<Release?>(null) }
        var downloaded by remember { mutableStateOf(false) }
        var restoreBook by remember { mutableStateOf<Book?>(null) }
        val scope=rememberCoroutineScope()
        val app=model.app
        val library=model.library
        var encoding by rememberSaveable { mutableStateOf("自动") }
        fun name(uri: Uri): String = activity.contentResolver.query(uri,null,null,null,null)?.use { c -> if(c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else "book.txt" } ?: "book.txt"
        val importBook=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if(uri!=null) model.run("书籍已加入书架") { BookImporter(library).import(uri,name(uri),encoding) } }
        val importSource=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if(uri!=null) model.run { val text=activity.contentResolver.openInputStream(uri)!!.use { it.readBytesLimited(8*1024*1024).toString(Charsets.UTF_8) }; val data=app.engine.inspect(text); withContext(Dispatchers.Main) { imports=data } } }
        val exportBackup=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> if(uri!=null) model.run("备份已导出") { val f=Backup(library).create(); try { activity.contentResolver.openOutputStream(uri)!!.use { out -> f.inputStream().use { it.copyTo(out) } } } finally { f.delete() } } }
        val exportSources=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if(uri!=null) model.run("书源已导出") { activity.contentResolver.openOutputStream(uri)!!.use { it.write(JSONArray(model.sources.value.map { s -> s.json }).toString(2).toByteArray()) } } }
        val importBackup=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if(uri!=null) model.run {
            val f=File(activity.cacheDir,"restore-input.zip"); activity.contentResolver.openInputStream(uri)!!.use { f.writeBytes(it.readBytesLimited(512*1024*1024)) }
            val preview=Backup(library).inspect(f); withContext(Dispatchers.Main) { restore=f to preview }
        } }
        LaunchedEffect(message) { message?.let { model.message.value=null; snack.showSnackbar(it) } }
        BackHandler(enabled=page.isNotEmpty() && !reading) { page="" }
        Scaffold(snackbarHost={ SnackbarHost(snack) },bottomBar={ if(!reading && page.isEmpty()) NavigationBar(containerColor=MaterialTheme.colorScheme.surface,tonalElevation=0.dp) {
            listOf("书架" to Icons.Outlined.MenuBook,"搜索" to Icons.Outlined.Search,"设置" to Icons.Outlined.Tune).forEachIndexed { i,(label,icon) -> NavigationBarItem(selected=tab==i,onClick={tab=i},icon={Icon(icon,label)},label={Text(label)}) }
        } }) { padding ->
            Box(Modifier.fillMaxSize().padding(if(!reading) padding else PaddingValues())) {
                when {
                    reading -> ReaderScreen(model,activity,onSources={ model.closeReader(); page="sources" })
                    page=="sources" -> SourcesScreen(model,onBack={page=""},onFile={importSource.launch(arrayOf("application/json","text/*","application/octet-stream"))},onPaste={ prompt=Prompt("导入书源","粘贴 JSON 或 HTTPS 地址","",true) { raw -> model.run {
                        val text=if(raw.trim().startsWith("https://")) Network().text(raw.trim()) else raw
                        val data=app.engine.inspect(text); withContext(Dispatchers.Main) { imports=data }
                    } } },onExport={exportSources.launch("万卷书源.json")})
                    page=="downloads" -> DownloadsScreen(model) { page="" }
                    page=="webdav" -> WebDavScreen(model,onBack={page=""},onRestore={ f,preview -> restore=f to preview })
                    tab==0 -> ShelfScreen(model,onBrowse={tab=1},onImport={importBook.launch(arrayOf("text/plain","application/epub+zip","application/octet-stream"))},onSources={page="sources"},onGroup={ book -> prompt=Prompt("书架分组","输入分组名称",book.group) { group -> model.run { library.updateBook(book.id) { it.copy(group=group.ifBlank { "未分组" }) } } } })
                    tab==1 -> SearchScreen(model,onSources={page="sources"})
                    else -> SettingsScreen(model,darkMode,{value -> model.run {library.preference("theme",value.toString())}},encoding,{encoding=it},
                        onSources={page="sources"},onDownloads={page="downloads"},onBackup={exportBackup.launch("万卷备份-${System.currentTimeMillis()}.zip")},onRestore={importBackup.launch(arrayOf("application/zip","application/octet-stream"))},onDav={page="webdav"},onUpdate={ model.run {
                            val r=Updater(activity).check()
                            if(r==null) model.message.value="已是最新版本 ${BuildConfig.VERSION_NAME}" else withContext(Dispatchers.Main) { downloaded=false; release=r }
                        } },onReaderSettings={page="readerSettings"})
                }
                if(page=="readerSettings" && !reading) Surface(Modifier.fillMaxSize()) { ProfileSettings(model,model.profile.collectAsState().value,false,{page=""},activity) }
                if(busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
        }
        prompt?.let { p -> TextPrompt(p) { prompt=null } }
        imports?.let { candidates ->
            var replace by remember { mutableStateOf(true) }
            AlertDialog(onDismissRequest={imports=null},title={Text("导入 ${candidates.size} 个书源")},text={ Column {
                Text("书源不会自动执行。导入后可启用、检测或允许 HTTP。",style=MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.heightIn(max=300.dp)) { items(candidates) { c -> Column(Modifier.padding(vertical=8.dp)) { Text(c.source.name,fontWeight=FontWeight.Medium); Text(if(c.warnings.isEmpty()) "常用规则 · 等待检测" else c.warnings.joinToString("；"),style=MaterialTheme.typography.bodySmall,color=Muted) } } }
                Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(replace,{replace=it}); Text("更新同地址的已有书源") }
            } },confirmButton={TextButton(onClick={ imports=null; model.run("书源已导入") { candidates.forEach { c -> val existing=library.source(c.source.id); if(existing==null || replace) library.save(c.source.copy(http=existing?.http ?: false)) } } }) { Text("确认导入") }},dismissButton={TextButton(onClick={imports=null}) { Text("取消") }})
        }
        restore?.let { (file,preview) -> AlertDialog(onDismissRequest={restore=null},title={Text("恢复备份")},text={Text(preview)},confirmButton={TextButton(onClick={restore=null; model.run("备份已恢复") { Backup(library).restore(file) }}) {Text("恢复")}},dismissButton={TextButton(onClick={restore=null}) {Text("取消")}}) }
        release?.let { r -> AlertDialog(onDismissRequest={release=null},title={Text("发现新版本 ${r.version}")},text={Text(r.notes.ifBlank { "更新将保留书架、阅读进度和设置。" } + if(downloaded) "\n\n已验证安装包。授权安装后返回，再点击安装。" else "")},confirmButton={TextButton(onClick={
            if(downloaded) runCatching { Updater(activity).install() }.onFailure { model.message.value=it.message }
            else model.run { Updater(activity).download(r); withContext(Dispatchers.Main) { downloaded=true } }
        }) {Text(if(downloaded) "安装更新" else "下载更新")}},dismissButton={TextButton(onClick={release=null}) {Text("稍后")}}) }
    }
}

data class Prompt(val title: String,val hint: String,val initial: String="",val multiline: Boolean=false,val submit: (String)->Unit)
@Composable fun TextPrompt(p: Prompt,onDismiss: ()->Unit) {
    var value by remember(p) { mutableStateOf(p.initial) }
    AlertDialog(onDismissRequest=onDismiss,title={Text(p.title)},text={OutlinedTextField(value,{value=it},placeholder={Text(p.hint)},minLines=if(p.multiline) 5 else 1,maxLines=if(p.multiline) 10 else 1,modifier=Modifier.fillMaxWidth())},confirmButton={TextButton(onClick={onDismiss();p.submit(value)}) {Text("确定")}},dismissButton={TextButton(onClick=onDismiss) {Text("取消")}})
}
@Composable fun PageHeader(title: String,subtitle: String="",onBack: (()->Unit)?=null,actions: @Composable RowScope.()->Unit = {}) {
    Column(Modifier.padding(horizontal=24.dp,vertical=12.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            if(onBack!=null) IconButton(onClick=onBack,modifier=Modifier.offset(x=(-12).dp)) {Icon(Icons.Outlined.ArrowBack,"返回")}
            Text(title,style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
            actions()
        }
        if(subtitle.isNotBlank()) Text(subtitle,color=Muted,style=MaterialTheme.typography.bodyMedium,modifier=Modifier.padding(top=4.dp))
    }
}
@Composable fun EmptyState(title: String,description: String,action: String,onAction: ()->Unit) {
    Column(Modifier.fillMaxSize().padding(36.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.AutoStories,null,Modifier.size(52.dp),tint=MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(22.dp)); Text(title,style=MaterialTheme.typography.titleLarge); Spacer(Modifier.height(10.dp))
        Text(description,color=Muted,style=MaterialTheme.typography.bodyMedium); Spacer(Modifier.height(28.dp)); Button(onClick=onAction) {Text(action)}
    }
}
@Composable fun ShelfScreen(model: AppModel,onBrowse: ()->Unit,onImport: ()->Unit,onSources: ()->Unit,onGroup: (Book)->Unit) {
    val books by model.books.collectAsState()
    var group by rememberSaveable { mutableStateOf("全部") }
    var list by rememberSaveable { mutableStateOf(false) }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<Book?>(null) }
    val shown=remember(books,group,sort) { books.filter { group=="全部" || it.group==group }.let { if(sort==1) it.sortedBy { b -> b.title } else it } }
    Column(Modifier.fillMaxSize()) {
        PageHeader("万卷",if(books.isEmpty()) "把时间，留给好故事。" else "${books.size} 本藏书 · 随时续读") {
            IconButton(onClick={list=!list}) {Icon(if(list) Icons.Outlined.GridView else Icons.Outlined.ViewList,"切换书架布局")}
            IconButton(onClick=onImport) {Icon(Icons.Outlined.Add,"导入本地书籍")}
        }
        if(books.isEmpty()) Column(Modifier.fillMaxSize().padding(32.dp),verticalArrangement=Arrangement.Center) {
            Text("一卷在手，\n闲处即书房。",fontFamily=FontFamily.Serif,fontSize=32.sp,lineHeight=48.sp,color=MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(20.dp))
            Text("内置经典书源，打开就能找书。\n也可以导入自己的 TXT 与 EPUB。",color=Muted,lineHeight=25.sp)
            Spacer(Modifier.height(32.dp))
            Button(onClick=onBrowse,contentPadding=PaddingValues(horizontal=24.dp,vertical=14.dp)) {Text("找一本书");Spacer(Modifier.width(16.dp));Icon(Icons.Outlined.ArrowForward,null,Modifier.size(18.dp))}
            TextButton(onClick=onImport,modifier=Modifier.padding(top=8.dp)) {Text("导入本地书籍")}
        }
        else {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=24.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                (listOf("全部")+books.map { it.group }.distinct()).forEach { g -> FilterChip(selected=group==g,onClick={group=g},label={Text(g)}) }
                FilterChip(selected=sort==1,onClick={sort=1-sort},label={Text(if(sort==0) "最近阅读" else "按书名")})
            }
            val recent=remember(books) { books.maxBy {it.updated} }
            Surface(Modifier.padding(24.dp,16.dp).fillMaxWidth().clickable { model.open(recent) },shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.secondaryContainer) {
                Row(Modifier.padding(20.dp),verticalAlignment=Alignment.CenterVertically) { Column(Modifier.weight(1f)) {Text("接着上次读",style=MaterialTheme.typography.labelMedium,color=Muted); Text(recent.title,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleMedium,modifier=Modifier.padding(top=6.dp)); Text(recent.chapters.getOrNull(recent.chapter)?.title ?: "开始新的故事",maxLines=1,style=MaterialTheme.typography.bodySmall,color=Muted)}; Icon(Icons.Outlined.ArrowForward,null) }
            }
            if(list) LazyColumn(contentPadding=PaddingValues(horizontal=24.dp,vertical=8.dp)) { items(shown,key={it.id}) { b -> ListItem(headlineContent={Text(b.title)},supportingContent={Text("${b.author.ifBlank { "本地书籍" }} · ${b.chapters.size} 章")},modifier=Modifier.combinedClickable(onClick={model.open(b)},onLongClick={selected=b}),trailingContent={IconButton(onClick={selected=b}) {Icon(Icons.Outlined.MoreHoriz,"书籍操作")}}); HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.3f)) } }
            else LazyVerticalGrid(columns=GridCells.Adaptive(96.dp),contentPadding=PaddingValues(24.dp,8.dp,24.dp,24.dp),horizontalArrangement=Arrangement.spacedBy(20.dp),verticalArrangement=Arrangement.spacedBy(24.dp)) { items(shown,key={it.id}) { b ->
                Column(Modifier.combinedClickable(onClick={model.open(b)},onLongClick={selected=b})) {
                    BookCover(b)
                    Text(b.title,Modifier.padding(top=10.dp),maxLines=2,overflow=TextOverflow.Ellipsis,fontSize=14.sp,fontWeight=FontWeight.Medium)
                    Text(if(b.finished) "已读完" else "${b.chapter+1} / ${b.chapters.size} 章",color=Muted,fontSize=11.sp,modifier=Modifier.padding(top=4.dp))
                }
            } }
        }
    }
    selected?.let { b -> ModalBottomSheet(onDismissRequest={selected=null}) { Column(Modifier.padding(24.dp)) {
        Text(b.title,style=MaterialTheme.typography.titleLarge)
        ActionRow("分组",Icons.Outlined.Folder) {selected=null;onGroup(b)}
        ActionRow(if(b.pinned) "取消置顶" else "置顶",Icons.Outlined.PushPin) {selected=null;model.run {model.library.updateBook(b.id) {it.copy(pinned=!it.pinned)}}}
        ActionRow(if(b.finished) "标为未读完" else "标为已读完",Icons.Outlined.Check) {selected=null;model.run {model.library.updateBook(b.id) {it.copy(finished=!it.finished)}}}
        if(b.sourceId.isNotBlank()) ActionRow("检查目录更新",Icons.Outlined.Refresh) { selected=null; model.run("目录已更新") { val source=model.library.source(b.sourceId) ?: error("书源已删除"); val updated=model.app.engine.catalog(b,source); model.library.updateBook(b.id) { old -> updated.copy(chapter=old.chapter,offset=old.offset,anchor=old.anchor) } } }
        ActionRow("移出书架（保留本地文件）",Icons.Outlined.DeleteOutline) {selected=null;model.run {model.library.removeBook(b.id)}}
    } } }
}
@Composable fun BookCover(b: Book) {
    val colors=listOf(0xffe2e6d9,0xffe4ddd2,0xffdce4e6,0xffe8ddd7,0xffdcdce6)
    Box(Modifier.fillMaxWidth().aspectRatio(.71f).background(Color(colors[(b.title.hashCode() and Int.MAX_VALUE)%colors.size]),RoundedCornerShape(4.dp))) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(Color.Black.copy(alpha=.045f)))
        Column(Modifier.padding(14.dp).fillMaxSize(),verticalArrangement=Arrangement.SpaceBetween) {
            Text(b.title.take(18),color=Ink,fontSize=19.sp,fontFamily=FontFamily.Serif,fontWeight=FontWeight.Medium,lineHeight=27.sp,maxLines=4,overflow=TextOverflow.Ellipsis)
            HorizontalDivider(Modifier.width(24.dp),color=Ink.copy(alpha=.3f))
            Text(b.author.ifBlank { "万卷 · 藏书" },color=Ink.copy(alpha=.55f),fontSize=9.sp,maxLines=1)
        }
    }
}
@Composable fun ActionRow(title: String,icon: ImageVector,subtitle: String="",onClick: ()->Unit) {
    ListItem(headlineContent={Text(title)},supportingContent=if(subtitle.isNotBlank()) {{Text(subtitle,color=Muted)}} else null,leadingContent={Icon(icon,null)},trailingContent={Icon(Icons.Outlined.ChevronRight,null,tint=Muted)},modifier=Modifier.clickable(onClick=onClick))
}
