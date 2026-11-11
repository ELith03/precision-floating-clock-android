package com.elith.precision

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSettings
import java.io.File
import java.time.*
import java.util.TimeZone
import android.view.ViewGroup
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@LooperMode(LooperMode.Mode.PAUSED)
class AndroidBehaviorTest {
    private val context get()=RuntimeEnvironment.getApplication()
    @Before fun reset() {
        ClockStore(context).reset(); ShadowSettings.setCanDrawOverlays(false)
        // Real devices wait for the next vsync. Pause Robolectric's immediate virtual vsync.
        org.robolectric.shadows.ShadowChoreographer.setPaused(true)
    }
    @Test(timeout=30000) fun dailyTargetFollowsZoneChangedWhileStopped() {
        val original=TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kuala_Lumpur"))
            val store=ClockStore(context)
            store.save(store.load().copy(daily=true,hour=12,targetMs=TargetSchedule.next(System.currentTimeMillis(),ZoneId.of("UTC"),LocalTime.NOON)))
            store.preferences.edit().putString("zone","UTC").apply()
            val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
            val local=Instant.ofEpochMilli(store.load().targetMs).atZone(ZoneId.systemDefault())
            assertEquals(12,local.hour)
            controller.pause().stop().destroy()
        } finally { TimeZone.setDefault(original) }
    }
    @Test(timeout=30000) fun editingAnExplicitDatePreservesDateMode() {
        val store=ClockStore(context)
        store.save(store.load().copy(targetMs=System.currentTimeMillis()+10*86400000L))
        store.preferences.edit().putString("mode","date").apply()
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        fun find(v:View):View? {
            if(v is android.widget.Button && v.text=="Choose target time & date")return v
            if(v is ViewGroup) for(i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
            return null
        }
        find(controller.get().findViewById(android.R.id.content))!!.performClick()
        val dialog=ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(dialog.findViewById<android.widget.RadioButton>(101).isChecked)
        dialog.dismiss();controller.pause().stop().destroy()
    }
    @Test(timeout=30000) fun previewCompletionIsPersisted() {
        val store=ClockStore(context)
        store.save(store.load().copy(targetMs=System.currentTimeMillis()-1000,completed=false))
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        assertTrue(store.load().completed)
        controller.pause().stop().destroy()
    }
    @Test(timeout=30000) fun changingTargetCancelsOldCompletionHide() {
        val store=ClockStore(context)
        store.save(store.load().copy(targetMs=System.currentTimeMillis()-1,hideOnComplete=true))
        ShadowSettings.setCanDrawOverlays(true)
        val controller=Robolectric.buildService(OverlayService::class.java).create()
        controller.get().onStartCommand(Intent(context,OverlayService::class.java).setAction(OverlayService.START),0,1)
        store.update { it.copy(targetMs=System.currentTimeMillis()+10000,completed=false,hidden=false) }
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1500))
        assertFalse(store.load().hidden)
        controller.destroy()
    }
    @Test(timeout=30000) @Config(qualifiers="w800dp-h360dp-land")
    fun expandedOverlayFitsLandscapeAtLargestRequestedSize() {
        val store=ClockStore(context)
        store.save(store.load().copy(widthDp=700,showNetwork=true,showRefresh=true))
        ShadowSettings.setCanDrawOverlays(true)
        val controller=Robolectric.buildService(OverlayService::class.java).create()
        controller.get().onStartCommand(Intent(context,OverlayService::class.java).setAction(OverlayService.START),0,1)
        val wm=context.getSystemService(android.view.WindowManager::class.java)
        val overlay=org.robolectric.shadow.api.Shadow.extract<org.robolectric.shadows.ShadowWindowManagerImpl>(wm).views.filterIsInstance<ClockView>().single()
        overlay.frame=CountdownFrame.of(2000)
        val lp=overlay.layoutParams as android.view.WindowManager.LayoutParams
        assertTrue(lp.y+lp.height<=wm.currentWindowMetrics.bounds.bottom)
        controller.destroy()
    }
    @Test(timeout=30000) fun appearanceAndTargetSurviveReopeningStore() {
        val store=ClockStore(context)
        store.save(store.load().copy(widthDp=277,opacity=0.52f,locked=true,touchThrough=true,targetMs=123456789L,fractionDigits=3))
        val saved=ClockStore(context).load()
        assertEquals(277,saved.widthDp); assertEquals(0.52f,saved.opacity,0f)
        assertTrue(saved.locked); assertTrue(saved.touchThrough)
        assertEquals(123456789L,saved.targetMs); assertEquals(3,saved.fractionDigits)
    }
    @Test(timeout=30000) fun previewCollapsesTimelineOutsideWindowAndAdaptsToSize() {
        val view=ClockView(context)
        view.config=ClockConfig()
        view.frame=CountdownFrame.of(3001)
        val collapsed=view.desiredHeight(360)
        view.frame=CountdownFrame.of(3000)
        assertTrue(view.desiredHeight(360)>collapsed)
        view.frame=CountdownFrame.of(0)
        assertEquals(collapsed,view.desiredHeight(360))
        view.config=view.config.copy(compact=true)
        assertTrue(view.desiredHeight(360)<collapsed)
    }
    @Test(timeout=30000) fun overlayPermissionFailureDoesNotCreateWindow() {
        val controller=Robolectric.buildService(OverlayService::class.java).create()
        val service=controller.get()
        service.onStartCommand(Intent(context,OverlayService::class.java).setAction(OverlayService.START),0,1)
        assertFalse(OverlayService.running)
        controller.destroy()
    }
    @Test(timeout=30000) fun overlayStartsCanHideAndHasRecoveryNotification() {
        ShadowSettings.setCanDrawOverlays(true)
        val controller=Robolectric.buildService(OverlayService::class.java).create()
        val service=controller.get()
        service.onStartCommand(Intent(context,OverlayService::class.java).setAction(OverlayService.START),0,1)
        assertTrue(OverlayService.running)
        val nm=context.getSystemService(NotificationManager::class.java)
        assertTrue(shadowOf(nm).allNotifications.isNotEmpty())
        service.onStartCommand(Intent(context,OverlayService::class.java).setAction(OverlayService.HIDE),0,2)
        assertTrue(ClockStore(context).load().hidden)
        service.onStartCommand(Intent(context,OverlayService::class.java).setAction(OverlayService.SHOW),0,3)
        assertFalse(ClockStore(context).load().hidden)
        controller.destroy(); assertFalse(OverlayService.running)
    }
    @Test(timeout=30000) fun touchThroughUsesSafeOpacityAndFlags() {
        assertEquals(0.8f,OverlayPolicy.alpha(1f,true,0.8f),0f)
        assertEquals(0.5f,OverlayPolicy.alpha(0.5f,true,0.8f),0f)
        assertEquals(1f,OverlayPolicy.alpha(1f,false,0.8f),0f)
        assertFalse(OverlayPolicy.canDrag(locked=true,touchThrough=false))
        assertFalse(OverlayPolicy.canDrag(locked=false,touchThrough=true))
        assertTrue(OverlayPolicy.canDrag(locked=false,touchThrough=false))
    }
    @Test(timeout=30000) fun activityLaunchAndRecreationKeepTarget() {
        val store=ClockStore(context); val target=store.load().targetMs
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        assertNotNull(controller.get().findViewById<View>(android.R.id.content))
        val state=android.os.Bundle()
        controller.saveInstanceState(state).pause().stop().destroy()
        val recreated=Robolectric.buildActivity(MainActivity::class.java).setup(state)
        assertEquals(target,store.load().targetMs)
        recreated.pause().stop().destroy()
    }
    @Test(timeout=30000) @Config(qualifiers="w360dp-h780dp-xxhdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun renderRealAndroidWidgetSnapshots() {
        val out=File(System.getProperty("user.dir"),"build/widget-previews").apply { mkdirs() }
        for ((name,remaining) in listOf("countdown" to 2500L,"before-three-seconds" to 15700L,"complete" to 0L)) {
            val view=ClockView(context).apply { config=ClockConfig(showFrameTime=false); frame=CountdownFrame.of(remaining) }
            val w=1000; val h=view.desiredHeight(w)
            view.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY)); view.layout(0,0,w,h)
            val b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888); view.draw(Canvas(b))
            File(out,"$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) }
            assertTrue(b.getPixel(w/2,10)!=0)
        }
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        val root=controller.get().findViewById<View>(android.R.id.content)
        root.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(2200,View.MeasureSpec.EXACTLY))
        root.layout(0,0,1080,2200)
        fun clockIn(v:View):ClockView? {
            if(v is ClockView)return v
            if(v is ViewGroup) for(i in 0 until v.childCount) clockIn(v.getChildAt(i))?.let { return it }
            return null
        }
        val dashboardClock=clockIn(root)!!
        assertEquals(dashboardClock.desiredHeight(dashboardClock.width),dashboardClock.height)
        val screen=Bitmap.createBitmap(1080,2200,Bitmap.Config.ARGB_8888)
        root.draw(Canvas(screen))
        File(out,"dashboard.png").outputStream().use { screen.compress(Bitmap.CompressFormat.PNG,100,it) }
        controller.pause().stop().destroy()
    }
}
