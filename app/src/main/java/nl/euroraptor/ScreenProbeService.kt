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
 private var previous:Int?=null
 private var repeats=0
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
  // Search lower-left only, where Google Maps displays the speed-limit badge.
  val right=(w*0.24).toInt()
  val top=(h*0.65).toInt()
  val bottom=(h*0.90).toInt()
  var minX=right;var maxX=0;var minY=bottom;var maxY=0;var count=0
  for(y in top until bottom step 3) for(x in 0 until right step 3) {
   val c=bitmap.getPixel(x,y)
   if(Color.red(c)>150 && Color.red(c)>Color.green(c)*1.6 &&
      Color.red(c)>Color.blue(c)*1.4 && Color.green(c)<155) {
    minX=minOf(minX,x);maxX=maxOf(maxX,x)
    minY=minOf(minY,y);maxY=maxOf(maxY,y);count++
   }
  }
  val dx=maxX-minX;val dy=maxY-minY
  val valid=count>20 && dx in (w*0.04).toInt()..(w*0.17).toInt() &&
    kotlin.math.abs(dx-dy)<dx*0.35
  if(!valid) {
   bitmap.recycle();previous=null;repeats=0
   getSharedPreferences("screen_probe",MODE_PRIVATE).edit()
    .putString("recognition","Geen rode snelheidsbord-cirkel").putString("candidate","—").apply()
   scanning=false;return
  }
  val inset=(dx*0.22).toInt().coerceAtLeast(2)
  val x=minX+inset;val y=minY+inset
  val cw=(dx-2*inset).coerceAtLeast(1)
  val ch=(dy-2*inset).coerceAtLeast(1)
  val crop=Bitmap.createBitmap(bitmap,x,y,cw,ch)
  bitmap.recycle()
  val scaled=Bitmap.createScaledBitmap(crop,cw*4,ch*4,false)
  crop.recycle()
  recognizer.process(InputImage.fromBitmap(scaled,0))
   .addOnSuccessListener { result ->
    val n=result.text.trim().replace(" ","").toIntOrNull()
    if(n!=null && n in 10..130 && n%10==0) {
     repeats=if(n==previous) repeats+1 else 1
     previous=n
    } else {previous=null;repeats=0}
    val confirmed=repeats>=2
    getSharedPreferences("screen_probe",MODE_PRIVATE).edit()
     .putString("candidate",if(confirmed) "$n km/u" else "—")
     .putString("recognition",if(confirmed) "Bord herkend (alleen test)" else "OCR: "+result.text.take(25))
     .putLong("candidate_at",if(confirmed) System.currentTimeMillis() else 0L).apply()
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
  getSharedPreferences("screen_probe",MODE_PRIVATE).edit().putString("status","Gestopt").apply()
  super.onDestroy()
 }
}
