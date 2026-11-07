package nl.euroraptor

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Bitmap
import android.graphics.Color
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics

/**
 * Opt-in screen-sharing diagnostic. Counts received frames only.
 * Does not store screenshots, inspect text, or transmit screen contents.
 */
class ScreenProbeService:Service() {
 private var projection:MediaProjection?=null
 private var display:VirtualDisplay?=null
 private var reader:ImageReader?=null
 private val handler=Handler(Looper.getMainLooper())
 private var frames=0
 private val recognizer=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
 private var scanning=false
 private var lastScan=0L
 private val observations=ArrayDeque<Pair<Long,Int>>()
 private var confirmedLimit:Int?=null
 private var lastConfirmedAt=0L
 private val voteWindowMs=5000L
 private val holdMs=4500L
 private fun recordObservation(value:Int?) {
  val now=System.currentTimeMillis()
  while(observations.isNotEmpty() && now-observations.first().first>voteWindowMs)
   observations.removeFirst()
  if(value!=null) observations.addLast(now to value)
  val winner=observations.groupingBy { it.second }.eachCount().maxByOrNull { it.value }
  if(value!=null && winner!=null && winner.key==value && winner.value>=3) {
   confirmedLimit=winner.key
   lastConfirmedAt=now
  }
  if(now-lastConfirmedAt>holdMs) confirmedLimit=null
  val confirmed=confirmedLimit
  getSharedPreferences("screen_probe",MODE_PRIVATE).edit()
   .putString("candidate",confirmed?.let { "$it km/u" } ?: "—")
   .putLong("candidate_at",if(confirmed!=null) lastConfirmedAt else 0L)
   .putString("votes",winner?.let { "${it.key} km/u: ${it.value} waarnemingen / 5 s" } ?: "0 waarnemingen / 5 s")
   .apply()
 }
 private val callback=object:MediaProjection.Callback() {
  override fun onStop() { stopSelf() }
 }
 override fun onBind(intent:Intent?)=null
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
  if(intent?.action=="STOP") {stopSelf();return START_NOT_STICKY}
  val token=if(Build.VERSION.SDK_INT>=33) intent?.getParcelableExtra("consent",Intent::class.java)
    else intent?.getParcelableExtra<Intent>("consent")
  val code=intent?.getIntExtra("resultCode",Activity.RESULT_CANCELED)?:Activity.RESULT_CANCELED
  if(code!=Activity.RESULT_OK || token==null) {stopSelf();return START_NOT_STICKY}
  val channel=NotificationChannel("screen_probe","Schermdeeltest",NotificationManager.IMPORTANCE_LOW)
  getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
  val notification=Notification.Builder(this,"screen_probe")
   .setContentTitle("EuroRaptor schermdeeltest")
   .setContentText("Schermdeling actief; geen beelden opgeslagen")
   .setSmallIcon(android.R.drawable.ic_menu_view)
   .setOngoing(true).build()
  if(Build.VERSION.SDK_INT>=29) startForeground(2,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
  else startForeground(2,notification)
  try {
   val manager=getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
   projection=manager.getMediaProjection(code,token)
   projection?.registerCallback(callback,handler)
   val metrics=DisplayMetrics()
   @Suppress("DEPRECATION")
   (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.getRealMetrics(metrics)
   val width=metrics.widthPixels
   val height=metrics.heightPixels
   reader=ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,2)
   reader?.setOnImageAvailableListener({ source ->
    source.acquireLatestImage()?.use {
     frames++
     if(!scanning && System.currentTimeMillis()-lastScan>1800L) {
      scanning=true
      lastScan=System.currentTimeMillis()
      try { recognize(it) } catch(e:Exception) {
       scanning=false
       getSharedPreferences("screen_probe",MODE_PRIVATE).edit().putString("recognition","Scan fout: "+e.javaClass.simpleName).apply()
      }
     }
     getSharedPreferences("screen_probe",MODE_PRIVATE).edit()
      .putString("status","Schermbeelden ontvangen")
      .putInt("frames",frames)
      .putLong("last_frame",System.currentTimeMillis())
      .putString("size","${it.width} x ${it.height}")
      .apply()
    }
   },handler)
   display=projection?.createVirtualDisplay("EuroRaptor screen diagnostic",width,height,metrics.densityDpi,
     DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader?.surface,null,handler)
   getSharedPreferences("screen_probe",MODE_PRIVATE).edit().putString("status","Schermdeling gestart").apply()
  } catch(e:Exception) {
   getSharedPreferences("screen_probe",MODE_PRIVATE).edit()
    .putString("status","Fout: ${e.javaClass.simpleName}: ${e.message?.take(80)}").apply()
   stopSelf()
  }
  return START_NOT_STICKY
 }

