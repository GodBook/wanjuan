package io.github.godbook.wanjuan

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.godbook.wanjuan.source.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BuiltinSourcesTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val app=instrumentation.targetContext.applicationContext as WanJuanApp

    @Test fun builtinsRoundTripAndRespectUserChoices()=runBlocking<Unit> {
        BuiltinSources.install(app.library,true)
        val source=BuiltinSources.defaults().first()
        try {
            app.library.save(source.copy(enabled=false))
            BuiltinSources.install(app.library)
            assertFalse(app.library.source(source.id)!!.enabled)
            app.library.dao.delete("source",source.id)
            BuiltinSources.install(app.library)
            assertNull(app.library.source(source.id))
            BuiltinSources.install(app.library,true)
            val imported=app.engine.inspect(source.raw).single()
            assertEquals(source.id,imported.source.id)
            assertTrue(imported.warnings.isEmpty())
            assertEquals(1,app.engine.searchOne("红楼梦",source).size)
            assertEquals(1,app.engine.searchOne("紅樓夢",source).size)
        } finally { BuiltinSources.install(app.library,true) }
    }

    /** Explicit opt-in: CI stays deterministic; releases run this on the connected phone. */
    @Test fun liveBuiltinCatalogsAndChapters()=runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveSources")=="true")
        withTimeout(240_000) {
            for(source in BuiltinSources.defaults()) {
                for(edition in BuiltinSources.editions.filter { BuiltinSources.provider(source)!="gutenberg" || it.pg!=null }) {
                    instrumentation.sendStatus(0,android.os.Bundle().apply {putString("stream","\nLIVE CHECK ${source.name} / ${edition.title}\n")})
                    val found=app.engine.searchOne(edition.title,source).single()
                    val book=app.engine.catalog(found,source)
                    assertEquals("${source.name} ${edition.title} 目录",when(edition.title) {"红楼梦","三国演义" -> 120;"西游记" -> 100;else -> 56},book.chapters.size)
                    for(index in listOf(0,book.chapters.size/2,book.chapters.lastIndex)) {
                        val body=app.engine.content(book.chapters[index],source)
                        assertTrue("${source.name} ${edition.title} 第 $index 节正文",body.length>300)
                        assertFalse(body.contains("Wikimedia Error"))
                        assertFalse(body.contains("THE FULL PROJECT GUTENBERG LICENSE"))
                    }
                    instrumentation.sendStatus(0,android.os.Bundle().apply {putString("stream","\nLIVE PASS ${source.name} / ${edition.title}: ${book.chapters.size} chapters; first/middle/last readable\n")})
                }
            }
        }
    }
}
