@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package io.github.godbook.wanjuan.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import io.github.godbook.wanjuan.AppModel
import io.github.godbook.wanjuan.data.Storage
import io.github.godbook.wanjuan.data.StorageEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun formatBytes(bytes: Long)=if(bytes>=1024*1024) "%.1f MiB".format(bytes.toDouble()/1024/1024) else "%.1f KiB".format(bytes.toDouble()/1024)

@Composable fun StorageScreen(model: AppModel,onBack: ()->Unit) {
    val storage=remember(model) {Storage(model.library)}
    var entries by remember {mutableStateOf<List<StorageEntry>>(emptyList())}
    var selected by remember {mutableStateOf<Set<String>>(emptySet())}
    var confirm by remember {mutableStateOf(false)}
    var details by remember {mutableStateOf<Set<String>>(emptySet())}
    var expanded by remember {mutableStateOf<Set<String>>(emptySet())}
    val busy by model.busy.collectAsState()
    fun refresh() {model.run {val next=storage.scan();withContext(Dispatchers.Main) {entries=next;selected=emptySet()}}}
    LaunchedEffect(Unit) {refresh()}
    Column(Modifier.fillMaxSize()) {
        PageHeader("存储管理","书库文件共 ${formatBytes(entries.sumOf {it.bytes})}",onBack) {
            IconButton(enabled=!busy,onClick={refresh()}) {Icon(Icons.Outlined.Refresh,"刷新存储占用")}
        }
        Text("本地书籍与正在使用的字体、背景受保护。移出书架后可选择清理遗留文件。删除下载后需联网重新获取。",Modifier.padding(horizontal=24.dp),color=Muted)
        val chosen=entries.filter {it.path in selected}
        Button(enabled=selected.isNotEmpty() && !busy,onClick={confirm=true},modifier=Modifier.padding(24.dp,12.dp)) {Text("删除已选 ${chosen.size} 项 · ${formatBytes(chosen.sumOf {it.bytes})}")}
        LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(horizontal=16.dp,vertical=8.dp)) {
            val order=listOf("本地书籍","已下载正文","临时缓存","正在使用的字体与背景","未使用书籍文件","未使用资源")
            entries.groupBy {it.category}.toList().sortedBy {order.indexOf(it.first)}.forEach {(category,files) ->
                item(key=category) {
                    Row(Modifier.fillMaxWidth().clickable {expanded=if(category in expanded) expanded-category else expanded+category}.padding(8.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {Text(category,style=MaterialTheme.typography.titleSmall);Text("${files.size} 个文件 · ${formatBytes(files.sumOf {it.bytes})}",style=MaterialTheme.typography.bodySmall,color=Muted)}
                        if(files.any {it.removable}) TextButton(enabled=!busy,onClick={val paths=files.filter {it.removable}.map {it.path}.toSet();selected=if(selected.containsAll(paths)) selected-paths else selected+paths}) {Text("选择此类")}
                        IconButton(onClick={expanded=if(category in expanded) expanded-category else expanded+category}) {Icon(if(category in expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,"${if(category in expanded) "收起" else "展开"}$category")}
                    }
                }
                if(category in expanded) items(files,key={it.path}) {entry ->
                    fun toggle() {selected=if(entry.path in selected) selected-entry.path else selected+entry.path}
                    ListItem(headlineContent={Text(entry.label,maxLines=2,overflow=TextOverflow.Ellipsis)},supportingContent={Column {
                        Text(formatBytes(entry.bytes)+if(entry.removable) "" else " · 正在使用")
                        if(entry.references.isNotBlank()) Text(entry.references,style=MaterialTheme.typography.bodySmall)
                        if(entry.path in details) Text(entry.path,style=MaterialTheme.typography.bodySmall)
                    }},
                        leadingContent={Checkbox(entry.path in selected,{toggle()},enabled=entry.removable && !busy)},
                        trailingContent={IconButton(onClick={details=if(entry.path in details) details-entry.path else details+entry.path}) {Icon(Icons.Outlined.Info,"查看文件路径")}},
                        modifier=Modifier.clickable(enabled=entry.removable && !busy) {toggle()})
                }
            }
            if(entries.isEmpty()) item {Text(if(busy) "正在检查文件占用" else "暂无书库文件",Modifier.padding(24.dp),color=Muted)}
        }
    }
    if(confirm) AlertDialog(onDismissRequest={confirm=false},title={Text("删除已选文件？")},text={Text("将删除 ${selected.size} 个文件。已下载正文需要重新联网下载；未使用的本地文件删除后需重新导入。书架与笔记不会删除。")},
        confirmButton={TextButton(onClick={confirm=false;val paths=selected;model.run {val bytes=storage.delete(paths);val next=storage.scan();withContext(Dispatchers.Main) {entries=next;selected=emptySet()};model.message.value="已释放 ${formatBytes(bytes)}"}}) {Text("删除文件")}},
        dismissButton={TextButton(onClick={confirm=false}) {Text("取消")}})
}
