package com.elith.precision

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import java.time.LocalTime
import java.time.ZoneId

data class ClockConfig(
    val targetMs: Long=TargetSchedule.next(System.currentTimeMillis(),ZoneId.systemDefault(),LocalTime.MIDNIGHT),
    val zoneId: String=ZoneId.systemDefault().id, val targetMode: String="next",
    val daily: Boolean=false, val hour: Int=0, val minute: Int=0, val second: Int=0, val millisecond: Int=0,
    val completed: Boolean=false, val widthDp: Int=360, val opacity: Float=1f,
    val fontScale: Float=1f, val radius: Float=23f, val compact: Boolean=false,
    val accent: Int=Color.rgb(255,81,61), val background: Int=Color.rgb(61,68,86),
    val fractionDigits: Int=1, val showFrameTime: Boolean=true, val showRefresh: Boolean=false,
    val showNetwork: Boolean=false, val fpsCap: Int=60, val powerSaving: Boolean=true,
    val locked: Boolean=false, val touchThrough: Boolean=false, val hidden: Boolean=false,
    val positionX: Float=0.5f, val positionY: Float=0.18f,
    val use24Hour: Boolean=true, val completion: String="silent", val hideOnComplete: Boolean=false
) {
    val localTime: LocalTime get()=LocalTime.of(hour,minute,second,millisecond*1000000)
    val targetKey: String get()="$targetMs/$daily/$hour/$minute/$second/$millisecond/$zoneId"
}

class ClockStore(context: Context) {
    val preferences: SharedPreferences=context.getSharedPreferences("precision",Context.MODE_PRIVATE)
    fun load(): ClockConfig {
        val p=preferences
        val d=ClockConfig()
        return d.copy(
            targetMs=p.getLong("target",d.targetMs),zoneId=p.getString("zone",d.zoneId)?:d.zoneId,targetMode=p.getString("mode","next")?:"next",daily=p.getBoolean("daily",false),
            hour=p.getInt("hour",0).coerceIn(0,23),minute=p.getInt("minute",0).coerceIn(0,59),second=p.getInt("second",0).coerceIn(0,59),millisecond=p.getInt("millisecond",0).coerceIn(0,999),
            completed=p.getBoolean("completed",false),widthDp=p.getInt("width",360).coerceIn(180,700),opacity=p.getFloat("opacity",1f).coerceIn(0.2f,1f),fontScale=p.getFloat("font",1f).coerceIn(0.6f,1.15f),radius=p.getFloat("radius",23f).coerceIn(0f,40f),compact=p.getBoolean("compact",false),
            accent=p.getInt("accent",d.accent),background=p.getInt("background",d.background),fractionDigits=p.getInt("digits",1).coerceIn(1,3),showFrameTime=p.getBoolean("frame",true),showRefresh=p.getBoolean("refresh",false),showNetwork=p.getBoolean("network",false),fpsCap=p.getInt("fps",60).let { if(it in listOf(0,30,60,90,120)) it else 60 },powerSaving=p.getBoolean("power",true),
            locked=p.getBoolean("locked",false),touchThrough=p.getBoolean("through",false),hidden=p.getBoolean("hidden",false),positionX=p.getFloat("x",0.5f).coerceIn(0f,1f),positionY=p.getFloat("y",0.18f).coerceIn(0f,1f),use24Hour=p.getBoolean("24",true),completion=p.getString("completion","silent")?:"silent",hideOnComplete=p.getBoolean("hideDone",false))
    }
    fun save(c: ClockConfig) {
        preferences.edit().putString("zone",c.zoneId).putString("mode",c.targetMode).putLong("target",c.targetMs).putBoolean("daily",c.daily).putInt("hour",c.hour).putInt("minute",c.minute).putInt("second",c.second).putInt("millisecond",c.millisecond).putBoolean("completed",c.completed)
            .putInt("width",c.widthDp).putFloat("opacity",c.opacity).putFloat("font",c.fontScale).putFloat("radius",c.radius).putBoolean("compact",c.compact).putInt("accent",c.accent).putInt("background",c.background)
            .putInt("digits",c.fractionDigits).putBoolean("frame",c.showFrameTime).putBoolean("refresh",c.showRefresh).putBoolean("network",c.showNetwork).putInt("fps",c.fpsCap).putBoolean("power",c.powerSaving)
            .putBoolean("locked",c.locked).putBoolean("through",c.touchThrough).putBoolean("hidden",c.hidden).putFloat("x",c.positionX).putFloat("y",c.positionY).putBoolean("24",c.use24Hour).putString("completion",c.completion).putBoolean("hideDone",c.hideOnComplete).apply()
    }
    fun update(block:(ClockConfig)->ClockConfig) = save(block(load()))
    fun reconcileZone() {
        val c=load(); val zone=ZoneId.systemDefault()
        if(c.daily && c.zoneId!=zone.id) save(c.copy(zoneId=zone.id,targetMs=TargetSchedule.next(System.currentTimeMillis(),zone,c.localTime),completed=false))
    }
    fun reset() { preferences.edit().clear().apply(); save(ClockConfig()) }
}
