package com.elith.precision

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil

/** Calendar-based recurrence: 24 hours is not always one local day. */
object TargetSchedule {
    fun next(nowMs: Long, zone: ZoneId, time: LocalTime): Long {
        var date=Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        repeat(370) {
            val candidate=date.atTime(time).atZone(zone).withEarlierOffsetAtOverlap().toInstant().toEpochMilli()
            if(candidate>nowMs) return candidate
            date=date.plusDays(1)
        }
        error("Could not resolve future occurrence")
    }
}

data class CountdownFrame(val remainingMs: Long, val timeline: Boolean, val activeCount: Int, val progress: Float) {
    companion object {
        val thresholds=longArrayOf(2500,2000,1500,1000,500)
        fun of(remaining: Long): CountdownFrame {
            val r=remaining.coerceAtLeast(0)
            return CountdownFrame(r,r in 1..3000,if(r>0) thresholds.count { r<=it } else 0,((3000-r)/3000f).coerceIn(0f,1f))
        }
    }
}

/** Holds zero for a visible beat and then resolves daily time from the current calendar. */
class CountdownSession(var targetMs: Long, private val daily: Boolean, private val time: LocalTime, completed: Boolean=false) {
    var completed=completed; private set
    private var completedAtMono: Long?=null
    fun frame(nowMs: Long, monoMs: Long, zone: ZoneId): CountdownFrame {
        if(completed) {
            if(!daily) return CountdownFrame.of(0)
            val at=completedAtMono
            if(at==null) completedAtMono=monoMs
            else if(monoMs-at>=1200) {
                targetMs=TargetSchedule.next(nowMs,zone,time)
                completed=false; completedAtMono=null
            }
            if(completed) return CountdownFrame.of(0)
        }
        if(nowMs>=targetMs) { completed=true; completedAtMono=monoMs; return CountdownFrame.of(0) }
        return CountdownFrame.of(targetMs-nowMs)
    }
    fun reconcile(nowMs: Long, zone: ZoneId) {
        if(daily) { targetMs=TargetSchedule.next(nowMs,zone,time); completed=false; completedAtMono=null }
    }
}

/** Interpolates sub-frame elapsed time, comparing to the authoritative wall clock each read.
 * A wall-clock step >2 ms reanchors immediately; there is no repeated interval subtraction. */
class DisciplinedClock {
    private var wall=0L; private var mono=Long.MIN_VALUE
    fun now(wallMs: Long, monoNanos: Long): Long {
        if(mono==Long.MIN_VALUE) { wall=wallMs; mono=monoNanos }
        val estimate=wall+(monoNanos-mono)/1000000
        if(abs(estimate-wallMs)>2 || monoNanos-mono>=250000000) { wall=wallMs; mono=monoNanos; return wallMs }
        return estimate.coerceAtMost(wallMs)
    }
}

fun formatCountdown(remainingMs: Long, digits: Int): String {
    val d=digits.coerceIn(1,3)
    val step=when(d) { 1->100L; 2->10L; else->1L }
    val safe=remainingMs.coerceAtLeast(0)
    val rounded=if(safe==0L) 0 else ((safe-1)/step+1)*step
    val seconds=rounded/1000
    return String.format(Locale.US,"%02d:%02d:%02d.%0${d}d",seconds/3600,(seconds/60)%60,seconds%60,(rounded%1000)/step)
}

data class TrafficRate(val rx: Double,val tx: Double)
class TrafficMeter {
    private var previous: Triple<Long,Long,Long>?=null
    fun sample(rx: Long, tx: Long, nowMs: Long): TrafficRate? {
        val old=previous
        if(rx<0 || tx<0) { previous=null; return null }
        previous=Triple(rx,tx,nowMs)
        if(old==null || rx<old.first || tx<old.second || nowMs<=old.third) return null
        val seconds=(nowMs-old.third)/1000.0
        return TrafficRate((rx-old.first)/seconds,(tx-old.second)/seconds)
    }
}
fun formatRate(bytes: Double): String = when {
    bytes>=1048576 -> String.format(Locale.US,"%.1f MB/s",bytes/1048576).replace(".0 "," ")
    bytes>=1024 -> String.format(Locale.US,"%.1f KB/s",bytes/1024).replace(".0 "," ")
    else -> "${bytes.toLong().coerceAtLeast(0)} B/s"
}
object OverlayPolicy {
    fun alpha(requested: Float, touchThrough: Boolean, maximum: Float): Float =
        if(touchThrough) requested.coerceIn(0.2f,maximum.coerceAtLeast(0.2f)) else requested.coerceIn(0.2f,1f)
    fun canDrag(locked: Boolean,touchThrough: Boolean)=!locked && !touchThrough
}
