package io.github.godbook.wanjuan.data

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

fun uid() = UUID.randomUUID().toString()
fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
fun JSONObject.text(key: String, default: String = "") = if (isNull(key)) default else optString(key, default)
fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

data class Source(val id: String, val raw: String, val enabled: Boolean = true, val http: Boolean = false, val status: String = "尚未检测") {
    val json get() = JSONObject(raw)
    val name by lazy { json.text("bookSourceName", "未命名书源") }
    val url by lazy { json.text("bookSourceUrl") }
    val group by lazy { json.text("bookSourceGroup", "默认") }
    fun encode() = JSONObject().put("id", id).put("raw", raw).put("enabled", enabled).put("http", http).put("status", status).toString()
    companion object { fun decode(s: String) = JSONObject(s).let { Source(it.getString("id"), it.getString("raw"), it.optBoolean("enabled", true), it.optBoolean("http"), it.text("status", "尚未检测")) } }
}
data class Chapter(val id: String = uid(), val title: String, val url: String = "", val file: String = "") {
    fun json() = JSONObject().put("id", id).put("title", title).put("url", url).put("file", file)
    companion object { fun from(j: JSONObject) = Chapter(j.getString("id"), j.getString("title"), j.text("url"), j.text("file")) }
}
data class Book(
    val id: String = uid(), val title: String, val author: String = "", val intro: String = "",
    val sourceId: String = "", val url: String = "", val tocUrl: String = "", val cover: String = "",
    val chapters: List<Chapter> = emptyList(), val chapter: Int = 0, val offset: Int = 0,
    val anchor: String = "", val updated: Long = System.currentTimeMillis(), val group: String = "未分组",
    val pinned: Boolean = false, val localHash: String = "", val profile: String = "", val finished: Boolean = false
) {
    fun encode(): String = JSONObject().put("id", id).put("title", title).put("author", author).put("intro", intro)
        .put("sourceId", sourceId).put("url", url).put("tocUrl", tocUrl).put("cover", cover)
        .put("chapters", JSONArray(chapters.map { it.json() })).put("chapter", chapter).put("offset", offset)
        .put("anchor", anchor).put("updated", updated).put("group", group).put("pinned", pinned)
        .put("localHash", localHash).put("profile", profile).put("finished", finished).toString()
    companion object { fun decode(s: String): Book = JSONObject(s).let {
        Book(it.getString("id"), it.getString("title"), it.text("author"), it.text("intro"), it.text("sourceId"), it.text("url"),
            it.text("tocUrl"), it.text("cover"), it.optJSONArray("chapters")?.objects()?.map(Chapter::from) ?: emptyList(),
            it.optInt("chapter"), it.optInt("offset"), it.text("anchor"), it.optLong("updated"), it.text("group", "未分组"),
            it.optBoolean("pinned"), it.text("localHash"), it.text("profile"), it.optBoolean("finished"))
    } }
}
data class Mark(val id: String = uid(), val bookId: String, val chapter: Int, val offset: Int, val quote: String, val note: String = "") {
    fun encode() = JSONObject().put("id", id).put("bookId", bookId).put("chapter", chapter).put("offset", offset).put("quote", quote).put("note", note).toString()
    companion object { fun decode(s: String) = JSONObject(s).let { Mark(it.getString("id"), it.getString("bookId"), it.getInt("chapter"), it.getInt("offset"), it.getString("quote"), it.text("note")) } }
}
data class Profile(
    val size: Float = 20f, val line: Float = 1.6f, val margin: Float = 24f, val paragraph: Float = 10f,
    val letter: Float = 0f, val theme: Int = 0, val mode: Int = 0, val font: String = "",
    val brightness: Float = -1f, val awake: Boolean = false, val volume: Boolean = false,
    val indent: Boolean = true, val leftHand: Boolean = false, val replaceFrom: String = "", val replaceTo: String = "",
    val textColor: Long = 0L, val backgroundColor: Long = 0L, val background: String = "", val orientation: Int = 0
) {
    fun json() = JSONObject().put("size", size).put("line", line).put("margin", margin).put("paragraph", paragraph)
        .put("letter", letter).put("theme", theme).put("mode", mode).put("font", font).put("brightness", brightness)
        .put("awake", awake).put("volume", volume).put("indent", indent).put("leftHand", leftHand)
        .put("replaceFrom", replaceFrom).put("replaceTo", replaceTo).put("textColor", textColor).put("backgroundColor", backgroundColor)
        .put("background", background).put("orientation", orientation)
    companion object {
        fun resolve(global: Profile, override: String): Profile {
            if(override.isBlank()) return global
            val merged=global.json()
            runCatching { val values=JSONObject(override); values.keys().forEach { merged.put(it,values.get(it)) } }
            return from(merged.toString())
        }
        fun from(s: String): Profile = runCatching { JSONObject(s).let {
            Profile(it.optDouble("size", 20.0).toFloat().coerceIn(12f,40f), it.optDouble("line",1.6).toFloat().coerceIn(1f,2.5f),
                it.optDouble("margin",24.0).toFloat().coerceIn(8f,64f), it.optDouble("paragraph",10.0).toFloat().coerceIn(0f,32f),
                it.optDouble("letter",0.0).toFloat().coerceIn(0f,0.2f), it.optInt("theme").coerceIn(0,3), it.optInt("mode").coerceIn(0,3),
                it.text("font"), it.optDouble("brightness",-1.0).toFloat().coerceIn(-1f,1f),it.optBoolean("awake"),it.optBoolean("volume"),
                it.optBoolean("indent",true),it.optBoolean("leftHand"),it.text("replaceFrom"),it.text("replaceTo"),
                it.optLong("textColor"),it.optLong("backgroundColor"),it.text("background"),it.optInt("orientation"))
        } }.getOrDefault(Profile())
    }
}
