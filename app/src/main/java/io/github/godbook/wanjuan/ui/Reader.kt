@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package io.github.godbook.wanjuan.ui

import android.content.pm.ActivityInfo
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.godbook.wanjuan.*
import io.github.godbook.wanjuan.data.*
import io.github.godbook.wanjuan.reader.ReadingView
import kotlinx.coroutines.*
import java.io.File

private data class ReaderText(val raw: String,val from: String,val to: String,val indent: Boolean)

@Composable fun ReaderScreen(model: AppModel,activity: MainActivity,onSources: ()->Unit) {
    val book by model.reader.collectAsState()
    val raw by model.body.collectAsState()
    val generation by model.readerGeneration.collectAsState()
    val global by model.profile.collectAsState()
    val marks by model.marks.collectAsState()
    val sourceList by model.sources.collectAsState()
    val b=book ?: return
    val profile=remember(global,b.profile) {Profile.resolve(global,b.profile)}
    var menu by remember {mutableStateOf(false)}
    var sheet by remember {mutableStateOf("")}
    var prompt by remember {mutableStateOf<Prompt?>(null)}
    var page by remember {mutableIntStateOf(1)}
    var pageCount by remember {mutableIntStateOf(1)}
    var view by remember {mutableStateOf<ReadingView?>(null)}
    var candidate by remember {mutableStateOf<Book?>(null)}
    var candidates by remember {mutableStateOf<List<Book>>(emptyList())}
    var wifi by remember {mutableStateOf(true)}
    var downloadEnd by remember {mutableIntStateOf(0)}
    var clock by remember {mutableStateOf("")}
    val fg=Color(ReadingView.foreground(profile))
    val bg=Color(ReadingView.background(profile))
    LaunchedEffect(bg) { updateSystemBars(activity,androidx.core.graphics.ColorUtils.calculateLuminance(ReadingView.background(profile))>.5) }
    val textInput=remember(raw,profile.replaceFrom,profile.replaceTo,profile.indent) { ReaderText(raw,profile.replaceFrom,profile.replaceTo,profile.indent) }
    val processed by produceState<Pair<ReaderText,String>?>(null,textInput) {
        value=textInput to withContext(Dispatchers.Default) {
            val cleaned=if(textInput.from.isNotBlank()) textInput.raw.replace(textInput.from,textInput.to) else textInput.raw
            cleaned.lineSequence().map {it.trim()}.filter {it.isNotBlank()}.joinToString("\n") { if(textInput.indent && !it.startsWith("[[wanjuan-image:")) "　　$it" else it }
        }
    }
    // Never display or persist the previous chapter while its replacement is being prepared.
    val transformed=processed?.takeIf {it.first===textInput}?.second.orEmpty()
    LaunchedEffect(Unit) {while(true) {clock=java.text.SimpleDateFormat("HH:mm",java.util.Locale.CHINA).format(java.util.Date());delay(30000)}}
    DisposableEffect(profile.awake,profile.brightness,profile.volume,profile.orientation) {
        val window=activity.window
        if(profile.awake) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes=window.attributes.apply {screenBrightness=profile.brightness}
        activity.volumeTurn=if(profile.volume) {{forward -> view?.turn(forward)}} else null
        activity.requestedOrientation=when(profile.orientation) {1 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;2 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED}
        onDispose {activity.volumeTurn=null;window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);window.attributes=window.attributes.apply {screenBrightness=-1f};activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED}
    }
    BackHandler {when {sheet.isNotBlank() -> sheet="";menu -> menu=false;else -> model.closeReader()}}
    Box(Modifier.fillMaxSize().background(bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
      Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=8.dp)) {Text(b.chapters.getOrNull(b.chapter)?.title ?: b.title,Modifier.weight(1f),maxLines=1,color=fg.copy(alpha=.5f),style=MaterialTheme.typography.labelSmall)}
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if(transformed.isBlank()) Column(Modifier.align(Alignment.Center),horizontalAlignment=Alignment.CenterHorizontally) {
                Text("正在准备正文",color=fg);TextButton(onClick={model.open(b)}) {Text("重试")};TextButton(onClick={menu=true;sheet="toc"}) {Text("打开目录")};TextButton(onClick={model.closeReader()}) {Text("返回书架")}
            } else AndroidView(factory={ctx -> ReadingView(ctx).also {v ->view=v}},modifier=Modifier.fillMaxSize(),update={v ->
                v.onMenu={menu=!menu};v.onBoundary={model.turnChapter(it)};v.onLongPress={sheet="select"}
                v.onPosition={offset,quote,p,count -> page=p;pageCount=count;model.position(offset,quote)}
                v.setContent(transformed,profile,b.offset,b.anchor,generation)
            })
        }
        Row(Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=8.dp),horizontalArrangement=Arrangement.SpaceBetween) {
            Text(clock,color=fg.copy(alpha=.5f),style=MaterialTheme.typography.labelSmall)
            Text("${b.chapter+1}/${b.chapters.size} 章 · $page/$pageCount 页",color=fg.copy(alpha=.5f),style=MaterialTheme.typography.labelSmall)
        }
      }
      if(menu) Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().background(bg),verticalAlignment=Alignment.CenterVertically) {
                IconButton(onClick={model.closeReader()}) {Icon(Icons.Outlined.ArrowBack,"返回书架",tint=fg)}
                Text(b.title,Modifier.weight(1f),maxLines=1,color=fg)
                IconButton(onClick={model.bookmark()}) {Icon(Icons.Outlined.BookmarkAdd,"添加书签",tint=fg)}
                IconButton(onClick={sheet="more"}) {Icon(Icons.Outlined.MoreHoriz,"更多阅读操作",tint=fg)}
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth().background(bg),horizontalArrangement=Arrangement.SpaceEvenly) {
                ReaderAction("目录",Icons.Outlined.FormatListBulleted) {sheet="toc"}
                ReaderAction("进度",Icons.Outlined.SwapHoriz) {sheet="progress"}
                ReaderAction("换源",Icons.Outlined.SyncAlt) {if(b.sourceId.isBlank()) model.message.value="本地书籍无需换源" else {sheet="sources";candidates=emptyList();model.run {model.app.engine.search(b.title,model.sources.value).collect { event -> withContext(Dispatchers.Main) {candidates=candidates+event.books} }}}}
                ReaderAction("设置",Icons.Outlined.TextFields) {sheet="settings"}
            }
      }
    }
    if(sheet=="settings") ModalBottomSheet(onDismissRequest={sheet=""}) {Box(Modifier.fillMaxHeight(.88f)) {ProfileSettings(model,profile,b.profile.isNotBlank(),{sheet=""},activity)}}
    if(sheet=="toc") ModalBottomSheet(onDismissRequest={sheet=""}) {
        var query by remember {mutableStateOf("")}
        val state=rememberLazyListState(initialFirstVisibleItemIndex=b.chapter)
        Column(Modifier.fillMaxHeight(.85f)) {
            Text("目录 · ${b.chapters.size} 章",Modifier.padding(24.dp),style=MaterialTheme.typography.titleLarge)
            OutlinedTextField(query,{query=it},Modifier.fillMaxWidth().padding(horizontal=24.dp),placeholder={Text("搜索章节")},singleLine=true)
            LazyColumn(state=state,contentPadding=PaddingValues(16.dp)) {itemsIndexed(b.chapters.filter {query.isBlank() || it.title.contains(query)}) { _,chapter ->
                val index=b.chapters.indexOf(chapter)
                ListItem(headlineContent={Text(chapter.title,color=if(index==b.chapter) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)},supportingContent={if(index==b.chapter) Text("正在阅读")},modifier=Modifier.clickable {model.flush();model.open(b,index,0,"");sheet=""})
            } }
        }
    }
    if(sheet=="progress") ModalBottomSheet(onDismissRequest={sheet=""}) {Column(Modifier.padding(24.dp)) {
        Text("阅读进度",style=MaterialTheme.typography.titleLarge)
        var progress by remember {mutableFloatStateOf(b.chapter.toFloat())}
        Slider(progress,{progress=it},valueRange=0f..b.chapters.lastIndex.coerceAtLeast(1).toFloat(),onValueChangeFinished={model.flush();model.open(b,progress.toInt(),0,"")})
        Text(b.chapters.getOrNull(progress.toInt())?.title.orEmpty())
        Row {TextButton(onClick={model.turnChapter(false)}) {Text("上一章")};TextButton(onClick={model.turnChapter(true)}) {Text("下一章")}}
    } }
    if(sheet=="more") ModalBottomSheet(onDismissRequest={sheet=""}) {Column(Modifier.padding(16.dp)) {
        ActionRow("书签与笔记",Icons.Outlined.Bookmarks) {sheet="marks"}
        ActionRow("添加笔记",Icons.Outlined.EditNote) {sheet="";prompt=Prompt("添加笔记","记录此刻的想法",multiline=true) {model.bookmark(it)}}
        ActionRow("本章查找",Icons.Outlined.Search) {sheet="";prompt=Prompt("查找正文","输入要查找的文字") {q -> val index=transformed.indexOf(q,b.offset+1).let {if(it<0) transformed.indexOf(q) else it};if(index<0 || q.isBlank()) model.message.value="本章未找到" else {model.open(b,b.chapter,index,q);view?.setContent("",profile,0,"")}}}
        if(b.sourceId.isNotBlank()) ActionRow("离线缓存",Icons.Outlined.Download) {downloadEnd=b.chapters.lastIndex;sheet="download"}
        ActionRow("书籍信息",Icons.Outlined.Info) {sheet="info"}
    } }
    if(sheet=="marks") ModalBottomSheet(onDismissRequest={sheet=""}) {LazyColumn(contentPadding=PaddingValues(24.dp)) {
        item {Text("书签与笔记",style=MaterialTheme.typography.titleLarge)}
        val bookMarks=marks.filter {it.bookId==b.id}
        if(bookMarks.isEmpty()) item {Text("还没有书签。阅读菜单右上角可以添加。",Modifier.padding(vertical=20.dp),color=Muted)}
        items(bookMarks,key={it.id}) {mark -> ListItem(headlineContent={Text(b.chapters.getOrNull(mark.chapter)?.title ?: "章节已变化")},supportingContent={Text(mark.note.ifBlank {mark.quote})},modifier=Modifier.clickable {model.open(b,mark.chapter,mark.offset,mark.quote);sheet=""},trailingContent={IconButton(onClick={model.run {model.library.dao.delete("mark",mark.id)}}) {Icon(Icons.Outlined.DeleteOutline,"删除书签")}})}
    } }
    if(sheet=="download") ModalBottomSheet(onDismissRequest={sheet=""}) {Column(Modifier.padding(24.dp)) {
        Text("离线缓存",style=MaterialTheme.typography.titleLarge)
        Text("从第 ${b.chapter+1} 章到第 ${downloadEnd+1} 章",Modifier.padding(top=16.dp))
        if(b.chapters.lastIndex>b.chapter) Slider(downloadEnd.toFloat(),{downloadEnd=it.toInt()},valueRange=b.chapter.toFloat()..b.chapters.lastIndex.toFloat())
        Row(verticalAlignment=Alignment.CenterVertically) {Checkbox(wifi,{wifi=it});Text("仅 Wi-Fi 下载")}
        Button(onClick={ChapterWorker.enqueue(activity,b,b.chapter,downloadEnd,wifi);sheet="";model.message.value="已加入下载队列"}) {Text("开始缓存")}
    } }
    if(sheet=="sources") ModalBottomSheet(onDismissRequest={sheet=""}) {LazyColumn(contentPadding=PaddingValues(24.dp)) {
        item {Text("选择其他来源",style=MaterialTheme.typography.titleLarge);Text("选择来源后，确认对应章节。",color=Muted)}
        items(candidates) {item -> ListItem(headlineContent={Text(item.title)},supportingContent={Text("${item.author} · ${sourceList.find {it.id==item.sourceId}?.name}")},modifier=Modifier.clickable {model.run {val source=model.library.source(item.sourceId) ?: error("书源不存在");val loaded=model.app.engine.catalog(item,source);withContext(Dispatchers.Main) {candidate=loaded;sheet="match"}}})}
    } }
    if(sheet=="match") candidate?.let { target -> ModalBottomSheet(onDismissRequest={sheet=""}) {
        var query by remember {mutableStateOf(b.chapters.getOrNull(b.chapter)?.title.orEmpty())}
        Column(Modifier.fillMaxHeight(.85f).padding(24.dp)) {Text("确认目标章节",style=MaterialTheme.typography.titleLarge);Text("原位置：${b.chapters.getOrNull(b.chapter)?.title}\n不同版本可能缺章或分章，请确认后切换。",color=Muted);OutlinedTextField(query,{query=it},Modifier.fillMaxWidth().padding(vertical=12.dp),label={Text("章节筛选（可清空）")})
            LazyColumn {items(target.chapters.filter {query.isBlank() || it.title.contains(query)}) {c -> ListItem(headlineContent={Text(c.title)},modifier=Modifier.clickable {model.switchSource(b,target,target.chapters.indexOf(c));sheet=""})}}
        }
    } }
    if(sheet=="info") AlertDialog(onDismissRequest={sheet=""},title={Text(b.title)},text={Text("${b.author}\n\n${b.intro.ifBlank { "${b.chapters.size} 章 · ${if(b.sourceId.isBlank()) "本地书籍" else "在线书籍"}" }}")},confirmButton={TextButton(onClick={sheet=""}) {Text("关闭")}})
    if(sheet=="select") ModalBottomSheet(onDismissRequest={sheet=""}) {Column(Modifier.padding(24.dp)) {Text("长按选择文字，可复制内容",style=MaterialTheme.typography.labelLarge);AndroidView(factory={ctx ->TextView(ctx).apply {setTextIsSelectable(true);textSize=18f}},update={it.text=transformed.substring(b.offset.coerceAtMost(transformed.length),(b.offset+3000).coerceAtMost(transformed.length))},modifier=Modifier.heightIn(max=380.dp).verticalScroll(rememberScrollState()));TextButton(onClick={sheet="";prompt=Prompt("添加笔记","记录此处文字",multiline=true) {model.bookmark(it)}}) {Text("为当前位置添加笔记")}}}
    prompt?.let {TextPrompt(it) {prompt=null}}
}
@Composable fun ReaderAction(label: String,icon: androidx.compose.ui.graphics.vector.ImageVector,onClick: ()->Unit) {TextButton(onClick=onClick) {Column(horizontalAlignment=Alignment.CenterHorizontally) {Icon(icon,label);Text(label,style=MaterialTheme.typography.labelSmall)}}}

