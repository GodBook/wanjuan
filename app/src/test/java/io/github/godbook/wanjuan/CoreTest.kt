package io.github.godbook.wanjuan

import io.github.godbook.wanjuan.data.*
import io.github.godbook.wanjuan.source.Rules
import io.github.godbook.wanjuan.reader.ReadingView
import org.junit.Assert.*
import org.junit.Test

class CoreTest {
    @Test fun textChaptersPreservePrefaceAndLastChapter() {
        val input="序言内容\n第一章 初见\n这是第一章。\n第二章 重逢\n这是最后一章。"
        val parts=BookImporter.splitText(input)
        assertEquals(3,parts.size)
        assertEquals("卷首",parts.first().first)
        assertEquals("这是最后一章。",parts.last().second)
    }
    @Test fun plainTextIsChunkedWithoutLosingCharacters() {
        val input="长篇正文".repeat(8000)
        val parts=BookImporter.splitText(input)
        assertTrue(parts.size>1)
        assertEquals(input,parts.joinToString("") {it.second})
    }
    @Test fun rulesExtractRelativeLinksAndParagraphs() {
        val html="<div class='book'><a href='/book/1'>测试书籍</a><span>作者甲</span></div>"
        val nodes=Rules.nodes(html,"class.book")
        assertEquals(1,nodes.size)
        assertEquals("测试书籍",Rules.value(nodes[0],"a@text"))
        assertEquals("/book/1",Rules.value(nodes[0],"a@href"))
        assertEquals("第一段\n\n第二段",Rules.value("<main><p>第一段</p><p>第二段</p></main>","main@html"))
    }
    @Test fun jsonRulesAndFallbacks() {
        val json="""{"books":[{"name":"万卷","url":"/b"}]}"""
        val nodes=Rules.nodes(json,"$.books[*]")
        assertEquals("万卷",Rules.value(nodes.single(),"$.name"))
        assertEquals("后备",Rules.value("<span>后备</span>","h1@text||span@text"))
    }
    @Test fun metadataRoundTripPreservesPositionAndSettings() {
        val book=Book(title="书名",chapters=listOf(Chapter(title="第一章",file="books/a.txt")),offset=42,anchor="原位置",profile=Profile(size=28f).json().toString())
        assertEquals(book,Book.decode(book.encode()))
    }
    @Test fun anchorsSurviveTextInsertedBeforePosition() {
        val original="甲乙丙丁戊己庚辛壬癸"
        assertEquals(7,ReadingView.locate("插入文本"+original,3,"丁戊己"))
        assertEquals(original.length,ReadingView.locate(original,9999,"不存在"))
    }
    @Test fun settingsAreClampedOnUntrustedImport() {
        val p=Profile.from("""{"size":500,"line":-1,"mode":40}""")
        assertEquals(40f,p.size)
        assertEquals(1f,p.line)
        assertEquals(3,p.mode)
    }
    @Test fun sha256MatchesKnownVector() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",sha256("abc".toByteArray()))
    }
    @Test fun perBookOverridesInheritOtherGlobalFields() {
        val p=Profile.resolve(Profile(size=20f,line=2.2f,theme=3),"""{"size":25}""")
        assertEquals(25f,p.size);assertEquals(2.2f,p.line);assertEquals(3,p.theme)
    }
    @Test(timeout=1500) fun regexReplacementUsesBoundedEngine() {
        val input="<p>"+"a".repeat(10000)+"!</p>"
        assertTrue(Rules.value(input,"p@text##^(a+)+$##x").endsWith("!"))
    }
}
