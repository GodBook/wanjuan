package io.github.godbook.wanjuan.source

import android.app.Service
import android.content.*
import android.os.*
import kotlinx.coroutines.*
import org.mozilla.javascript.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ScriptService: Service() {
    private val thread = HandlerThread("bounded-script").apply { start() }
    private val messenger = Messenger(object: Handler(thread.looper) {
        override fun handleMessage(msg: Message) {
            val reply = Message.obtain(null,1)
            reply.data = Bundle()
            try {
                val code = msg.data.getString("code") ?: ""
                val input = msg.data.getString("input") ?: ""
                require(code.length <= 32768 && input.length <= 65536) { "脚本输入超过限制" }
                val deadline = System.nanoTime() + 1_000_000_000L
                val factory = object: ContextFactory() {
                    override fun makeContext(): org.mozilla.javascript.Context = super.makeContext().apply { optimizationLevel = -1; instructionObserverThreshold = 1000; maximumInterpreterStackDepth = 128 }
                    override fun observeInstructionCount(cx: org.mozilla.javascript.Context, instructionCount: Int) { if (System.nanoTime() > deadline) throw EvaluatorException("脚本执行超时") }
                }
                val result = factory.call(ContextAction<String> { cx ->
                    cx.setClassShutter { false }
                    val scope = cx.initSafeStandardObjects()
                    ScriptableObject.putProperty(scope,"result",input)
                    val value = cx.evaluateString(scope,code,"source",1,null)
                    org.mozilla.javascript.Context.toString(value)
                })
                require(result.length <= 65536) { "脚本输出超过限制" }
                reply.data.putString("result",result)
            } catch (e: Throwable) { reply.data.putString("error",e.message ?: "脚本执行失败") }
            try { msg.replyTo.send(reply) } catch (_: Exception) { }
        }
    })
    override fun onBind(intent: Intent?) = messenger.binder
    override fun onDestroy() { thread.quitSafely(); super.onDestroy(); android.os.Process.killProcess(android.os.Process.myPid()) }
}

class Scripts(private val context: android.content.Context) {
    suspend fun execute(code: String, input: String): String = withContext(Dispatchers.Main) {
        var connection: ServiceConnection? = null
        var bound = false
        try {
            withTimeout(2500) {
                suspendCancellableCoroutine { cont ->
                    val callback = Messenger(object: Handler(Looper.getMainLooper()) {
                        override fun handleMessage(msg: Message) {
                            if (!cont.isActive) return
                            val error = msg.data.getString("error")
                            if (error != null) cont.resumeWithException(IllegalArgumentException(error)) else cont.resume(msg.data.getString("result") ?: "")
                        }
                    })
                    connection = object: ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                            try { Messenger(service).send(Message.obtain(null,1).apply { replyTo = callback; data = Bundle().apply { putString("code",code); putString("input",input) } }) }
                            catch (e: Exception) { if(cont.isActive) cont.resumeWithException(e) }
                        }
                        override fun onServiceDisconnected(name: ComponentName?) { if(cont.isActive) cont.resumeWithException(IllegalStateException("脚本进程已结束")) }
                    }
                    bound = context.bindService(Intent(context, ScriptService::class.java), connection!!, android.content.Context.BIND_AUTO_CREATE)
                    if (!bound) cont.resumeWithException(IllegalStateException("无法启动脚本引擎"))
                }
            }
        } finally { if(bound) connection?.let { context.unbindService(it) } }
    }
}
