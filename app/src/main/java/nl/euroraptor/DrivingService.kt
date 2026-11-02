package nl.euroraptor

import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.graphics.Color
import android.graphics.PixelFormat
import android.location.*
import android.os.*
import android.view.*
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.*

class DrivingService : Service(), LocationListener {
 private lateinit var wm: WindowManager
 private lateinit var overlay: TextView
 private lateinit var lm: LocationManager
 private val executor = Executors.newSingleThreadExecutor()
 private val handler=Handler(Looper.getMainLooper())
 private var limit:Int?=null
 private var roadType:FineCalculator.RoadType?=null
 private var specialZone=false
 private var lastQuery=0L
 private var lastLat=0.0
 private var lastLon=0.0
 private var speed:Double?=null
 override fun onBind(intent:Intent?)=null
 @SuppressLint("MissingPermission") override fun onCreate() { super.onCreate()
  val channel=NotificationChannel("driving","EuroRaptor actief",NotificationManager.IMPORTANCE_LOW)
  getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
  val notification=Notification.Builder(this,"driving").setContentTitle("EuroRaptor actief").setContentText("GPS en overlay actief").setSmallIcon(android.R.drawable.ic_menu_mylocation).build()
  startForeground(1,notification)
  wm=getSystemService(WINDOW_SERVICE) as WindowManager
  overlay=TextView(this).apply { text="€ —"; textSize=30f; setTextColor(Color.WHITE); setBackgroundColor(Color.DKGRAY); setPadding(22,12,22,12) }
  val p=WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT).apply { gravity=Gravity.TOP or Gravity.START; x=35; y=170 }
  overlay.setOnTouchListener(object:View.OnTouchListener { var x=0;var y=0;var tx=0f;var ty=0f
   override fun onTouch(v:View,event:android.view.MotionEvent):Boolean { when(event.action){ MotionEvent.ACTION_DOWN->{x=p.x;y=p.y;tx=event.rawX;ty=event.rawY;return true};MotionEvent.ACTION_MOVE->{p.x=x+(event.rawX-tx).toInt();p.y=y+(event.rawY-ty).toInt();wm.updateViewLayout(overlay,p);return true} };return false }
  })
  wm.addView(overlay,p)
  lm=getSystemService(LOCATION_SERVICE) as LocationManager
  lm.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000L,0f,this)
 }
 override fun onLocationChanged(location:Location) {
  speed=if(location.hasSpeed() && location.accuracy<=25f) location.speed*3.6 else null
  update()
  val now=System.currentTimeMillis()
  if(now-lastQuery>20000 && (lastQuery==0L || distance(location.latitude,location.longitude,lastLat,lastLon)>100)) {
   lastQuery=now;lastLat=location.latitude;lastLon=location.longitude
   val lat=location.latitude;val lon=location.longitude
   executor.execute { val result=fetchLimit(lat,lon);handler.post { limit=result?.first;roadType=result?.second;specialZone=result?.third?:false;update() } }
  }
 }
 private fun distance(a:Double,b:Double,c:Double,d:Double):Double { val x=(a-c)*111000;val y=(b-d)*111000*cos(Math.toRadians(a));return hypot(x,y) }
 private fun fetchLimit(lat:Double,lon:Double):Triple<Int,FineCalculator.RoadType?,Boolean>? { return try {
  // Conservative: accept only an unambiguous maxspeed from exactly one nearby mapped road.
  val q="[out:json][timeout:8];way(around:15,$lat,$lon)[highway][maxspeed];out tags;"
  val url=URL("https://overpass.kumi.systems/api/interpreter?data="+java.net.URLEncoder.encode(q,"UTF-8"))
  val conn=url.openConnection() as HttpURLConnection;conn.connectTimeout=8000;conn.readTimeout=8000
  val root=JSONObject(conn.inputStream.bufferedReader().use { it.readText() });conn.disconnect()
  val arr=root.getJSONArray("elements")
  if(arr.length()!=1) null else {
   val tags=arr.getJSONObject(0).getJSONObject("tags")
   if(tags.has("maxspeed:conditional") || tags.has("maxspeed:variable")) null else {
    val max=tags.optString("maxspeed").toIntOrNull()
    val highway=tags.optString("highway")
    val source=tags.optString("source:maxspeed")+" "+tags.optString("zone:maxspeed")
    val kind=when {
     highway=="motorway" || highway=="motorway_link" -> FineCalculator.RoadType.MOTORWAY
     source.contains("NL:urban",true) -> FineCalculator.RoadType.URBAN
     source.contains("NL:rural",true) -> FineCalculator.RoadType.RURAL
     else -> null
    }
    val special=tags.optString("zone:maxspeed").contains("30") || highway=="living_street"
    if(max==null || max !in 5..130) null else Triple(max,kind,special)
   }
  }
 } catch(e:Exception){null} }
 private fun update() {
  val result=speed?.let { FineCalculator.calculate(it,limit,roadType,specialZone) } ?: FineCalculator.Result.Unknown
  when(result) {
   is FineCalculator.Result.Amount -> {
    overlay.text="€ ${result.euros}"
    overlay.setBackgroundColor(if(result.euros==0) Color.rgb(0,110,40) else Color.rgb(160,35,25))
   }
   is FineCalculator.Result.Warning -> {
    overlay.text=result.label
    overlay.setBackgroundColor(Color.rgb(175,30,25))
   }
   FineCalculator.Result.Unknown -> {
    overlay.text="€ —"
    overlay.setBackgroundColor(Color.DKGRAY)
   }
  }
 }
 override fun onDestroy() {lm.removeUpdates(this);wm.removeView(overlay);executor.shutdownNow();super.onDestroy()}
}
