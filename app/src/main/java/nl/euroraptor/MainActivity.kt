package nl.euroraptor

import android.Manifest
import android.app.Activity
import android.database.sqlite.SQLiteDatabase
import java.io.File
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
 private val mapRequest=1200
 override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
  super.onActivityResult(requestCode,resultCode,data)
  if(requestCode!=mapRequest || resultCode!=RESULT_OK) return
  val uri=data?.data?:return
  android.widget.Toast.makeText(this,"Kaart importeren…",android.widget.Toast.LENGTH_SHORT).show()
  Thread {
   val temp=File(filesDir,"roads-import.tmp")
   val target=File(filesDir,"roads.db")
   try {
    contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use { output -> input.copyTo(output) } }
     ?: error("Bestand niet leesbaar")
    val db=SQLiteDatabase.openDatabase(temp.path,null,SQLiteDatabase.OPEN_READONLY)
    try {
     val valid=db.rawQuery("SELECT value FROM metadata WHERE key='schema'",null).use { it.moveToFirst() && it.getString(0)=="1" }
     if(!valid) error("Onbekend databaseformaat")
    } finally {db.close()}
    if(!temp.renameTo(target)) {
     temp.copyTo(target,overwrite=true)
     temp.delete()
    }
    runOnUiThread {android.widget.Toast.makeText(this,"Offline wegenkaart geïmporteerd",android.widget.Toast.LENGTH_LONG).show();showDiagnostics()}
   } catch(e:Exception) {
    temp.delete()
    runOnUiThread {android.widget.Toast.makeText(this,"Import mislukt: ${e.message}",android.widget.Toast.LENGTH_LONG).show()}
   }
  }.start()
 }

 private val handler=Handler(Looper.getMainLooper())
 private lateinit var diagnostics:TextView
 private val refresh=object:Runnable { override fun run() { if(::diagnostics.isInitialized) showDiagnostics();handler.postDelayed(this,2000L) } }
 override fun onResume() { super.onResume();handler.post(refresh) }
 override fun onPause() {handler.removeCallbacks(refresh);super.onPause()}
 private fun showDiagnostics() {
  val p=getSharedPreferences("diagnostics",MODE_PRIVATE)
  val updated=p.getLong("updated",0L)
  val age=if(updated==0L) "nog geen GPS-fix" else "${(System.currentTimeMillis()-updated)/1000} s geleden"
  val request=p.getLong("request_at",0L)
  val requestAge=if(request==0L) "nog nooit" else "${(System.currentTimeMillis()-request)/1000} s geleden"
  val duration=p.getLong("request_duration",0L)
  val offline=OfflineRoads(this).available()
  val maps=getSharedPreferences("maps_probe",MODE_PRIVATE)
  val mapsAt=maps.getLong("last_event",0L)
  val mapsAge=if(mapsAt==0L) "nog geen Maps-signaal" else "${(System.currentTimeMillis()-mapsAt)/1000} s geleden"
  diagnostics.text="Google Maps toegankelijkheid: $mapsAge\\nKnopen: ${maps.getInt("nodes",0)} | getallen (ONGEVERIFIEERD): ${maps.getString("candidates","geen")}\\nDeze getallen zijn NIET de vastgestelde maximumsnelheid.\\n\\nOffline kaart: ${if(offline) "geïnstalleerd" else "niet geïnstalleerd"}\\nDiagnostiek ($age)\\nGPS: ${p.getString("speed","—")} | nauwkeurigheid: ${p.getString("accuracy","—")}\\nLimiet: ${p.getString("limit","—")} | weg-ID: ${p.getString("road","—")}\\nCache: ${p.getString("cache","—")}\\nStatus: ${p.getString("status","—")}\\nNetwerk: ${p.getString("network","—")}\\nServer: ${p.getString("server","—")}\\nLaatste aanvraag: $requestAge | duur: $duration ms".replace("\\n","\n")
 }
 override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState)
  val layout = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(40,60,40,30) }
  layout.addView(TextView(this).apply { text="EuroRaptor v1.1 — experimenteel\n\nGroen: € 0. Onbekend: € —. De boetebedragen zijn schattingen."; textSize=19f })
  val displaySettings=getSharedPreferences("display",MODE_PRIVATE)
  layout.addView(CheckBox(this).apply {
   text="Toon gemeten GPS-snelheid in overlay"
   isChecked=displaySettings.getBoolean("show_speed",false)
   setOnCheckedChangeListener { _, checked -> displaySettings.edit().putBoolean("show_speed",checked).apply() }
  })
  layout.addView(CheckBox(this).apply {
   text="Toon toegestane snelheid in overlay"
   isChecked=displaySettings.getBoolean("show_limit",false)
   setOnCheckedChangeListener { _, checked -> displaySettings.edit().putBoolean("show_limit",checked).apply() }
  })
  layout.addView(TextView(this).apply {text="Boeteschatting: GPS afgerond, minus 3 km/u t/m 100 km/u of 3% daarboven (naar boven afgerond). Geen extra 5%.";textSize=14f})
  layout.addView(Button(this).apply {text="Google Maps uitlezing inschakelen (Toegankelijkheid)";setOnClickListener {
   startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
  }})
  layout.addView(TextView(this).apply {text="Test v1.1: schakel EuroRaptor in onder Toegankelijkheid en start Google Maps-navigatie. De diagnostiek toont mogelijke getallen, nog geen betrouwbare maximumsnelheid.";textSize=14f})
  layout.addView(Button(this).apply {text="Importeer offline wegenkaart (.db)";setOnClickListener {
   startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
    type="*/*";addCategory(Intent.CATEGORY_OPENABLE)
   },mapRequest)
  }})
  layout.addView(Button(this).apply { text="1. Toestemmingen"; setOnClickListener {
   if (!Settings.canDrawOverlays(this@MainActivity)) startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName")))
   requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, *if(Build.VERSION.SDK_INT>=33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()),1)
  } })
  layout.addView(Button(this).apply { text="2. Start overlay"; setOnClickListener {
   if (!Settings.canDrawOverlays(this@MainActivity) || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=android.content.pm.PackageManager.PERMISSION_GRANTED) { android.widget.Toast.makeText(this@MainActivity,"Geef eerst locatie- en overlaytoestemming",android.widget.Toast.LENGTH_LONG).show(); return@setOnClickListener }
   startForegroundService(Intent(this@MainActivity,DrivingService::class.java))
  } })
  layout.addView(Button(this).apply { text="Stop overlay"; setOnClickListener { stopService(Intent(this@MainActivity,DrivingService::class.java)) } })
  diagnostics=TextView(this).apply {textSize=15f;setPadding(0,25,0,0)}
  layout.addView(diagnostics)
  setContentView(layout)
  showDiagnostics()
 }
}
