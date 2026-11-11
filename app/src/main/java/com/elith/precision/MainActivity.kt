package com.elith.precision

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.text.InputType
import android.view.*
import android.widget.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

class MainActivity:Activity() {
    private lateinit var store:ClockStore
    private lateinit var body:LinearLayout
    private lateinit var preview:ClockView
    private lateinit var currentLabel:TextView
    private lateinit var targetLabel:TextView
    private lateinit var statusLabel:TextView
    private lateinit var enableButton:Button
    private lateinit var session:CountdownSession
    private var sessionKey=""
    private var tab=0
    private var pendingStart=false
    private val handler=Handler(Looper.getMainLooper())
    private val white=Color.rgb(245,247,252)
    private val muted=Color.rgb(153,165,187)
    private val accent=Color.rgb(255,81,61)
    private val background=Color.rgb(16,20,30)
    private val card=Color.rgb(27,33,47)
    private val clock=DisciplinedClock()
    private val ticker=object:Runnable {
        override fun run() { updateDashboard(); handler.postDelayed(this,250) }
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        store=ClockStore(this); tab=savedInstanceState?.getInt("tab")?:0
        if(!store.preferences.contains("target")) store.save(ClockConfig())
        window.decorView.setOnApplyWindowInsetsListener { v,insets ->
            if(Build.VERSION.SDK_INT>=30) {
                val i=insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(i.left,i.top,i.right,i.bottom)
            } else { v.setPadding(insets.systemWindowInsetLeft,insets.systemWindowInsetTop,insets.systemWindowInsetRight,insets.systemWindowInsetBottom) }
            insets
        }
        render()
    }
    override fun onSaveInstanceState(outState:Bundle) { outState.putInt("tab",tab); super.onSaveInstanceState(outState) }
    override fun onResume() {
        super.onResume()
        store.reconcileZone()
        if(pendingStart && Settings.canDrawOverlays(this)) { pendingStart=false; requestNotificationAndStart() }
        refreshPreview(); preview.start(); handler.removeCallbacks(ticker); handler.post(ticker)
    }
    override fun onPause() { handler.removeCallbacks(ticker); preview.stop(); super.onPause() }
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private fun rounded(color:Int,radius:Int=18)=GradientDrawable().apply { setColor(color); cornerRadius=dp(radius).toFloat() }
    private fun text(value:String,size:Int=14,color:Int=white,bold:Boolean=false)=TextView(this).apply {
        text=value; textSize=size.toFloat(); setTextColor(color); if(bold) typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
        setLineSpacing(dp(3).toFloat(),1f)
    }
    private fun column(parent:LinearLayout,padding:Int=18):LinearLayout = LinearLayout(this).apply {
        orientation=LinearLayout.VERTICAL; setPadding(dp(padding),dp(padding),dp(padding),dp(padding)); background=rounded(card)
        parent.addView(this,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(14) })
    }
    private fun gap(parent:LinearLayout,height:Int=12) { parent.addView(Space(this),LinearLayout.LayoutParams(1,dp(height))) }
    private fun button(parent:LinearLayout,label:String,primary:Boolean=false,action:()->Unit):Button=Button(this).apply {
        text=label; isAllCaps=false; textSize=14f; setTextColor(if(primary)Color.WHITE else white)
        typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
        background=rounded(if(primary)accent else Color.rgb(43,52,71),12)
        minHeight=dp(48); minimumHeight=dp(48); setPadding(dp(12),dp(9),dp(12),dp(9))
        parent.addView(this,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(9) })
        setOnClickListener { action() }
    }
    private fun heading(parent:LinearLayout,title:String,subtitle:String?=null) {
        parent.addView(text(title,18,bold=true))
        if(subtitle!=null) { gap(parent,5); parent.addView(text(subtitle,12,muted)) }
        gap(parent,8)
    }
    private fun render() {
        val scroll=ScrollView(this).apply { setBackgroundColor(this@MainActivity.background); isFillViewport=true }
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(20),dp(20),dp(20),dp(32)) }
        scroll.addView(root); setContentView(scroll)
        root.addView(text("PRECISION   /   LOCAL CLOCK",11,accent,true))
        gap(root,8); root.addView(text("Every moment.\nExactly yours.",30,bold=true)); gap(root,6)
        root.addView(text("A floating countdown, set by device time.",13,muted)); gap(root,22)
        val dashboard=column(root,16)
        val statusRow=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
        dashboard.addView(statusRow)
        statusRow.addView(text("LIVE PREVIEW",10,muted,true),LinearLayout.LayoutParams(0,-2,1f))
        statusLabel=text("○ INACTIVE",10,accent,true); statusRow.addView(statusLabel)
        gap(dashboard,13)
        preview=ClockView(this)
        preview.layoutParams=LinearLayout.LayoutParams(-1,-2)
        preview.onContentHeightChanged={ preview.requestLayout() }
        preview.frameProvider={ frameNow() }
        dashboard.addView(preview)
        gap(dashboard,13)
        currentLabel=text("",12,muted); targetLabel=text("",13,white,true)
        dashboard.addView(currentLabel); gap(dashboard,6); dashboard.addView(targetLabel)
        enableButton=button(dashboard,"Enable floating clock",true) {
            if(OverlayService.running) { stopService(Intent(this,OverlayService::class.java)); updateDashboard() } else enableOverlay()
        }
        val tabs=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        root.addView(tabs); gap(root,18)
        listOf("Target","Style","Float","Info").forEachIndexed { index,label ->
            val b=Button(this).apply {
                text=label; textSize=12f; isAllCaps=false; setTextColor(if(index==tab)white else muted)
                background=rounded(if(index==tab)Color.rgb(52,62,82) else card,10); minWidth=0; minimumWidth=0; setPadding(0,0,0,0)
                setOnClickListener { tab=index; render(); refreshPreview(); preview.start() }
            }
            tabs.addView(b,LinearLayout.LayoutParams(0,dp(43),1f).apply { if(index>0) leftMargin=dp(6) })
        }
        body=root
        when(tab) { 0->targetSection(); 1->styleSection(); 2->floatingSection(); else->infoSection() }
        refreshPreview(); updateDashboard()
    }
    private fun refreshPreview() { preview.config=store.load(); preview.alpha=store.load().opacity; preview.frame=frameNow() }
    private fun frameNow():CountdownFrame {
        store.reconcileZone()
        val c=store.load()
        if(sessionKey!=c.targetKey) { sessionKey=c.targetKey; session=CountdownSession(c.targetMs,c.daily,c.localTime,c.completed && !c.daily) }
        val old=session.targetMs
        val f=session.frame(clock.now(System.currentTimeMillis(),SystemClock.elapsedRealtimeNanos()),SystemClock.elapsedRealtime(),ZoneId.systemDefault())
        if(session.completed && !c.daily && !c.completed && !OverlayService.running) store.update { it.copy(completed=true) }
        if(session.targetMs!=old && !OverlayService.running) {
            store.update { it.copy(targetMs=session.targetMs,completed=false) }; sessionKey=store.load().targetKey
        }
        return f
    }
    private fun updateDashboard() {
        if(!::currentLabel.isInitialized) return
        val c=store.load()
        val pattern=if(c.use24Hour) "HH:mm:ss" else "hh:mm:ss a"
        val now=ZonedDateTime.now()
        currentLabel.text="Device ${now.format(DateTimeFormatter.ofPattern(pattern,Locale.US))}  ·  ${now.zone.id}"
        val next=Instant.ofEpochMilli(if(::session.isInitialized)session.targetMs else c.targetMs).atZone(ZoneId.systemDefault())
        targetLabel.text="${if(c.daily) "Daily · next" else "Target"} ${next.format(DateTimeFormatter.ofPattern("d MMM · $pattern",Locale.US))}${if(c.millisecond>0) ".%03d".format(Locale.US,c.millisecond) else ""}"
        statusLabel.text=when { !OverlayService.running -> "○ INACTIVE"; c.hidden -> "● HIDDEN"; else -> "● FLOATING" }
        enableButton.text=if(OverlayService.running) "Stop floating clock" else "Enable floating clock"
    }
    private fun change(block:(ClockConfig)->ClockConfig) { store.update(block); refreshPreview(); updateDashboard() }
    private fun targetSection() {
        val section=column(body)
        heading(section,"Set your moment","Choose the next local time, an exact date, or a daily repeat.")
        button(section,"Choose target time & date",true) { editTarget() }
        gap(section,10); section.addView(text("QUICK TARGETS",10,muted,true))
        button(section,"Upcoming midnight") { setTarget(LocalTime.MIDNIGHT) }
        button(section,"Next minute") {
            val t=ZonedDateTime.now().plusMinutes(1).truncatedTo(ChronoUnit.MINUTES); setInstant(t)
        }
        button(section,"Next hour") {
            val t=ZonedDateTime.now().plusHours(1).truncatedTo(ChronoUnit.HOURS); setInstant(t)
        }
        button(section,"10-second test countdown") { setInstant(ZonedDateTime.now().plusSeconds(10)) }
        val done=column(body); heading(done,"At zero","Silent by default. Daily targets hold zero for 1.2 seconds, then advance.")
        val c=store.load()
        choice(done,"Completion effect",listOf("Silent","Subtle flash","Short vibration"),listOf("silent","flash","vibrate").indexOf(c.completion).coerceAtLeast(0)) { i -> change { it.copy(completion=listOf("silent","flash","vibrate")[i]) } }
        toggle(done,"Hide after one-time completion",c.hideOnComplete) { v -> change { it.copy(hideOnComplete=v) } }
        done.addView(text("The five checkpoint dots appear only during the final 3 seconds. Zero stays fixed until you choose another target.",12,muted))
    }
    private fun setTarget(time:LocalTime) {
        change { it.copy(targetMs=TargetSchedule.next(System.currentTimeMillis(),ZoneId.systemDefault(),time),hour=time.hour,minute=time.minute,second=time.second,millisecond=time.nano/1000000,daily=false,completed=false,hidden=false,zoneId=ZoneId.systemDefault().id,targetMode="next") }
    }
    private fun setInstant(t:ZonedDateTime) {
        change { it.copy(targetMs=t.toInstant().toEpochMilli(),hour=t.hour,minute=t.minute,second=t.second,millisecond=t.nano/1000000,daily=false,completed=false,hidden=false,zoneId=ZoneId.systemDefault().id,targetMode="date") }
    }
    private fun editTarget() {
        val c=store.load()
        val local=Instant.ofEpochMilli(c.targetMs).atZone(ZoneId.systemDefault())
        val absolute=c.targetMode=="date" && !c.daily
        var hour=if(absolute)local.hour else c.hour; var minute=if(absolute)local.minute else c.minute
        var date=local.toLocalDate()
        val layout=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(22),dp(10),dp(22),0) }
        val modes=RadioGroup(this)
        listOf("Next occurrence","One-time date","Daily repeat").forEachIndexed { i,label -> modes.addView(RadioButton(this).apply { id=100+i; text=label; setTextColor(white) }) }
        val initialMode=if(c.daily)102 else if(c.targetMode=="date")101 else 100
        modes.check(initialMode); layout.addView(modes)
        val timeButton=button(layout,"%02d:%02d".format(hour,minute)) { }
        timeButton.setOnClickListener {
            TimePickerDialog(this,{_,h,m -> hour=h;minute=m;timeButton.text=if(c.use24Hour) "%02d:%02d".format(h,m) else LocalTime.of(h,m).format(DateTimeFormatter.ofPattern("hh:mm a",Locale.US)) },hour,minute,c.use24Hour).show()
        }
        val dateButton=button(layout,"Date · $date") { }
        dateButton.setOnClickListener { DatePickerDialog(this,{_,y,m,d -> date=LocalDate.of(y,m+1,d);dateButton.text="Date · $date" },date.year,date.monthValue-1,date.dayOfMonth).show() }
        dateButton.visibility=if(initialMode==101)View.VISIBLE else View.GONE
        modes.setOnCheckedChangeListener { _,id -> dateButton.visibility=if(id==101)View.VISIBLE else View.GONE }
        layout.addView(text("Seconds (0–59)       Milliseconds (0–999)",12,muted))
        val row=LinearLayout(this); layout.addView(row)
        fun numeric(value:Int):EditText=EditText(this).apply {
            inputType=InputType.TYPE_CLASS_NUMBER; setText(value.toString()); setTextColor(white); setSelectAllOnFocus(true)
            row.addView(this,LinearLayout.LayoutParams(0,dp(52),1f))
        }
        val second=numeric(if(absolute)local.second else c.second); val ms=numeric(if(absolute)local.nano/1000000 else c.millisecond)
        val dialog=AlertDialog.Builder(this).setTitle("Target moment").setView(layout).setNegativeButton("Cancel",null).setPositiveButton("Set countdown",null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val sec=second.text.toString().toIntOrNull(); val milli=ms.text.toString().toIntOrNull()
                if(sec==null || sec !in 0..59 || milli==null || milli !in 0..999) { Toast.makeText(this,"Enter seconds 0–59 and milliseconds 0–999",Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                val time=LocalTime.of(hour,minute,sec,milli*1000000)
                val daily=modes.checkedRadioButtonId==102
                val target=if(modes.checkedRadioButtonId==101) date.atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() else TargetSchedule.next(System.currentTimeMillis(),ZoneId.systemDefault(),time)
                if(target<=System.currentTimeMillis()) { Toast.makeText(this,"Choose a future date and time",Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                change { it.copy(targetMs=target,daily=daily,hour=hour,minute=minute,second=sec,millisecond=milli,completed=false,hidden=false,zoneId=ZoneId.systemDefault().id,targetMode=if(daily)"daily" else if(modes.checkedRadioButtonId==101)"date" else "next") }
                dialog.dismiss()
            }
        }
        dialog.show()
    }
    private fun toggle(parent:LinearLayout,label:String,value:Boolean,onChange:(Boolean)->Unit) {
        val s=Switch(this).apply { text=label; textSize=13f; setTextColor(white); isChecked=value; setPadding(0,dp(12),0,dp(12)); setOnCheckedChangeListener { _,v -> onChange(v) } }
        parent.addView(s,LinearLayout.LayoutParams(-1,-2))
    }
    private fun slider(parent:LinearLayout,label:String,min:Int,max:Int,current:Int,suffix:String,onChange:(Int)->Unit) {
        val caption=text("$label  $current$suffix",13)
        parent.addView(caption)
        val seek=SeekBar(this).apply {
            this.max=max-min; progress=current.coerceIn(min,max)-min
            setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar:SeekBar,value:Int,fromUser:Boolean) { caption.text="$label  ${value+min}$suffix"; if(fromUser)onChange(value+min) }
                override fun onStartTrackingTouch(bar:SeekBar) { }
                override fun onStopTrackingTouch(bar:SeekBar) { }
            })
        }
        parent.addView(seek,LinearLayout.LayoutParams(-1,dp(42))); gap(parent,8)
    }
    private fun choice(parent:LinearLayout,label:String,options:List<String>,selected:Int,onChoose:(Int)->Unit) {
        val b=button(parent,"$label · ${options[selected.coerceIn(options.indices)]}") { }
        b.setOnClickListener { AlertDialog.Builder(this).setTitle(label).setSingleChoiceItems(options.toTypedArray(),selected) { dialog,i -> onChoose(i); b.text="$label · ${options[i]}";dialog.dismiss() }.setNegativeButton("Cancel",null).show() }
    }
    private fun styleSection() {
        val c=store.load(); val section=column(body)
        heading(section,"Make it yours","Size and opacity update the floating clock immediately.")
        slider(section,"Width",180,700,c.widthDp," dp") { v -> change { it.copy(widthDp=v) } }
        slider(section,"Opacity",20,100,(c.opacity*100).toInt(),"%") { v -> change { it.copy(opacity=v/100f) } }
        slider(section,"Digit size",60,115,(c.fontScale*100).toInt(),"%") { v -> change { it.copy(fontScale=v/100f) } }
        slider(section,"Corner radius",0,40,c.radius.toInt(),"") { v -> change { it.copy(radius=v.toFloat()) } }
        toggle(section,"Compact layout",c.compact) { v -> change { it.copy(compact=v) } }
        choice(section,"Fractional digits",listOf("1 digit","2 digits","3 digits"),c.fractionDigits-1) { i -> change { it.copy(fractionDigits=i+1) } }
        button(section,"Accent color") { colorDialog(true) }
        button(section,"Background color") { colorDialog(false) }
        val extras=column(body); heading(extras,"Optional details","Keep these off for the cleanest reference-style card.")
        toggle(extras,"Measured draw interval (ms)",c.showFrameTime) { v -> change { it.copy(showFrameTime=v) } }
        toggle(extras,"Display Hz & render cap",c.showRefresh) { v -> change { it.copy(showRefresh=v) } }
        toggle(extras,"Live upload & download",c.showNetwork) { v -> change { it.copy(showNetwork=v) } }
        toggle(extras,"24-hour local time",c.use24Hour) { v -> change { it.copy(use24Hour=v) } }
    }
    private fun colorDialog(isAccent:Boolean) {
        val c=store.load(); val input=EditText(this).apply { setText("#%06X".format(0xFFFFFF and if(isAccent)c.accent else c.background)); inputType=InputType.TYPE_CLASS_TEXT; setSingleLine(true) }
        val dialog=AlertDialog.Builder(this).setTitle(if(isAccent)"Accent · #RRGGBB" else "Background · #RRGGBB").setView(input).setPositiveButton("Apply",null).setNegativeButton("Cancel",null).create()
        dialog.setOnShowListener { dialog.getButton(-1).setOnClickListener {
            val value=input.text.toString().trim()
            if(!Regex("#[0-9a-fA-F]{6}").matches(value)) { input.error="Use #RRGGBB";return@setOnClickListener }
            val color=Color.parseColor(value); change { if(isAccent)it.copy(accent=color) else it.copy(background=color) }; dialog.dismiss()
        } };dialog.show()
    }
    private fun floatingSection() {
        val c=store.load(); val controls=column(body)
        heading(controls,"Floating controls","Drag to move. Pinch with two fingers to resize. Long-press opens settings; double-tap hides.")
        toggle(controls,"Lock position & size",c.locked) { v -> change { it.copy(locked=v) } }
        toggle(controls,"Touch-through",c.touchThrough) { v -> change { it.copy(touchThrough=v) } }
        controls.addView(text("Touch-through lets taps reach the app underneath. Android 12+ may limit opacity to 80%. Reopen this app or tap the notification to turn it off.",12,muted))
        button(controls,if(c.hidden) "Show overlay" else "Hide overlay") { change { it.copy(hidden=!it.hidden) }; render();preview.start() }
        button(controls,"Reset position") { change { it.copy(positionX=0.5f,positionY=0.18f) } }
        button(controls,"Overlay permission") { openOverlayPermission() }
        button(controls,"Notification settings") { startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,packageName).putExtra(Settings.EXTRA_CHANNEL_ID,OverlayService.CHANNEL)) }
        val perf=column(body);heading(perf,"Rendering & power")
        val caps=listOf(30,60,0,90,120)
        choice(perf,"Render cap",listOf("30 FPS","60 FPS","Display matched / Auto","90 FPS","120 FPS"),caps.indexOf(c.fpsCap).coerceAtLeast(0)) { i -> change { it.copy(fpsCap=caps[i]) } }
        toggle(perf,"Power saver outside final 10 seconds",c.powerSaving) { v -> change { it.copy(powerSaving=v) } }
        perf.addView(text("Power saver caps rendering at 30 FPS outside the final 10 seconds. Caps never force a hardware refresh rate. Drawing pauses when the screen is off.",12,muted))
    }
    private fun infoSection() {
        val info=column(body); heading(info,"Honest precision")
        @Suppress("DEPRECATION") val hz=windowManager.defaultDisplay.refreshRate
        info.addView(text("Display: %.1f Hz\n\n".format(Locale.US,hz)+
            "The ms label is an average interval between Canvas draw callbacks. It is not network latency, CPU/GPU frame time, or clock-sync accuracy.\n\n"+
            "The device wall clock is authoritative. Monotonic time smooths updates and clock steps re-anchor immediately. Digits round upward so zero never appears early. Scheduling and display frames can delay the visible transition.\n\n"+
            "Network speeds use differences in Android TrafficStats byte counters, sampled about once a second. VPNs, virtual interfaces and vendor restrictions may affect totals. A dash means a baseline or statistics are unavailable.\n\n"+
            "One-time dates represent an absolute instant. Daily times follow the local timezone; DST gaps move forward and repeated hours use the first occurrence. Daily zero holds for 1.2 seconds.\n\n"+
            "No CPU wake lock or exact alarm: completion effects may be delayed until waking. Android can terminate foreground services, and secure apps or system screens may hide overlays. Start again from this app if needed.\n\n"+
            "Offline · no ads · no account · no internet permission",13,muted))
        val general=column(body);heading(general,"General")
        button(general,"Reset all configuration") {
            AlertDialog.Builder(this).setTitle("Reset Precision Clock?").setMessage("Restore appearance and set the next midnight as the target.").setNegativeButton("Cancel",null).setPositiveButton("Reset") { _,_ -> stopService(Intent(this,OverlayService::class.java)); store.reset();sessionKey="";render();preview.start() }.show()
        }
        general.addView(text("Precision Clock 1.0.0 · EL\nInitial personal debug build",12,muted))
    }
    private fun enableOverlay() {
        if(!Settings.canDrawOverlays(this)) { pendingStart=true; openOverlayPermission(); return }
        requestNotificationAndStart()
    }
    private fun openOverlayPermission() {
        try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName"))) }
        catch(_:Exception) { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) }
    }
    private fun requestNotificationAndStart() {
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),44)
        else startClock()
    }
    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode==44) startClock()
    }
    private fun startClock() {
        change { it.copy(hidden=false) }
        try { startForegroundService(Intent(this,OverlayService::class.java).setAction(OverlayService.START)); handler.postDelayed({ updateDashboard() },300) }
        catch(e:RuntimeException) { Toast.makeText(this,"Floating clock could not start: ${e.message}",Toast.LENGTH_LONG).show() }
    }
}
