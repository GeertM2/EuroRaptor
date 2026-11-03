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
 private val server="overpass-api.de"
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
  val now=System.currentTimeMillis()
  speed=if(location.hasSpeed() && location.hasAccuracy() && location.accuracy<=25f) location.speed*3.6 else null
  val cacheDistance=if(roadsLoadedAt>0) distance(location.latitude,location.longitude,lastLat,lastLon) else Double.MAX_VALUE
  val withinCache=roadsLoadedAt>0 && now-roadsLoadedAt<180000 && cacheDistance<cacheRadius-100
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
  if(!fetching && now-lastQuery>60000L && (roadsLoadedAt==0L ||
    cacheDistance>cacheRadius-350 || now-roadsLoadedAt>150000L)) {
   fetching=true
   lastQuery=now
   lastRequestAt=now
   lastNetwork="Kaartaanvraag bezig"
   val lat=location.latitude;val lon=location.longitude
   val bearing=if(location.hasBearing() && location.hasSpeed() && location.speed>4f) location.bearing.toDouble() else null
   executor.execute {
    val started=System.currentTimeMillis()
    val result=fetchRoads(lat,lon,bearing)
    handler.post {
     lastDuration=System.currentTimeMillis()-started
     when(result) {
      is FetchResult.Success -> {
       roads=result.roads;lastLat=lat;lastLon=lon;cacheRadius=if(bearing==null) 900.0 else 1100.0;roadsLoadedAt=System.currentTimeMillis()
       lastNetwork=if(result.roads.isEmpty()) "Geen wegen gevonden" else "Kaart ontvangen: ${result.roads.size} wegen"
      }
      is FetchResult.Error -> lastNetwork=result.message
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
  data class Success(val roads:List<RoadMatcher.Road>):FetchResult()
  data class Error(val message:String):FetchResult()
 }
 private fun fetchRoads(lat:Double,lon:Double,bearing:Double?):FetchResult {
  var conn:HttpURLConnection?=null
  return try {
   val ahead=if(bearing!=null) {
    val rad=Math.toRadians(bearing)
    val futureLat=lat+sin(rad)*550.0/111320.0
    val futureLon=lon+cos(rad)*550.0/(111320.0*cos(Math.toRadians(lat)))
    "way(around:700,$futureLat,$futureLon)[highway][maxspeed];"
   } else ""
   val q="[out:json][timeout:15];(way(around:650,$lat,$lon)[highway][maxspeed];$ahead);out geom;"
   conn=URL("https://overpass-api.de/api/interpreter").openConnection() as HttpURLConnection
   conn.requestMethod="POST"
   conn.doOutput=true
   conn.connectTimeout=15000
   conn.readTimeout=20000
   conn.setRequestProperty("User-Agent","EuroRaptor/0.7 (personal experimental road matching)")
   conn.setRequestProperty("Content-Type","application/x-www-form-urlencoded; charset=UTF-8")
   conn.outputStream.use { it.write(("data="+java.net.URLEncoder.encode(q,"UTF-8")).toByteArray(Charsets.UTF_8)) }
   val code=conn.responseCode
   if(code !in 200..299) {
    val detail=try { conn.errorStream?.bufferedReader()?.use { it.readText().take(180).replace("\n"," ") } ?: "" } catch(_:Exception) { "" }
    FetchResult.Error("HTTP $code"+if(detail.isNotBlank()) ": $detail" else "")
   } else {
    val data=conn.inputStream.bufferedReader().use { it.readText() }
    try { FetchResult.Success(RoadMatcher.parse(data)) }
    catch(e:Exception) { FetchResult.Error("Ongeldige kaartdata: ${e.javaClass.simpleName}: ${e.message?.take(100)}") }
   }
  } catch(e:SocketTimeoutException) { FetchResult.Error("Timeout: ${e.message?.take(90)}") }
    catch(e:UnknownHostException) { FetchResult.Error("DNS-fout: ${e.message?.take(90)}") }
    catch(e:SSLException) { FetchResult.Error("TLS-fout: ${e.message?.take(90)}") }
    catch(e:Exception) { FetchResult.Error("${e.javaClass.simpleName}: ${e.message?.take(120)}") }
  finally { conn?.disconnect() }
 }
 private fun update() {
  val result=speed?.let { FineCalculator.calculate(it,limit,roadType,specialZone) } ?: FineCalculator.Result.Unknown
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
   lines.add("Max: "+(limit?.let { "$it km/u" } ?: "—"))
  overlay.text=lines.joinToString("\n")
  overlay.setBackgroundColor(when(result) {
   is FineCalculator.Result.Amount -> if(result.euros==0) Color.rgb(0,110,40) else Color.rgb(160,35,25)
   is FineCalculator.Result.Warning -> Color.rgb(175,30,25)
   FineCalculator.Result.Unknown -> Color.DKGRAY
  })
 }
 override fun onDestroy() {lm.removeUpdates(this);wm.removeView(overlay);executor.shutdownNow();super.onDestroy()}
}
