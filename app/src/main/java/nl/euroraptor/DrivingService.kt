package nl.euroraptor

import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.location.*
import android.os.*
import android.view.*
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
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
 private var roads:List<RoadMatcher.Road> = emptyList()
 private var roadsLoadedAt=0L
 private var previousRoad:Long?=null
 private var fetching=false
 private var lastLat=0.0
 private var lastLon=0.0
 private var cacheRadius=650.0
 private var lastStatus="Wachten op GPS"
 private var lastNetwork="Nog geen kaartaanvraag"
 private var lastRequestAt=0L
 private var lastDuration=0L
 private var server="nog geen"
 private var speed:Double?=null
 private lateinit var offlineRoads:OfflineRoads
 override fun onBind(intent:Intent?)=null
 @SuppressLint("MissingPermission") override fun onCreate() { super.onCreate()
  val channel=NotificationChannel("driving","EuroRaptor actief",NotificationManager.IMPORTANCE_LOW)
  getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
  val notification=Notification.Builder(this,"driving").setContentTitle("EuroRaptor actief").setContentText("GPS en overlay actief").setSmallIcon(android.R.drawable.ic_menu_mylocation).build()
  startForeground(1,notification)
  wm=getSystemService(WINDOW_SERVICE) as WindowManager
  // Google Maps-like speed capsule: dark charcoal, white bold sans-serif,
  // fully rounded ends and compact dimensions. Keep fine amount as the label.
  val density=resources.displayMetrics.density
  fun dp(value:Float)=(value*density+0.5f).toInt()
  overlay=TextView(this).apply {
   text="€ —"
   setTextSize(TypedValue.COMPLEX_UNIT_SP,25f)
   typeface=Typeface.create("sans-serif-medium",Typeface.BOLD)
   setTextColor(Color.WHITE)
   gravity=Gravity.CENTER
   setPadding(dp(18f),dp(9f),dp(18f),dp(9f))
   minHeight=dp(52f)
   background=GradientDrawable().apply {
    shape=GradientDrawable.RECTANGLE
    cornerRadius=dp(100f).toFloat()
    setColor(Color.rgb(13,16,20))
    setStroke(dp(1f),Color.WHITE)
   }
   elevation=dp(5f).toFloat()
  }
  val p=WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT).apply { gravity=Gravity.TOP or Gravity.START; x=35; y=170 }
  val touchSlop=ViewConfiguration.get(this).scaledTouchSlop
  overlay.setOnTouchListener(object:View.OnTouchListener {
   var startX=0;var startY=0;var downX=0f;var downY=0f
   var dragging=false
   override fun onTouch(v:View,event:MotionEvent):Boolean {
    when(event.actionMasked) {
     MotionEvent.ACTION_DOWN -> {
      startX=p.x;startY=p.y;downX=event.rawX;downY=event.rawY
      dragging=false
      return true
     }
     MotionEvent.ACTION_MOVE -> {
      val dx=event.rawX-downX;val dy=event.rawY-downY
      if(!dragging && hypot(dx.toDouble(),dy.toDouble())>touchSlop) dragging=true
      if(dragging) {
       p.x=startX+dx.toInt();p.y=startY+dy.toInt()
       wm.updateViewLayout(overlay,p)
      }
      return true
     }
     MotionEvent.ACTION_UP -> {
      if(!dragging && hypot((event.rawX-downX).toDouble(),(event.rawY-downY).toDouble())<=touchSlop) {
       val intent=Intent(this@DrivingService,MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
       }
       startActivity(intent)
      }
      return true
     }
     MotionEvent.ACTION_CANCEL -> { dragging=false;return true }
    }
    return true
   }
  })
  wm.addView(overlay,p)
  offlineRoads=OfflineRoads(this)
  lm=getSystemService(LOCATION_SERVICE) as LocationManager
  lm.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000L,0f,this)
 }
 override fun onLocationChanged(location:Location) {
  val now=System.currentTimeMillis()
  speed=if(location.hasSpeed() && location.hasAccuracy() && location.accuracy<=25f) location.speed*3.6 else null
  val cacheDistance=if(roadsLoadedAt>0) distance(location.latitude,location.longitude,lastLat,lastLon) else Double.MAX_VALUE
  val offline=offlineRoads.available()
  val withinCache=roadsLoadedAt>0 && (offline || now-roadsLoadedAt<180000) && cacheDistance<cacheRadius-100
  val match=if(withinCache) RoadMatcher.choose(roads,location,previousRoad) else null
  limit=match?.limit
  roadType=match?.type
  specialZone=match?.special?:false
  if(match!=null) previousRoad=match.id else previousRoad=null
  lastStatus=when { speed==null -> "GPS onnauwkeurig of geen snelheid"; !withinCache -> "Buiten kaartcache"; match==null -> "Geen eenduidige wegmatch"; match.type==null -> "Wegtype onbekend"; else -> "Weg herkend" }
  update()
  getSharedPreferences("diagnostics",MODE_PRIVATE).edit().putString("status",lastStatus).putString("network",lastNetwork)
   .putString("speed",speed?.let { "%.1f km/u".format(java.util.Locale.US,it) }?:"onbekend")
   .putString("limit",limit?.let { "$it km/u" }?:"onbekend")
   .putString("road",match?.id?.toString()?:"geen")
   .putString("accuracy",if(location.hasAccuracy()) "%.0f m".format(java.util.Locale.US,location.accuracy) else "onbekend")
   .putString("cache",if(withinCache) "beschikbaar" else "niet beschikbaar")
   .putLong("updated",now).putLong("request_at",lastRequestAt).putLong("request_duration",lastDuration)
   .putString("server",server).apply()
  // Shared Overpass instances are unsuitable for continuous high-frequency tracking.
  // Keep requests deliberately sparse; unknown is safer than an outdated limit.
  if(!fetching && now-lastQuery>(if(offline) 3000L else 60000L) && (roadsLoadedAt==0L ||
    cacheDistance>cacheRadius-350 || (!offline && now-roadsLoadedAt>150000L))) {
   fetching=true
   lastQuery=now
   lastRequestAt=now
   lastNetwork="Kaartaanvraag bezig"
   val lat=location.latitude;val lon=location.longitude
   val bearing=if(location.hasBearing() && location.hasSpeed() && location.speed>4f) location.bearing.toDouble() else null
   executor.execute {
    val started=System.currentTimeMillis()
    val result=if(offline) try { FetchResult.Success(offlineRoads.query(lat,lon),"offline SQLite") }
       catch(e:Exception) { FetchResult.Error("Offline kaartfout: ${e.javaClass.simpleName}: ${e.message?.take(70)}","offline SQLite") }
      else fetchRoads(lat,lon,bearing)
    handler.post {
     lastDuration=System.currentTimeMillis()-started
     when(result) {
      is FetchResult.Success -> {
       roads=result.roads;lastLat=lat;lastLon=lon;cacheRadius=if(offline) 650.0 else if(bearing==null) 400.0 else 500.0;roadsLoadedAt=System.currentTimeMillis()
       server=result.server
       lastNetwork=if(result.roads.isEmpty()) "Geen wegen gevonden (${result.server})" else "Kaart ontvangen: ${result.roads.size} wegen (${result.server})"
      }
      is FetchResult.Error -> {server=result.server;lastNetwork=result.message}
     }
     fetching=false
    }
   }
  }
 }
 private fun distance(a:Double,b:Double,c:Double,d:Double):Double {
  val x=(a-c)*111000;val y=(b-d)*111000*cos(Math.toRadians(a));return hypot(x,y)
 }
 private sealed class FetchResult {
  data class Success(val roads:List<RoadMatcher.Road>,val server:String):FetchResult()
  data class Error(val message:String,val server:String):FetchResult()
 }
 private fun fetchRoads(lat:Double,lon:Double,bearing:Double?):FetchResult {
  // Limit each request's footprint and retry once on an independent public endpoint.
  val ahead=if(bearing!=null) {
   val rad=Math.toRadians(bearing)
   val futureLat=lat+sin(rad)*300.0/111320.0
   val futureLon=lon+cos(rad)*300.0/(111320.0*cos(Math.toRadians(lat)))
   "way(around:400,$futureLat,$futureLon)[highway][maxspeed];"
  } else ""
  val q="[out:json][timeout:20];(way(around:400,$lat,$lon)[highway][maxspeed];$ahead);out geom;"
  val endpoints=listOf("https://overpass.kumi.systems/api/interpreter","https://overpass.private.coffee/api/interpreter")
  var lastError="Geen reactie"
  var lastServer="nog geen"
  for(endpoint in endpoints) {
   val host=URL(endpoint).host
   lastServer=host
   var conn:HttpURLConnection?=null
   try {
    conn=URL(endpoint).openConnection() as HttpURLConnection
    conn.requestMethod="POST"
    conn.doOutput=true
    conn.connectTimeout=10000
    conn.readTimeout=25000
    conn.setRequestProperty("User-Agent","EuroRaptor/0.9 (personal experimental road matching)")
    conn.setRequestProperty("Content-Type","application/x-www-form-urlencoded; charset=UTF-8")
    conn.outputStream.use { it.write(("data="+java.net.URLEncoder.encode(q,"UTF-8")).toByteArray(Charsets.UTF_8)) }
    val code=conn.responseCode
    if(code !in 200..299) {
     val detail=try { conn.errorStream?.bufferedReader()?.use { it.readText().take(90).replace("\n"," ") } ?: "" } catch(_:Exception) { "" }
     lastError="HTTP $code"+if(detail.isNotBlank()) ": $detail" else ""
    } else {
     val data=conn.inputStream.bufferedReader().use { it.readText() }
     try { return FetchResult.Success(RoadMatcher.parse(data),host) }
     catch(e:Exception) {lastError="Ongeldige kaartdata: ${e.javaClass.simpleName}"}
    }
   } catch(e:SocketTimeoutException) {lastError="Timeout"}
     catch(e:UnknownHostException) {lastError="DNS-fout"}
     catch(e:SSLException) {lastError="TLS-fout"}
     catch(e:Exception) {lastError="${e.javaClass.simpleName}: ${e.message?.take(80)}"}
   finally {conn?.disconnect()}
  }
  return FetchResult.Error("Beide servers mislukt; laatste: $lastError",lastServer)
 }
 private fun update() {
  val capture=getSharedPreferences("screen_probe",MODE_PRIVATE)
  val capturedAt=capture.getLong("candidate_at",0L)
  val captured=capture.getString("candidate","—")?.substringBefore(" ")?.toIntOrNull()
  val lastFrame=capture.getLong("last_frame",0L)
  val now=System.currentTimeMillis()
  val mapsLimit=if(capturedAt>0L && now-capturedAt<5000L &&
    lastFrame>0L && now-lastFrame<4000L &&
    capture.getString("status","")=="Schermbeelden ontvangen") captured else null
  val effectiveLimit=mapsLimit ?: limit
  // Prefer a matched road classification; otherwise infer a provisional category
  // from the speed limit. This is only an estimate, not a legal road classification.
  val estimatedRoadType=roadType ?: when(effectiveLimit) {
   10,20,30,40,50 -> FineCalculator.RoadType.URBAN
   60,70,80,90 -> FineCalculator.RoadType.RURAL
   100,110,120,130 -> null // 100 km/h may be a motorway or non-motorway.
   else -> null
  }
  val result=speed?.let { FineCalculator.calculate(it,effectiveLimit,estimatedRoadType,specialZone) } ?: FineCalculator.Result.Unknown
  getSharedPreferences("diagnostics",MODE_PRIVATE).edit()
   .putString("effective_limit",effectiveLimit?.let { "$it km/u" }?:"—")
   .putString("limit_source",if(mapsLimit!=null) "Google Maps OCR" else if(limit!=null) "Wegenkaart" else "Onbekend")
   .putString("road_class",estimatedRoadType?.name?:"Onbekend")
   .apply()
  val amount=when(result) {
   is FineCalculator.Result.Amount -> "€ ${result.euros}"
   is FineCalculator.Result.Warning -> result.label
   FineCalculator.Result.Unknown -> "€ —"
  }
  val prefs=getSharedPreferences("display",MODE_PRIVATE)
  val lines=mutableListOf(amount)
  if(prefs.getBoolean("show_speed",false))
   lines.add("GPS: "+(speed?.let { "${it.roundToInt()} km/u" } ?: "—"))
  if(prefs.getBoolean("show_limit",false))
   lines.add("Max: "+(effectiveLimit?.let { "$it km/u" } ?: "—"))
  overlay.text=lines.joinToString("\n")

 }
 override fun onDestroy() {lm.removeUpdates(this);wm.removeView(overlay);executor.shutdownNow();super.onDestroy()}
}
