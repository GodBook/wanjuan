@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package io.github.godbook.wanjuan.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import io.github.godbook.wanjuan.source.BuiltinSources
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.work.WorkManager
import io.github.godbook.wanjuan.*
import io.github.godbook.wanjuan.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable fun SearchScreen(model: AppModel,onSources: ()->Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var group by rememberSaveable { mutableStateOf("全部") }
    val sources by model.sources.collectAsState()
    val results by model.results.collectAsState()
    val done by model.searchDone.collectAsState()
    val errors by model.searchErrors.collectAsState()
    val searching by model.searching.collectAsState()
    val histories by remember(model) { model.library.dao.observe("history") }.collectAsState(emptyList())
    val keyboard=LocalSoftwareKeyboardController.current
    var selected by remember { mutableStateOf<Book?>(null) }
    var showErrors by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        PageHeader("搜索","在不同书源之间，找到想读的故事。") {IconButton(onClick=onSources) {Icon(Icons.Outlined.Dns,"管理书源")}}
        Row(Modifier.padding(24.dp,12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(query,{query=it},Modifier.weight(1f),placeholder={Text("书名或作者")},singleLine=true,keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={keyboard?.hide();model.search(query,group)}),shape=androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            FilledIconButton(onClick={ if(searching) model.searchJob?.cancel() else {keyboard?.hide();model.search(query,group)} }) {Icon(if(searching) Icons.Outlined.Close else Icons.Outlined.Search,if(searching) "取消搜索" else "开始搜索")}
        }
        if(sources.none { it.enabled }) EmptyState("先添加你的书源","支持导入阅读 / Legado 的常用 JSON 规则。","管理书源",onSources)
        else {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal=24.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) { (listOf("全部")+sources.map { it.group }.distinct()).forEach { g -> FilterChip(group==g,{group=g},label={Text(g)}) } }
            if(done>0 || searching) Row(Modifier.padding(24.dp,8.dp)) {Text("已完成 $done / ${sources.count { it.enabled && (group=="全部" || it.group==group) }} · ${results.size} 个结果",style=MaterialTheme.typography.labelMedium,color=Muted); Spacer(Modifier.weight(1f)); if(errors.isNotEmpty()) Text("${errors.size} 个失败",Modifier.clickable {showErrors=true},style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.error)}
            val grouped=remember(results) { results.groupBy { it.title.trim().lowercase()+"|"+it.author.trim().lowercase().ifBlank { it.sourceId } } }
            LazyColumn(contentPadding=PaddingValues(horizontal=24.dp,vertical=8.dp)) {
                if(results.isEmpty() && !searching && done==0) {
                    item {
                        Text("从经典开始",style=MaterialTheme.typography.titleLarge,modifier=Modifier.padding(top=24.dp,bottom=8.dp))
                        Text("内置精选版本 · 搜索书名或作者即可阅读",color=Muted,style=MaterialTheme.typography.bodySmall)
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            BuiltinSources.editions.forEach { edition -> SuggestionChip(onClick={query=edition.title;keyboard?.hide();model.search(query,group)},label={Text(edition.title)}) }
                        }
                        if(histories.isNotEmpty()) Text("最近搜索",color=Muted,modifier=Modifier.padding(vertical=12.dp))
                    }
                    items(histories.takeLast(12).reversed()) { h -> ListItem(headlineContent={Text(h.id)},leadingContent={Icon(Icons.Outlined.History,null)},modifier=Modifier.clickable {query=h.id;model.search(query,group)}) }
                }
                if(results.isEmpty() && !searching && done>0) item {Text("没有找到结果。可以尝试其他关键词，或查看书源失败信息。",Modifier.padding(vertical=28.dp),color=Muted)}
                grouped.values.forEach { books -> item(key=books.first().id) {
                    Column(Modifier.fillMaxWidth().padding(vertical=12.dp)) {
                        Text(books.first().title,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Medium)
                        Text(books.first().author.ifBlank { "作者未知" },color=Muted,style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(top=4.dp))
                        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) { books.forEach { b -> AssistChip(onClick={selected=b},label={Text(sources.find { it.id==b.sourceId }?.name ?: "书源")}) } }
                    }; HorizontalDivider()
                } }
            }
        }
    }
    selected?.let { b -> AlertDialog(onDismissRequest={selected=null},title={Text(b.title)},text={Column {Text(b.author.ifBlank { "作者未知" },color=Muted); Text(b.intro.ifBlank { "选择开始阅读后，将加载书籍目录并加入书架。" },Modifier.padding(top=12.dp))}},confirmButton={TextButton(onClick={selected=null;model.addOnline(b)}) {Text("开始阅读")}},dismissButton={TextButton(onClick={selected=null;model.addOnline(b,false)}) {Text("加入书架")}}) }
    if(showErrors) AlertDialog(onDismissRequest={showErrors=false},title={Text("书源状态")},text={Column(Modifier.verticalScroll(rememberScrollState())) {errors.forEach {Text(it,Modifier.padding(vertical=8.dp))}}},confirmButton={TextButton(onClick={showErrors=false}) {Text("知道了")}})
}

