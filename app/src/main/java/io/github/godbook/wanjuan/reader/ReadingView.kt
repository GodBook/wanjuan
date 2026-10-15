package io.github.godbook.wanjuan.reader

import android.content.Context
import android.graphics.*
import android.os.Bundle
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
import io.github.godbook.wanjuan.data.Profile
import kotlinx.coroutines.*
import java.io.File
import kotlin.math.abs

class ReadingView(context: Context): View(context) {
    var onMenu: ()->Unit = {}
    var onBoundary: (Boolean)->Unit = {}
    var onPosition: (Int,String,Int,Int)->Unit = { _,_,_,_ -> }
    var onLongPress: ()->Unit = {}
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var job: Job?=null
    private var text=""
    private var displayText=""
    private var profile=Profile()
    private var layout: StaticLayout?=null
    private var pages=listOf(0)
    private var page=0
    private var scrollYpx=0f
    private var desired=0
    private var anchor=""
    private var margin=0f
    private var top=0f
    private var viewport=0f
    private var backgroundImage: Bitmap?=null
    private var visualKey=""
    private var contentToken=-1
    private var oldPage: Int?=null
    private val imagePaint=Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha=45 }
    private val imageRect=Rect()
    private val scroller=android.widget.OverScroller(context)
    private var lastReportedOffset=-1
    private var backgroundJob: Job?=null
    @Volatile private var fontCache="" to Typeface.create("sans-serif",Typeface.NORMAL)
    private var turnAnimator: android.animation.ValueAnimator?=null
    private var turnProgress=1f
    private var turnDirection=1
    private val paint=TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val detector=GestureDetector(context,object: GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: android.view.MotionEvent): Boolean { scroller.forceFinished(true);return true }
        override fun onSingleTapUp(e: android.view.MotionEvent): Boolean {
            when { e.x < width*0.3 -> turn(profile.leftHand); e.x > width*0.7 -> turn(!profile.leftHand); else -> { performClick(); onMenu() } }
            return true
        }
        override fun onLongPress(e: android.view.MotionEvent) { onLongPress() }
        override fun onScroll(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if(profile.mode==3) { scrollYpx=(scrollYpx+distanceY).coerceIn(0f,((layout?.height ?: 0)-viewport).coerceAtLeast(0f)); invalidate(); position() }
            return true
        }
        override fun onFling(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if(profile.mode!=3 && abs(velocityX)>150) turn(velocityX<0)
            else if(profile.mode==3 && abs(velocityY)>1200) {
                if(velocityY<0 && scrollYpx >= ((layout?.height ?: 0)-viewport).coerceAtLeast(0f)) onBoundary(true)
                else if(velocityY>0 && scrollYpx<=0) onBoundary(false)
                else { scroller.fling(0,scrollYpx.toInt(),0,-velocityY.toInt(),0,0,0,((layout?.height ?: 0)-viewport).coerceAtLeast(0f).toInt());postInvalidateOnAnimation() }
            }
            return true
        }
    })
    init { isFocusable=true; importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES }
    fun setContent(body: String, p: Profile, offset: Int, quote: String, token: Int=0) {
        if(text==body && profile==p && contentToken==token) return
        val needsLayout=text!=body || contentToken!=token || profile.size!=p.size || profile.line!=p.line || profile.margin!=p.margin || profile.paragraph!=p.paragraph || profile.letter!=p.letter || profile.font!=p.font || profile.mode!=p.mode
        turnAnimator?.cancel();oldPage=null;scroller.forceFinished(true)
        contentToken=token
        desired=offset; anchor=quote; text=body; profile=p
        val key=p.background
        if(key!=visualKey) { backgroundJob?.cancel(); backgroundImage=null; visualKey=key
            if(key.isNotBlank()) backgroundJob=scope.launch {
                val image=withContext(Dispatchers.IO) {
                    val options=BitmapFactory.Options().apply { inJustDecodeBounds=true };BitmapFactory.decodeFile(key,options)
                    var sample=1
                    while(options.outWidth/sample>1440 || options.outHeight/sample>2560) sample*=2
                    runCatching { BitmapFactory.decodeFile(key,BitmapFactory.Options().apply { inSampleSize=sample }) }.getOrNull()
                }
                ensureActive();backgroundImage=image;invalidate()
            }
        }
        if(needsLayout) render() else { layout?.paint?.color=foreground(p);invalidate() }
    }
    override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) { super.onSizeChanged(w,h,oldw,oldh); render() }
    private fun render() {
        if(width<=0 || height<=0 || text.isBlank()) return
        job?.cancel();turnAnimator?.cancel();oldPage=null
        margin=profile.margin*resources.displayMetrics.density
        top=12*resources.displayMetrics.density
        viewport=(height-top*2).coerceAtLeast(50f)
        paint.textSize=profile.size*resources.displayMetrics.scaledDensity
        paint.letterSpacing=profile.letter
        paint.color=foreground(profile)
        val content=text
        val width=(width-margin*2).toInt().coerceAtLeast(30)
        val style=TextPaint(paint)
        val spacing=profile.line
        val paragraphGap=(profile.paragraph*resources.displayMetrics.density).toInt()
        val font=profile.font
        val availableHeight=viewport
        job=scope.launch {
            val built=withContext(Dispatchers.Default) {
                val cachedFont=fontCache
                style.typeface=if(font==cachedFont.first) cachedFont.second else {
                    val resolved=if(font.isNotBlank()) runCatching { Typeface.createFromFile(font) }.getOrDefault(Typeface.DEFAULT) else Typeface.create("sans-serif",Typeface.NORMAL)
                    fontCache=font to resolved;resolved
                }
                val styled=android.text.SpannableStringBuilder(content)
                Regex("\\[\\[wanjuan-image:([^\\]]+)\\]\\]").findAll(content).toList().asReversed().forEach {match ->
                    val root=File(context.filesDir,"library").canonicalFile
                    val file=File(root,match.groupValues[1]).canonicalFile
                    if(file.path.startsWith(root.path+File.separator) && file.exists()) {
                        val options=BitmapFactory.Options().apply {inJustDecodeBounds=true}
                        BitmapFactory.decodeFile(file.path,options)
                        if(options.outWidth>0) {
                            val sample=(options.outWidth/width).coerceAtLeast(1)
                            val bitmap=BitmapFactory.decodeFile(file.path,BitmapFactory.Options().apply {inSampleSize=sample})
                            if(bitmap!=null) {
                                val drawable=android.graphics.drawable.BitmapDrawable(resources,bitmap)
                                val scale=minOf(width.toFloat()/bitmap.width,availableHeight*.75f/bitmap.height,1f)
                                drawable.setBounds(0,0,(bitmap.width*scale).toInt(),(bitmap.height*scale).toInt())
                                styled.replace(match.range.first,match.range.last+1,"\uFFFC")
                                styled.setSpan(android.text.style.ImageSpan(drawable),match.range.first,match.range.first+1,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            }
                        }
                    }
                }
                var start=0
                styled.forEachIndexed { i,c -> if(c=='\n') {
                    val paragraphEnd=i+1
                    styled.setSpan(android.text.style.LineHeightSpan { _,_,end,_,_,fm -> if(end>=paragraphEnd) { fm.descent+=paragraphGap;fm.bottom+=paragraphGap } },start,paragraphEnd,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    start=paragraphEnd
                } }
                StaticLayout.Builder.obtain(styled,0,styled.length,style,width).setIncludePad(false).setLineSpacing(0f,spacing)
                    .setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_HIGH_QUALITY).setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE).build()
            }
            ensureActive()
            layout=built
            built.paint.color=foreground(profile)
            lastReportedOffset=-1
            displayText=built.text.toString()
            val starts=mutableListOf(0)
            var first=0
            for(line in 1 until built.lineCount) if(built.getLineBottom(line)-built.getLineTop(first)>viewport) { starts+=line; first=line }
            pages=starts
            val target=locate(displayText,desired,anchor)
            val targetLine=built.getLineForOffset(target.coerceIn(0,displayText.length))
            page=pages.indexOfLast { it<=targetLine }.coerceAtLeast(0)
            scrollYpx=built.getLineTop(targetLine).toFloat().coerceIn(0f,(built.height-viewport).coerceAtLeast(0f))
            invalidate(); position()
        }
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val old=oldPage
        drawPage(canvas,page,if(old!=null && profile.mode==1) turnDirection*width*(1f-turnProgress) else 0f)
        if(old!=null) drawPage(canvas,old,-turnDirection*width*turnProgress)
    }
    private fun drawPage(canvas: Canvas,index: Int,translation: Float) {
        val saved=canvas.save()
        canvas.translate(translation,0f)
        canvas.clipRect(0,0,width,height)
        canvas.drawColor(background(profile))
        imageRect.set(0,0,width,height)
        backgroundImage?.let { canvas.drawBitmap(it,null,imageRect,imagePaint) }
        val built=layout
        if(built==null) { canvas.restoreToCount(saved);return }
        val y=if(profile.mode==3) scrollYpx else built.getLineTop(pages[index]).toFloat()
        val lastLine=(pages.getOrNull(index+1) ?: built.lineCount)-1
        val bottom=if(profile.mode==3) height-top else (top+built.getLineBottom(lastLine)-y).coerceAtMost(height-top)
        canvas.clipRect(margin,top,width-margin,bottom)
        canvas.translate(margin,top-y)
        built.draw(canvas)
        canvas.restoreToCount(saved)
    }
    fun turn(forward: Boolean) {
        val built=layout ?: return
        if(profile.mode==3) {
            val max=(built.height-viewport).coerceAtLeast(0f)
            if((forward && scrollYpx>=max) || (!forward && scrollYpx<=0)) onBoundary(forward)
            else { scrollYpx=(scrollYpx+(if(forward) viewport else -viewport)).coerceIn(0f,max); invalidate(); position() }
            return
        }
        val next=page+if(forward) 1 else -1
        if(next !in pages.indices) onBoundary(forward) else {
            turnAnimator?.cancel();oldPage=null
            val animated=profile.mode in 0..1 && android.animation.ValueAnimator.areAnimatorsEnabled()
            if(animated) {
                oldPage=page;turnProgress=0f;turnDirection=if(forward) 1 else -1
            }
            page=next; invalidate(); position()
            if(animated) {
                turnAnimator=android.animation.ValueAnimator.ofFloat(0f,1f).apply {
                    duration=180;addUpdateListener {turnProgress=it.animatedValue as Float;invalidate()}
                    addListener(object: android.animation.AnimatorListenerAdapter() {override fun onAnimationEnd(animation: android.animation.Animator) {oldPage=null;invalidate()}})
                    start()
                }
            }
        }
    }
    private fun position() {
        val l=layout ?: return
        val line=if(profile.mode==3) l.getLineForVertical(scrollYpx.toInt()) else pages[page]
        val offset=l.getLineStart(line)
        if(offset==lastReportedOffset) return
        lastReportedOffset=offset
        if(profile.mode==3) page=pages.indexOfLast {it<=line}.coerceAtLeast(0)
        desired=offset; anchor=displayText.substring(offset,(offset+36).coerceAtMost(displayText.length))
        onPosition(offset,anchor,page+1,pages.size)
        contentDescription=displayText.substring(offset,(if(profile.mode==3) offset+1500 else pages.getOrNull(page+1)?.let { l.getLineStart(it) } ?: displayText.length).coerceAtMost(displayText.length)).replace("\uFFFC","【插图】")
    }
    override fun onTouchEvent(event: android.view.MotionEvent)=detector.onTouchEvent(event)
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info); info.className="android.widget.TextView"; info.isScrollable=true
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD); info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
    }
    override fun performAccessibilityAction(action: Int,arguments: Bundle?): Boolean = when(action) {
        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> { turn(true); true }
        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> { turn(false); true }
        else -> super.performAccessibilityAction(action,arguments)
    }
    override fun computeScroll() {
        if(scroller.computeScrollOffset()) { scrollYpx=scroller.currY.toFloat();position();postInvalidateOnAnimation() }
    }
    override fun onDetachedFromWindow() { job?.cancel();backgroundJob?.cancel();turnAnimator?.cancel();scroller.forceFinished(true);oldPage=null;backgroundImage=null;visualKey="";super.onDetachedFromWindow() }
    companion object {
        fun locate(text: String, offset: Int, anchor: String): Int {
            if(offset==Int.MAX_VALUE) return text.length
            if(anchor.isNotBlank()) {
                if(offset>=0 && offset+anchor.length<=text.length && text.regionMatches(offset,anchor,0,anchor.length)) return offset
                val matches=mutableListOf<Int>(); var at=text.indexOf(anchor)
                while(at>=0 && matches.size<100) { matches+=at; at=text.indexOf(anchor,at+1) }
                if(matches.isNotEmpty()) return matches.minBy { abs(it-offset) }
            }
            return offset.coerceIn(0,text.length)
        }
        fun background(p: Profile): Int = if(p.backgroundColor!=0L) p.backgroundColor.toInt() else when(p.theme) { 1 -> 0xffeee6d4.toInt(); 2 -> 0xffcddcce.toInt(); 3 -> 0xff171b19.toInt(); else -> 0xfffaf9f5.toInt() }
        fun foreground(p: Profile): Int = if(p.textColor!=0L) p.textColor.toInt() else if(p.theme==3) 0xffc8c9c2.toInt() else 0xff262c28.toInt()
    }
}
