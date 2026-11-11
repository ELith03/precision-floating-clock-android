package com.elith.precision

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.input.InputManager
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.Toast
import java.time.ZoneId

class OverlayService: Service() {
    companion object {
        const val START="com.elith.precision.START"
        const val STOP="com.elith.precision.STOP"
        const val HIDE="com.elith.precision.HIDE"
        const val SHOW="com.elith.precision.SHOW"
        const val CHANNEL="floating_countdown"
        var running=false; private set
    }
    private lateinit var store: ClockStore
    private lateinit var wm: WindowManager
    private lateinit var view: ClockView
    private lateinit var config: ClockConfig
    private lateinit var session: CountdownSession
    private val clock=DisciplinedClock()
    private val handler=Handler(Looper.getMainLooper())
    private var params: WindowManager.LayoutParams?=null
    private var attached=false
    private var screenOn=true
    private var pendingReload=false
    private var receivedRegistration=false
    private var disposed=false
    private val listener=SharedPreferences.OnSharedPreferenceChangeListener { _,_ ->
        if(!pendingReload && !disposed) {
            pendingReload=true
            handler.post { pendingReload=false; if(!disposed) reload() }
        }
    }
    private val receiver=object:BroadcastReceiver() {
        override fun onReceive(context:Context,intent:Intent) {
            when(intent.action) {
                Intent.ACTION_SCREEN_OFF -> { screenOn=false; view.stop() }
                Intent.ACTION_SCREEN_ON -> { screenOn=true; applyWindow() }
                Intent.ACTION_TIMEZONE_CHANGED,Intent.ACTION_TIME_CHANGED -> {
                    if(config.daily) {
                        session.reconcile(System.currentTimeMillis(),ZoneId.systemDefault())
                        store.update { it.copy(targetMs=session.targetMs,completed=false,zoneId=ZoneId.systemDefault().id) }
                    }
                    view.frame=currentFrame(); applyWindow()
                }
                Intent.ACTION_CONFIGURATION_CHANGED -> applyWindow()
            }
        }
    }
    override fun onCreate() {
        super.onCreate()
        store=ClockStore(this); store.reconcileZone(); wm=getSystemService(WindowManager::class.java); config=store.load()
        session=newSession(config)
        view=ClockView(this).apply {
            config=this@OverlayService.config
            frameProvider={ currentFrame() }
            onContentHeightChanged={ if(attached) applyWindow() }
        }
        installGestures()
        store.preferences.registerOnSharedPreferenceChangeListener(listener)
        val filter=IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_CONFIGURATION_CHANGED)
        }
        if(Build.VERSION.SDK_INT>=33) registerReceiver(receiver,filter,Context.RECEIVER_NOT_EXPORTED) else registerReceiver(receiver,filter)
        receivedRegistration=true
        screenOn=getSystemService(PowerManager::class.java).isInteractive
    }
    private fun newSession(c:ClockConfig)=CountdownSession(c.targetMs,c.daily,c.localTime,c.completed && !c.daily)
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action==STOP) { stopSelf(); return START_NOT_STICKY }
        if(!Settings.canDrawOverlays(this)) { running=false; stopSelf(); return START_NOT_STICKY }
        when(intent?.action) {
            HIDE -> store.update { it.copy(hidden=true) }
            SHOW -> store.update { it.copy(hidden=false) }
        }
        try {
            createChannel()
            val notification=notification()
            if(Build.VERSION.SDK_INT>=34) startForeground(1,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(1,notification)
            running=true
            reload()
        } catch(e:RuntimeException) {
            Toast.makeText(this,"Cannot start floating clock: ${e.message}",Toast.LENGTH_LONG).show()
            stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun reload() {
        val updated=store.load()
        if(updated.targetKey!=config.targetKey) session=newSession(updated)
        config=updated
        view.config=config
        view.frame=currentFrame()
        if(running) { applyWindow(); getSystemService(NotificationManager::class.java).notify(1,notification()) }
    }
    private fun currentFrame(): CountdownFrame {
        val now=clock.now(System.currentTimeMillis(),SystemClock.elapsedRealtimeNanos())
        val wasCompleted=session.completed
        val previousTarget=session.targetMs
        val result=session.frame(now,SystemClock.elapsedRealtime(),ZoneId.systemDefault())
        if(!wasCompleted && session.completed) {
            // Latch completion across process death; no alarm or effect is promised while asleep.
            if(!config.daily) store.update { it.copy(completed=true) }
            if(screenOn) {
                if(config.completion=="flash") view.completionFlashUntil=SystemClock.uptimeMillis()+500
                if(config.completion=="vibrate") {
                    @Suppress("DEPRECATION")
                    val vibrator=getSystemService(VIBRATOR_SERVICE) as Vibrator
                    vibrator.vibrate(VibrationEffect.createOneShot(60,VibrationEffect.DEFAULT_AMPLITUDE))
                }
            }
            if(config.hideOnComplete && !config.daily) {
                val completedSession=session
                handler.postDelayed({
                    if(!disposed && session===completedSession && session.completed && store.load().targetKey==config.targetKey)
                        store.update { it.copy(hidden=true) }
                },1200)
            }
        }
        if(previousTarget!=session.targetMs) {
            // Update config immediately so asynchronous preference callbacks do not recreate the session.
            config=config.copy(targetMs=session.targetMs,completed=false)
            store.update { it.copy(targetMs=session.targetMs,completed=false,zoneId=ZoneId.systemDefault().id) }
        }
        return result
    }
    private fun safeBounds(): Rect {
        if(Build.VERSION.SDK_INT>=30) {
            val m=wm.currentWindowMetrics
            val i=m.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            return Rect(m.bounds.left+i.left,m.bounds.top+i.top,m.bounds.right-i.right,m.bounds.bottom-i.bottom)
        }
        val metrics=resources.displayMetrics
        val rect=Rect(0,0,metrics.widthPixels,metrics.heightPixels)
        val status=resources.getIdentifier("status_bar_height","dimen","android")
        val nav=resources.getIdentifier("navigation_bar_height","dimen","android")
        if(status>0) rect.top+=resources.getDimensionPixelSize(status)
        if(nav>0) rect.bottom-=resources.getDimensionPixelSize(nav)
        return rect
    }
    private fun geometry(): Pair<Rect,Int> {
        val r=safeBounds(); val margin=(8*resources.displayMetrics.density).toInt(); r.inset(margin,margin)
        val requested=(config.widthDp*resources.displayMetrics.density).toInt()
        val expandedUnits=(if(config.compact)138 else 168)+(if(config.showNetwork || config.showRefresh)22 else 0)
        val fitted=minOf(requested,r.width(),r.height()*400/expandedUnits).coerceAtLeast(1)
        return r to fitted
    }
    private fun maxHeight(w:Int)=((if(config.compact) 138 else 168)+(if(config.showNetwork || config.showRefresh)22 else 0))*w/400
    private fun applyWindow() {
        if(!running || disposed) return
        if(config.hidden) { detach(); return }
        val (r,w)=geometry()
        val h=view.desiredHeight(w)
        val maximum=if(Build.VERSION.SDK_INT>=31) getSystemService(InputManager::class.java).maximumObscuringOpacityForTouch else 1f
        val lp=params?:WindowManager.LayoutParams(w,h,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT).apply {
            gravity=Gravity.TOP or Gravity.LEFT
            if(Build.VERSION.SDK_INT>=28) layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
        }
        lp.width=w; lp.height=h
        lp.x=r.left+((r.width()-w).coerceAtLeast(0)*config.positionX).toInt()
        lp.y=r.top+((r.height()-maxHeight(w)).coerceAtLeast(0)*config.positionY).toInt()
        lp.flags=WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            (if(config.touchThrough) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0)
        lp.alpha=OverlayPolicy.alpha(config.opacity,config.touchThrough,maximum)
        params=lp
        try {
            if(!attached) { wm.addView(view,lp); attached=true } else wm.updateViewLayout(view,lp)
            if(screenOn) view.start() else view.stop()
        } catch(_:RuntimeException) { detach(); stopSelf() }
    }
    private fun detach() {
        view.stop()
        if(attached) { try { wm.removeView(view) } catch(_:IllegalArgumentException) { }; attached=false }
    }
    private fun installGestures() {
        var startX=0f; var startY=0f; var originX=0; var originY=0; var resized=false
        val scale=ScaleGestureDetector(this,object:ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector:ScaleGestureDetector):Boolean {
                if(!OverlayPolicy.canDrag(config.locked,config.touchThrough)) return false
                resized=true
                config=config.copy(widthDp=(config.widthDp*detector.scaleFactor).toInt().coerceIn(180,700))
                view.config=config; applyWindow(); return true
            }
        })
        val gestures=GestureDetector(this,object:GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e:MotionEvent)=true
            override fun onLongPress(e:MotionEvent) {
                startActivity(Intent(this@OverlayService,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            override fun onDoubleTap(e:MotionEvent):Boolean { store.update { it.copy(hidden=true) }; return true }
        })
        view.setOnTouchListener { _,event ->
            gestures.onTouchEvent(event); scale.onTouchEvent(event)
            if(!OverlayPolicy.canDrag(config.locked,config.touchThrough)) return@setOnTouchListener true
            val lp=params?:return@setOnTouchListener true
            when(event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startX=event.rawX; startY=event.rawY; originX=lp.x; originY=lp.y; resized=false }
                MotionEvent.ACTION_MOVE -> if(!scale.isInProgress && event.pointerCount==1 && !resized) {
                    val (r,w)=geometry()
                    lp.x=(originX+event.rawX-startX).toInt().coerceIn(r.left,(r.right-w).coerceAtLeast(r.left))
                    lp.y=(originY+event.rawY-startY).toInt().coerceIn(r.top,(r.bottom-maxHeight(w)).coerceAtLeast(r.top))
                    try { wm.updateViewLayout(view,lp) } catch(_:RuntimeException) { stopSelf() }
                }
                MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> {
                    val (r,w)=geometry()
                    val x=(lp.x-r.left)/(r.width()-w).coerceAtLeast(1).toFloat()
                    val y=(lp.y-r.top)/(r.height()-maxHeight(w)).coerceAtLeast(1).toFloat()
                    store.update { it.copy(widthDp=config.widthDp,positionX=x.coerceIn(0f,1f),positionY=y.coerceIn(0f,1f)) }
                    view.performClick()
                }
            }
            true
        }
    }
    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,"Floating countdown",NotificationManager.IMPORTANCE_LOW).apply {
            description="Settings, hide/show and stop controls for the floating clock"; setShowBadge(false)
        })
    }
    private fun notification(): Notification {
        val flags=PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val settings=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),flags)
        fun action(name:String,id:Int)=PendingIntent.getService(this,id,Intent(this,OverlayService::class.java).setAction(name),flags)
        val hidden=store.load().hidden
        return Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_clock).setContentTitle("Precision Clock is running")
            .setContentText(if(hidden) "Clock hidden · tap to open settings" else "Device time countdown · tap for settings")
            .setContentIntent(settings).setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
            .addAction(Notification.Action.Builder(null,if(hidden)"Show" else "Hide",action(if(hidden)SHOW else HIDE,1)).build())
            .addAction(Notification.Action.Builder(null,"Stop",action(STOP,2)).build()).build()
    }
    override fun onDestroy() {
        disposed=true; handler.removeCallbacksAndMessages(null); running=false
        if(::view.isInitialized) detach()
        if(::store.isInitialized) store.preferences.unregisterOnSharedPreferenceChangeListener(listener)
        if(receivedRegistration) unregisterReceiver(receiver)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    override fun onBind(intent:Intent?)=null
}