@Composable fun SourcesScreen(model: AppModel,onBack: ()->Unit,onFile: ()->Unit,onPaste: ()->Unit,onExport: ()->Unit) {
    val sources by model.sources.collectAsState()
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Source?>(null) }
    var confirmDelete by remember { mutableStateOf<Source?>(null) }
    var prompt by remember { mutableStateOf<Prompt?>(null) }
    Column(Modifier.fillMaxSize()) {
        PageHeader("书源管理","${sources.count { it.enabled }} / ${sources.size} 个书源已启用",onBack) {IconButton(onClick=onExport) {Icon(Icons.Outlined.IosShare,"导出书源")}}
        Row(Modifier.padding(horizontal=24.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {Button(onClick=onFile) {Text("从文件导入")}; OutlinedButton(onClick=onPaste) {Text("链接 / 粘贴")}}
        if(sources.isNotEmpty()) OutlinedTextField(query,{query=it},Modifier.fillMaxWidth().padding(24.dp,12.dp),placeholder={Text("搜索书源名称、分组")},singleLine=true)
        TextButton(onClick={model.run("已补充缺失的内置书源") {BuiltinSources.install(model.library,true)}},modifier=Modifier.padding(horizontal=16.dp)) {Icon(Icons.Outlined.LibraryBooks,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text("恢复内置经典书源")}
        if(sources.isEmpty()) EmptyState("书源由你选择","恢复内置经典书源，或导入你自己的书源。","导入书源",onPaste)
        else LazyColumn(contentPadding=PaddingValues(16.dp)) {items(sources.filter { query.isBlank() || it.name.contains(query,true) || it.group.contains(query,true) },key={it.id}) { s ->
            ListItem(headlineContent={Text(s.name)},supportingContent={Column {Text(s.group,color=Muted);Text(s.status,style=MaterialTheme.typography.bodySmall,maxLines=2)}},trailingContent={Switch(s.enabled,{model.run {model.library.save(s.copy(enabled=it))}})},modifier=Modifier.clickable {selected=s})
            HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.4f))
        } }
    }
    selected?.let { s -> ModalBottomSheet(onDismissRequest={selected=null}) {Column(Modifier.padding(24.dp)) {
        Text(s.name,style=MaterialTheme.typography.titleLarge); Text(s.url,color=Muted,style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(vertical=8.dp))
        Row(verticalAlignment=Alignment.CenterVertically) {Text("允许此书源使用 HTTP",Modifier.weight(1f));Switch(s.http,{val next=s.copy(http=it);selected=next;model.run {model.library.save(next)}})}
        ActionRow("检测搜索规则",Icons.Outlined.NetworkCheck) {selected=null;prompt=Prompt("检测书源","输入一个应当存在的书名") { q -> model.run {
            val result=runCatching {model.app.engine.searchOne(q,s)}
            val status=result.fold({"搜索返回 ${it.size} 项 · ${java.text.SimpleDateFormat("MM-dd HH:mm",java.util.Locale.CHINA).format(java.util.Date())}"},{it.message ?: "检测失败"})
            model.library.save(s.copy(status=status));model.message.value=status
        } }}
        ActionRow("修改名称与分组",Icons.Outlined.Edit) {selected=null;prompt=Prompt("书源信息","名称 | 分组","${s.name} | ${s.group}") { value -> val parts=value.split('|');model.run {model.library.save(s.copy(raw=s.json.put("bookSourceName",parts[0].trim()).put("bookSourceGroup",parts.getOrElse(1){"默认"}.trim()).toString()))} }}
        ActionRow("删除书源",Icons.Outlined.DeleteOutline) {selected=null;confirmDelete=s}
    } } }
    confirmDelete?.let { s -> AlertDialog(onDismissRequest={confirmDelete=null},title={Text("删除 ${s.name}？")},text={Text("已缓存内容保留。使用此书源的书籍将需要换源才能获取新章节。")},confirmButton={TextButton(onClick={confirmDelete=null;model.run {model.library.dao.delete("source",s.id)}}) {Text("删除")}},dismissButton={TextButton(onClick={confirmDelete=null}) {Text("取消")}}) }
    prompt?.let {TextPrompt(it) {prompt=null}}
}

