package io.github.godbook.wanjuan

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import io.github.godbook.wanjuan.ui.WanJuan

class MainActivity: ComponentActivity() {
    val model: AppModel by viewModels()
    var volumeTurn: ((Boolean)->Unit)?=null
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); enableEdgeToEdge(); setContent { WanJuan(model,this) } }
    override fun onPause() { model.flush(); super.onPause() }
    override fun onKeyDown(keyCode: Int,event: KeyEvent?): Boolean {
        if(volumeTurn!=null && keyCode in listOf(KeyEvent.KEYCODE_VOLUME_DOWN,KeyEvent.KEYCODE_VOLUME_UP)) { volumeTurn?.invoke(keyCode==KeyEvent.KEYCODE_VOLUME_DOWN); return true }
        return super.onKeyDown(keyCode,event)
    }
}
