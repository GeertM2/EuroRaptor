package nl.euroraptor

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
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
 private val callback=object:MediaProjection.Callback() {
  override fun onStop() { stopSelf() }
 }
 override fun onBind(intent:Intent?)=null
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
  if(intent?.action=="STOP") {stopSelf();return START_NOT_STICKY}
  val token=if(Build.VERSION.SDK_INT>=33) intent?.getParcelableExtra("consent",Intent::class.java)
    else @Suppress("DEPRECATION") intent?.getParcelableExtra<Intent>("consent")
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
 override fun onDestroy() {
  display?.release()
  reader?.close()
  projection?.unregisterCallback(callback)
  projection?.stop()
  getSharedPreferences("screen_probe",MODE_PRIVATE).edit().putString("status","Gestopt").apply()
  super.onDestroy()
 }
}