@Composable fun ProfileSettings(model: AppModel,initial: Profile,initialPerBook: Boolean,onClose: ()->Unit,activity: MainActivity) {
    val activeBook by model.reader.collectAsState()
    var p by remember {mutableStateOf(initial)}
    var perBook by remember {mutableStateOf(initialPerBook)}
    var prompt by remember {mutableStateOf<Prompt?>(null)}
    fun change(value: Profile) {p=value;model.saveProfile(value,perBook)}
    val importFont=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri -> if(uri!=null) model.run {
        val file=model.library.file("assets/font-${uid()}.ttf");file.parentFile?.mkdirs();activity.contentResolver.openInputStream(uri)!!.use {file.writeBytes(it.readBytesLimited(20*1024*1024))}
        try {android.graphics.Typeface.createFromFile(file)} catch(e: Exception) {file.delete();error("无法读取此字体")}
        withContext(Dispatchers.Main) {change(p.copy(font=file.path))}
    } }
    val importBackground=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri -> if(uri!=null) model.run {
        val file=model.library.file("assets/background-${uid()}.img");file.parentFile?.mkdirs();activity.contentResolver.openInputStream(uri)!!.use {file.writeBytes(it.readBytesLimited(10*1024*1024))}
        val options=android.graphics.BitmapFactory.Options().apply {inJustDecodeBounds=true};android.graphics.BitmapFactory.decodeFile(file.path,options)
        require(options.outWidth>0 && options.outWidth.toLong()*options.outHeight<=40_000_000) {"图片尺寸过大或格式不支持"}
        withContext(Dispatchers.Main) {change(p.copy(background=file.path))}
    } }
    val presets by model.library.dao.observe("preset").collectAsState(emptyList())
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal=24.dp),verticalAlignment=Alignment.CenterVertically) {Text("阅读设置",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge);TextButton(onClick=onClose) {Text("完成")}}
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal=24.dp,vertical=12.dp)) {
            if(activeBook!=null) Row(verticalAlignment=Alignment.CenterVertically) {Text("仅对本书生效",Modifier.weight(1f));Switch(perBook,{perBook=it;if(it) model.saveProfile(p,true) else model.run {val b=model.reader.value ?: return@run;model.library.updateBook(b.id) {book ->book.copy(profile="")};model.reader.value=b.copy(profile="");withContext(Dispatchers.Main) {p=model.profile.value}}})}
            SettingSlider("字号",p.size,12f..40f) {change(p.copy(size=it))}
            SettingSlider("行距",p.line,1f..2.5f) {change(p.copy(line=it))}
            SettingSlider("页边距",p.margin,8f..64f) {change(p.copy(margin=it))}
            SettingSlider("段间距",p.paragraph,0f..32f) {change(p.copy(paragraph=it))}
            SettingSlider("字间距",p.letter,0f..0.2f) {change(p.copy(letter=it))}
            Text("阅读主题",style=MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {listOf("素白","纸色","护眼","夜间").forEachIndexed {i,label ->FilterChip(p.theme==i,{change(p.copy(theme=i,textColor=0,backgroundColor=0))},label={Text(label)})}}
            Row {TextButton(onClick={prompt=Prompt("自定义颜色","正文色,背景色（#RRGGBB）","#262C28,#FAF9F5") {value ->runCatching {val parts=value.split(',');change(p.copy(textColor=android.graphics.Color.parseColor(parts[0].trim()).toLong(),backgroundColor=android.graphics.Color.parseColor(parts[1].trim()).toLong()))}.onFailure {model.message.value="颜色格式错误"}}}) {Text("自定配色")};TextButton(onClick={importBackground.launch(arrayOf("image/*"))}) {Text("背景图片")};if(p.background.isNotBlank()) TextButton(onClick={change(p.copy(background=""))}) {Text("清除背景")}}
            Text("翻页方式",style=MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {listOf("覆盖","滑动","无动画","上下滚动").forEachIndexed {i,label ->FilterChip(p.mode==i,{change(p.copy(mode=i))},label={Text(label)})}}
            Row {TextButton(onClick={importFont.launch(arrayOf("*/*"))}) {Text("导入字体")};TextButton(onClick={change(p.copy(font=""))}) {Text("系统字体")}}
            Text(if(p.font.isBlank()) "当前使用系统字体" else "当前使用自定义字体",style=MaterialTheme.typography.bodySmall,color=Muted)
            Toggle("首行缩进",p.indent) {change(p.copy(indent=it))}
            Toggle("左手翻页（交换点击区域）",p.leftHand) {change(p.copy(leftHand=it))}
            Toggle("音量键翻页",p.volume) {change(p.copy(volume=it))}
            Toggle("阅读时保持亮屏",p.awake) {change(p.copy(awake=it))}
            Toggle("亮度跟随系统",p.brightness<0) {change(p.copy(brightness=if(it) -1f else .5f))}
            if(p.brightness>=0) SettingSlider("亮度",p.brightness,0.02f..1f) {change(p.copy(brightness=it))}
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {listOf("自动旋转","竖屏","横屏").forEachIndexed {i,label ->FilterChip(p.orientation==i,{change(p.copy(orientation=i))},label={Text(label)})}}
            Text("正文替换（按原文匹配）",Modifier.padding(top=16.dp),style=MaterialTheme.typography.labelLarge)
            var from by remember {mutableStateOf(p.replaceFrom)};var to by remember {mutableStateOf(p.replaceTo)}
            OutlinedTextField(from,{from=it},Modifier.fillMaxWidth().padding(top=8.dp),label={Text("要替换的文字")})
            OutlinedTextField(to,{to=it},Modifier.fillMaxWidth().padding(top=8.dp),label={Text("替换为（可留空）")})
            TextButton(onClick={change(p.copy(replaceFrom=from,replaceTo=to))}) {Text("应用替换")}
            HorizontalDivider(Modifier.padding(vertical=12.dp))
            Row {TextButton(onClick={prompt=Prompt("保存阅读预设","预设名称") {name -> if(name.isNotBlank()) model.run("预设已保存") {model.library.dao.put(Record("preset",name,p.json().toString()))}}}) {Text("保存为预设")};TextButton(onClick={change(Profile())}) {Text("恢复默认")}}
            presets.forEach {preset -> TextButton(onClick={change(Profile.from(preset.payload))}) {Text("应用：${preset.id}")}}
        }
    }
    prompt?.let {TextPrompt(it) {prompt=null}}
}
@Composable fun SettingSlider(label: String,value: Float,range: ClosedFloatingPointRange<Float>,onChange: (Float)->Unit) {
    var local by remember(value) {mutableFloatStateOf(value)}
    Row {Text(label,Modifier.weight(1f),style=MaterialTheme.typography.labelLarge);Text("%.2f".format(local),color=Muted,style=MaterialTheme.typography.labelMedium)}
    Slider(local,{local=it},valueRange=range,onValueChangeFinished={onChange(local)})
}
@Composable fun Toggle(label: String,value: Boolean,onChange: (Boolean)->Unit) {Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {Text(label,Modifier.weight(1f));Switch(value,onChange)}}
