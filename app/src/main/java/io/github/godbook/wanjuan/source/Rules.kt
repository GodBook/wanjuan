package io.github.godbook.wanjuan.source

import com.jayway.jsonpath.JsonPath
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.helper.W3CDom
import org.json.JSONArray
import org.json.JSONObject
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

object Rules {
    fun css(rule: String) = rule.removePrefix("@css:").replace(Regex("(^|[ >])class\\."),"$1.").replace(Regex("(^|[ >])id\\."),"$1#").replace(Regex("(^|[ >])tag\\."),"$1")
    fun nodes(input: String, rule: String, base: String = ""): List<String> {
        require(rule.isNotBlank()) { "列表规则为空" }
        return when {
            rule.startsWith("$") || rule.startsWith("@json:") -> {
                val value: Any? = JsonPath.read(input,rule.removePrefix("@json:"))
                (if(value is List<*>) value else listOf(value)).map { v -> when(v) { is Map<*,*> -> JSONObject(v).toString(); is List<*> -> JSONArray(v).toString(); else -> v.toString() } }
            }
            rule.startsWith("//") || rule.startsWith("@XPath:") -> xpath(input,rule.removePrefix("@XPath:"))
            else -> Jsoup.parse(input,base).select(css(rule)).map { it.outerHtml() }
        }
    }
    fun value(input: String, rule: String, base: String = ""): String {
        if(rule.isBlank()) return ""
        require(!rule.contains("@js:") && !rule.contains("<js>")) { "此位置的脚本组合暂不支持" }
        if (rule.contains("||")) return rule.split("||").firstNotNullOfOrNull { part -> runCatching { value(input,part.trim(),base) }.getOrNull()?.takeIf { it.isNotBlank() } } ?: ""
        if (rule.contains("&&")) return rule.split("&&").joinToString("\n") { value(input,it.trim(),base) }
        val pieces = rule.split("##")
        val selector = pieces[0].trim()
        var result = when {
            selector.startsWith("$") || selector.startsWith("@json:") -> {
                val v: Any? = JsonPath.read(input,selector.removePrefix("@json:"))
                if(v is List<*>) v.joinToString("\n") else v?.toString().orEmpty()
            }
            selector.startsWith("//") || selector.startsWith("@XPath:") -> xpath(input,selector.removePrefix("@XPath:")).joinToString("\n")
            else -> {
                val s = selector.removePrefix("@css:")
                val at = s.lastIndexOf('@')
                val attr = if(at >= 0) s.substring(at+1) else if(s in listOf("text","textNodes","html","outerHtml","href","src")) s else "text"
                val query = if(at >= 0) s.substring(0,at) else if(s == attr) "" else s
                val doc = Jsoup.parse(input,base)
                val elements = if(query.isBlank()) listOf(doc.body().children().singleOrNull() ?: doc.body()) else doc.select(css(query)).toList()
                elements.joinToString("\n") { el -> when(attr) {
                    "text" -> el.text(); "textNodes" -> el.textNodes().joinToString("\n") { it.text() }
                    "html" -> htmlText(el.html()); "outerHtml" -> el.outerHtml()
                    else -> el.attr(attr)
                } }
            }
        }
        if(pieces.size > 1 && pieces[1].isNotEmpty()) {
            require(pieces[1].length <= 256 && result.length <= 200000) { "替换规则超过限制" }
            result = com.google.re2j.Pattern.compile(pieces[1]).matcher(result).replaceAll(pieces.getOrElse(2) { "" })
        }
        return result.trim()
    }
    fun htmlText(html: String): String {
        val doc = Jsoup.parse(html.replace(Regex("(?i)<br\\s*/?>"),"\n").replace(Regex("(?i)</(p|div|h[1-6]|li)>"),"\n</$1>"))
        doc.select("script,style,noscript").remove()
        return doc.wholeText().lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n\n")
    }
    private fun xpath(input: String, rule: String): List<String> {
        val document = W3CDom().namespaceAware(false).fromJsoup(Jsoup.parse(input))
        val nodes = XPathFactory.newInstance().newXPath().evaluate(rule,document,XPathConstants.NODESET) as org.w3c.dom.NodeList
        return (0 until nodes.length).map { i ->
            val n = nodes.item(i)
            if(n.nodeType == org.w3c.dom.Node.ATTRIBUTE_NODE || n.nodeType == org.w3c.dom.Node.TEXT_NODE) n.nodeValue
            else {
                val out = java.io.StringWriter()
                javax.xml.transform.TransformerFactory.newInstance().newTransformer().transform(javax.xml.transform.dom.DOMSource(n),javax.xml.transform.stream.StreamResult(out))
                out.toString()
            }
        }
    }
}
