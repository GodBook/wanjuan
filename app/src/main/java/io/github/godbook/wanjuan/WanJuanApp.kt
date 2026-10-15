package io.github.godbook.wanjuan

import android.app.Application
import io.github.godbook.wanjuan.data.Library
import io.github.godbook.wanjuan.source.SourceEngine

class WanJuanApp: Application() {
    val library by lazy { Library(this) }
    val engine by lazy { SourceEngine(this) }
}
