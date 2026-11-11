package com.elith.precision

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import java.util.Locale
import kotlin.math.min

/** A single vector-drawn card; digit cells stay fixed even with proportional typefaces. */
class ClockView(context: Context): View(context), Choreographer.FrameCallback {
    var config=ClockConfig()
        set(value) { field=value; invalidate(); onContentHeightChanged?.invoke() }
    var frame=CountdownFrame.of(0)
        set(value) {
            val changed=field.timeline!=value.timeline
            field=value
            if(changed) { timelineEntered=SystemClock.uptimeMillis(); onContentHeightChanged?.invoke() }
            invalidate()
        }
    var frameProvider:(()->CountdownFrame)?=null
    var onContentHeightChanged:(()->Unit)?=null
    private val metrics=LiveMetrics(context)
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    private val numberFont=Typeface.create("sans-serif-light",Typeface.NORMAL)
    private val labelFont=Typeface.create("sans-serif-medium",Typeface.NORMAL)
    private var running=false
    private var lastFrameNs=0L
    private var lastDrawNs=0L
    private var intervalMs: Double?=null
    private var timelineEntered=0L
    var completionFlashUntil=0L
    init { importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES; contentDescription="Precision countdown" }
    private fun hasStats()=config.showNetwork || config.showRefresh
    private fun baseHeight()=if(config.compact) 84 else 114
    fun desiredHeight(widthPx: Int): Int = ((baseHeight()+(if(frame.timeline) 54 else 0)+(if(hasStats()) 22 else 0))*widthPx/400f).toInt().coerceAtLeast(1)
    fun start() { if(!running) { running=true; lastFrameNs=0; lastDrawNs=0; Choreographer.getInstance().postFrameCallback(this) } }
    fun stop() { running=false; Choreographer.getInstance().removeFrameCallback(this); lastFrameNs=0; lastDrawNs=0; intervalMs=null }
    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }
    override fun doFrame(frameTimeNanos: Long) {
        if(!running) return
        val cap=if(config.powerSaving && frame.remainingMs>10000) min(if(config.fpsCap==0) 60 else config.fpsCap,30) else config.fpsCap
        val due=lastFrameNs==0L || cap==0 || frameTimeNanos-lastFrameNs>=1000000000L/cap-1000000L
        if(due) {
            lastFrameNs=frameTimeNanos
            frameProvider?.invoke()?.let { frame=it }
            metrics.sample(config.showNetwork)
            invalidate()
        }
        Choreographer.getInstance().postFrameCallback(this)
    }
    override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
        val w=MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w,resolveSize(desiredHeight(w),heightMeasureSpec))
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if(running) {
            val now=SystemClock.elapsedRealtimeNanos()
            if(lastDrawNs>0) {
                val delta=(now-lastDrawNs)/1000000.0
                intervalMs=intervalMs?.let { it*0.85+delta*0.15 }?:delta
            }
            lastDrawNs=now
        }
        val s=width/400f
        if(s<=0) return
        canvas.save(); canvas.scale(s,s)
        p.color=config.background; p.style=Paint.Style.FILL
        val h=height/s
        canvas.drawRoundRect(RectF(0f,0f,400f,h),config.radius,config.radius,p)
        p.typeface=labelFont; p.textSize=12f; p.color=Color.rgb(208,213,224)
        if(!config.compact) {
            val ms=intervalMs?.let { "${it.toInt()}ms" }?:"—ms"
            if(config.showFrameTime) canvas.drawText(ms,23f,27f,p)
            val source="Device Time"
            canvas.drawText(source,377f-p.measureText(source),27f,p)
        }
        val text=formatCountdown(frame.remainingMs,config.fractionDigits)
        drawDigits(canvas,text,if(config.compact) 59f else 90f)
        if(frame.timeline) {
            val top=baseHeight().toFloat()
            val entrance=if(running) ((SystemClock.uptimeMillis()-timelineEntered)/120f).coerceIn(0f,1f) else 1f
            canvas.saveLayerAlpha(0f,top-8f,400f,top+54f,(255*entrance).toInt())
            canvas.translate(0f,(1f-entrance)*3f)
            p.strokeWidth=1.6f; p.color=config.accent; p.alpha=90
            val x0=38f; val x1=362f; val y=top+29f
            canvas.drawLine(x0,y,x1,y,p)
            p.alpha=255
            canvas.drawLine(x0,y,x0+(x1-x0)*frame.progress,y,p)
            val labels=arrayOf("2.5","2.0","1.5","1.0","0.5")
            for(i in 0..4) {
                val x=x0+i*(x1-x0)/4f
                p.textSize=12f; p.typeface=labelFont; p.color=Color.WHITE
                canvas.drawText(labels[i],x-p.measureText(labels[i])/2,top+12,p)
                p.color=Color.rgb(245,245,245); canvas.drawCircle(x,y,4.8f,p)
                p.color=config.accent; canvas.drawCircle(x,y,3.3f,p)
                if(i<frame.activeCount) {
                    p.color=config.accent; p.style=Paint.Style.STROKE; p.strokeWidth=1.4f
                    canvas.drawCircle(x,y,7f,p); p.style=Paint.Style.FILL
                }
            }
            canvas.restore()
        }
        if(hasStats()) {
            val y=h-13f
            p.textSize=11f; p.typeface=labelFont; p.color=Color.rgb(208,213,224)
            if(config.showRefresh) {
                val hz=metrics.refreshHz?.let { String.format(Locale.US,"%.0f Hz",it) }?:"Hz unavailable"
                val cap=if(config.fpsCap==0) "Auto" else "${config.fpsCap} FPS cap"
                canvas.drawText("$hz · $cap",23f,y,p)
            }
            if(config.showNetwork) {
                val rate=metrics.network
                val net=if(rate==null) "↑ —   ↓ —" else "↑ ${formatRate(rate.tx)}   ↓ ${formatRate(rate.rx)}"
                p.textSize=if(config.showRefresh) 10f else 11f
                canvas.drawText(net,377f-p.measureText(net),y,p)
            }
        }
        if(SystemClock.uptimeMillis()<completionFlashUntil) {
            p.color=config.accent; p.alpha=40
            canvas.drawRoundRect(RectF(0f,0f,400f,h),config.radius,config.radius,p); p.alpha=255
        }
        canvas.restore()
    }
    private fun drawDigits(canvas:Canvas,text:String,baseline:Float) {
        p.typeface=numberFont; p.textSize=66f*config.fontScale
        fun charWidth(c:Char)=if(c.isDigit()) p.measureText("0") else p.measureText(c.toString())
        var total=text.sumOf { charWidth(it).toDouble() }.toFloat()
        if(total>360) { p.textSize*=360/total; total=text.sumOf { charWidth(it).toDouble() }.toFloat() }
        var x=(400-total)/2
        var fractional=false
        for(c in text) {
            p.color=if(fractional) config.accent else Color.WHITE
            val cell=charWidth(c); val glyph=c.toString()
            canvas.drawText(glyph,x+(cell-p.measureText(glyph))/2,baseline,p)
            x+=cell
            if(c=='.') fractional=true
        }
    }
}