 private fun recognize(image:android.media.Image) {
  val plane=image.planes[0]
  val w=image.width;val h=image.height
  val bitmap=Bitmap.createBitmap(plane.rowStride/plane.pixelStride,h,Bitmap.Config.ARGB_8888)
  bitmap.copyPixelsFromBuffer(plane.buffer)
  val found=SpeedBadgeDetector.find(w,h) { x,y -> bitmap.getPixel(x,y) }
  val cxBest=found?.x?:0
  val cyBest=found?.y?:0
  val radiusBest=found?.radius?:0
  val best=found?.score?:0.0
  if(radiusBest==0) {
   bitmap.recycle();recordObservation(null)
   getSharedPreferences("screen_probe",MODE_PRIVATE).edit()
    .putString("circle","Niet gevonden (zoekzone links: 30–98,5% hoogte)")
    .putString("ocr_text","—")
    .putString("recognition","Geen rode cirkel met lichte binnenkant").apply()
   scanning=false;return
  }
  getSharedPreferences("screen_probe",MODE_PRIVATE).edit()
   .putString("circle","Gevonden x="+cxBest+" y="+cyBest+" r="+radiusBest+" score="+best).apply()
  val minX=cxBest-radiusBest;val minY=cyBest-radiusBest
  val maxX=cxBest+radiusBest;val maxY=cyBest+radiusBest
  val dx=maxX-minX;val dy=maxY-minY
  val inset=(dx*0.10).toInt().coerceAtLeast(1)
  val x=minX+inset;val y=minY+inset
  val cw=(dx-2*inset).coerceAtLeast(1)
  val ch=(dy-2*inset).coerceAtLeast(1)
  val crop=Bitmap.createBitmap(bitmap,x,y,cw,ch)
  bitmap.recycle()
  val scaled=Bitmap.createScaledBitmap(crop,cw*4,ch*4,false)
  crop.recycle()
  recognizer.process(InputImage.fromBitmap(scaled,0))
   .addOnSuccessListener { result ->
    val raw=result.text.trim()
    val n=Regex("""\b(10|20|30|40|50|60|70|80|90|100|110|120|130)\b""").find(raw)?.value?.toIntOrNull()
    getSharedPreferences("screen_probe",MODE_PRIVATE).edit().putString("ocr_text",raw.ifBlank { "(leeg)" }.take(60)).apply()
    recordObservation(n)
    val confirmed=confirmedLimit
    getSharedPreferences("screen_probe",MODE_PRIVATE).edit()
     .putString("recognition",when {
      confirmed!=null -> "Bevestigd: $confirmed km/u (diagnostisch)"
      n!=null -> "OCR: $n, wachten op 3 waarnemingen"
      else -> "Cirkel gevonden, OCR zonder geldige limiet"
     }).apply()
   }
   .addOnFailureListener { e ->
    getSharedPreferences("screen_probe",MODE_PRIVATE).edit().putString("recognition","OCR: "+e.javaClass.simpleName).apply()
   }
   .addOnCompleteListener { scaled.recycle();scanning=false }
 }

 override fun onDestroy() {
  display?.release()
  reader?.close()
  projection?.unregisterCallback(callback)
  projection?.stop()
  recognizer.close()
  getSharedPreferences("screen_probe",MODE_PRIVATE).edit()
   .putString("status","Gestopt").putString("candidate","—")
   .putLong("candidate_at",0L).putString("votes","Schermdeling gestopt").apply()
  super.onDestroy()
 }
}