@Composable fun SettingsScreen(model: AppModel,dark: Int,onDark: (Int)->Unit,encoding: String,onEncoding: (String)->Unit,onSources: ()->Unit,onDownloads: ()->Unit,onBackup: ()->Unit,onRestore: ()->Unit,onDav: ()->Unit,onUpdate: ()->Unit,onReaderSettings: ()->Unit) {
    LazyColumn {
        item {PageHeader("设置","按自己的习惯，读每一本书。")}
        item {SectionLabel("阅读与外观");ActionRow("阅读设置",Icons.Outlined.TextFields,"字体、排版、主题与翻页",onReaderSettings)}
        item {Row(Modifier.padding(horizontal=24.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {listOf("跟随系统","浅色","深色").forEachIndexed { i,label -> FilterChip(dark==i,{onDark(i)},label={Text(label)})}}}
        item {SectionLabel("书库管理");ActionRow("书源管理",Icons.Outlined.Dns,"导入与管理多个书源",onSources);ActionRow("离线下载",Icons.Outlined.Download,"查看队列、暂停与继续",onDownloads);ActionRow("清理临时缓存",Icons.Outlined.CleaningServices,"保留本地书籍和明确下载的章节") {model.run("临时缓存已清理") {model.library.trimCache(0)}}}
        item {Text("TXT 导入编码",Modifier.padding(24.dp,8.dp),style=MaterialTheme.typography.bodyMedium);Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal=24.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {listOf("自动","UTF-8","GB18030","UTF-16LE","UTF-16BE").forEach { e -> FilterChip(encoding==e,{onEncoding(e)},label={Text(e)})}}}
        item {SectionLabel("备份与恢复");ActionRow("导出备份",Icons.Outlined.SaveAlt,"包含书籍、书源、进度、笔记与阅读设置",onBackup);ActionRow("从文件恢复",Icons.Outlined.Restore,"校验后恢复完整快照",onRestore);ActionRow("WebDAV 备份",Icons.Outlined.CloudUpload,"使用你自己的云端存储",onDav)}
        item {SectionLabel("关于万卷");ActionRow("检查更新",Icons.Outlined.SystemUpdate,"当前版本 ${BuildConfig.VERSION_NAME}",onUpdate)}
        item {Text("本地优先 · 无需账号\n不内置内容书源，不上传阅读数据。\n书源兼容范围请查看仓库文档。",Modifier.padding(24.dp),color=Muted,style=MaterialTheme.typography.bodySmall)}
    }
}
@Composable fun SectionLabel(text: String) {Text(text,Modifier.padding(24.dp,22.dp,24.dp,8.dp),style=MaterialTheme.typography.labelMedium,color=Muted)}

@Composable fun DownloadsScreen(model: AppModel,onBack: ()->Unit) {
    val context=model.app
    val infos by WorkManager.getInstance(context).getWorkInfosByTagFlow("downloads").collectAsState(emptyList())
    val books by model.books.collectAsState()
    Column {
        PageHeader("离线下载","默认等待 Wi-Fi，系统可能延迟执行。",onBack)
        if(infos.isEmpty()) Text("在阅读菜单中选择缓存，即可离线阅读。",Modifier.padding(24.dp),color=Muted)
        LazyColumn {items(infos.groupBy {it.tags.firstOrNull {tag ->tag.startsWith("book:")}}.values.toList()) { all ->
            val generation=all.maxOf {it.tags.firstOrNull {tag ->tag.startsWith("generation:")}?.substringAfter(':')?.toLongOrNull() ?: 0}
            val batch=all.filter {(it.tags.firstOrNull {tag ->tag.startsWith("generation:")}?.substringAfter(':')?.toLongOrNull() ?: 0)==generation}
            val info=batch.firstOrNull {it.state==androidx.work.WorkInfo.State.RUNNING} ?: batch.firstOrNull {it.state==androidx.work.WorkInfo.State.ENQUEUED} ?: batch.firstOrNull {it.state==androidx.work.WorkInfo.State.FAILED} ?: batch.first()
            val id=info.tags.firstOrNull {it.startsWith("book:")}?.removePrefix("book:")
            val b=books.find {it.id==id}
            val total=batch.sumOf {it.tags.firstOrNull {tag ->tag.startsWith("batch:")}?.substringAfter(':')?.toIntOrNull() ?: 0}
            val done=batch.sumOf {if(it.state==androidx.work.WorkInfo.State.SUCCEEDED) it.tags.firstOrNull {tag ->tag.startsWith("batch:")}?.substringAfter(':')?.toIntOrNull() ?: 0 else it.progress.getInt("done",0)}
            val finished=batch.all {it.state==androidx.work.WorkInfo.State.SUCCEEDED}
            val active=batch.any {!it.state.isFinished}
            ListItem(headlineContent={Text(b?.title ?: "书籍已移出书架")},supportingContent={Text("${if(finished) "下载完成" else when(info.state.name) {"ENQUEUED","BLOCKED" -> "等待执行";"RUNNING" -> "正在下载";"FAILED" -> "下载失败";else -> "已暂停"}} · $done / $total\n${info.outputData.getString("error").orEmpty()}")},trailingContent={
                if(active) TextButton(onClick={WorkManager.getInstance(context).cancelUniqueWork("download:$id")}) {Text("暂停")}
                else if(b!=null && !finished) TextButton(onClick={val range=info.tags.firstOrNull {it.startsWith("range:")}?.split(':');ChapterWorker.enqueue(context,b,range?.getOrNull(1)?.toIntOrNull() ?: b.chapter,range?.getOrNull(2)?.toIntOrNull() ?: b.chapters.lastIndex)}) {Text("继续")}
            })
        } }
    }
}

@Composable fun WebDavScreen(model: AppModel,onBack: ()->Unit,onRestore: (File,String)->Unit) {
    val dav=remember {WebDav(model.app)}
    val initial=remember {dav.load()}
    var url by remember {mutableStateOf(initial?.url ?: "")}
    var user by remember {mutableStateOf(initial?.username ?: "")}
    var password by remember {mutableStateOf(initial?.password ?: "")}
    var private by remember {mutableStateOf(initial?.allowPrivate ?: false)}
    var remote by remember {mutableStateOf<List<String>>(emptyList())}
    val backupWork by WorkManager.getInstance(model.app).getWorkInfosForUniqueWorkFlow("daily-backup").collectAsState(emptyList())
    val preferences by model.library.preferences.collectAsState(org.json.JSONObject())
    Column(Modifier.verticalScroll(rememberScrollState())) {
        PageHeader("WebDAV","备份到你自己的空间，不进行自动合并。",onBack)
        Column(Modifier.padding(horizontal=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(url,{url=it},Modifier.fillMaxWidth(),label={Text("HTTPS 目录地址")},singleLine=true)
            OutlinedTextField(user,{user=it},Modifier.fillMaxWidth(),label={Text("用户名")},singleLine=true)
            OutlinedTextField(password,{password=it},Modifier.fillMaxWidth(),label={Text("密码 / 应用密码")},visualTransformation=PasswordVisualTransformation(),singleLine=true)
            Row(verticalAlignment=Alignment.CenterVertically) {Checkbox(private,{private=it});Text("允许连接我的私有服务器")}
            Button(onClick={model.run("配置已加密保存") {dav.save(DavConfig(url,user,password,private))}}) {Text("保存配置")}
            Text("请使用已存在的目录。凭据只保存在此设备，导出的备份不含密码。",color=Muted,style=MaterialTheme.typography.bodySmall)
            Toggle("每天自动备份（Wi-Fi）",backupWork.any {!it.state.isFinished}) {enabled ->
                if(enabled && dav.load()==null) model.message.value="请先保存 WebDAV 配置" else AutomaticBackup.schedule(model.app,enabled)
            }
            Text("自动备份受系统调度影响；成功校验后，仅保留本设备最近 10 份快照。",color=Muted,style=MaterialTheme.typography.bodySmall)
            if(preferences.optLong("lastAutomaticBackup")>0) Text("最近成功："+java.text.DateFormat.getDateTimeInstance().format(java.util.Date(preferences.optLong("lastAutomaticBackup"))),style=MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick={model.run {val file=Backup(model.library).create();try {val name=dav.upload(file);model.message.value="已上传并校验：$name"} finally {file.delete()}}}) {Text("立即备份到 WebDAV")}
            OutlinedButton(onClick={model.run {val names=dav.list();withContext(Dispatchers.Main) {remote=names};if(names.isEmpty()) model.message.value="目录中没有万卷备份"}}) {Text("查看远端备份")}
            remote.forEach { name -> TextButton(onClick={model.run {val f=dav.download(name);val preview=Backup(model.library).inspect(f);withContext(Dispatchers.Main) {onRestore(f,preview)}}}) {Text(name)} }
        }
    }
}
