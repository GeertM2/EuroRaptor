package nl.euroraptor

import android.Manifest
import android.app.Activity
import android.database.sqlite.SQLiteDatabase
import java.io.File
import android.content.Intent
import android.net.Uri
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.text.InputType
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
import android.view.View

class MainActivity : Activity() {
 private val mapRequest=1200
 private val screenRequest=1201
 override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
  super.onActivityResult(requestCode,resultCode,data)
  if(requestCode==screenRequest) {
   if(resultCode==RESULT_OK && data!=null) {
    startForegroundService(Intent(this,ScreenProbeService::class.java).apply {
     putExtra("resultCode",resultCode)
     putExtra("consent",data)
    })
   }
   return
  }
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
  val capture=getSharedPreferences("screen_probe",MODE_PRIVATE)
  diagnostics.text="Gebruikte limiet: ${p.getString("effective_limit","—")} (${p.getString("limit_source","—")})\\nWegcategorie: ${p.getString("road_class","—")}\\nSchermdeeltest: ${capture.getString("status","nog niet gestart")}\\nFrames: ${capture.getInt("frames",0)} | resolutie: ${capture.getString("size","—")}\\nMaximumsnelheid via Maps: ${capture.getString("candidate","—")}\\nStemmen: ${capture.getString("votes","nog geen")}\\nCirkel: ${capture.getString("circle","nog niet getest")}\\nOCR tekst: ${capture.getString("ocr_text","—")}\\nHerkenning: ${capture.getString("recognition","nog niet getest")}\\n\\nOffline kaart: ${if(offline) "geïnstalleerd" else "niet geïnstalleerd"}\\nDiagnostiek ($age)\\nGPS: ${p.getString("speed","—")} | nauwkeurigheid: ${p.getString("accuracy","—")}\\nLimiet: ${p.getString("limit","—")} | weg-ID: ${p.getString("road","—")}\\nCache: ${p.getString("cache","—")}\\nStatus: ${p.getString("status","—")}\\nNetwerk: ${p.getString("network","—")}\\nServer: ${p.getString("server","—")}\\nLaatste aanvraag: $requestAge | duur: $duration ms".replace("\\n","\n")
 }
 override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState)
  val dp=resources.displayMetrics.density
  fun px(n:Int)=(n*dp).toInt()
  val layout=LinearLayout(this).apply {
   orientation=LinearLayout.VERTICAL
   setPadding(px(20),px(18),px(20),px(24))
  }
  val scroller=ScrollView(this).apply {isFillViewport=true;addView(layout)}
  layout.addView(TextView(this).apply {text="EuroRaptor 2.1";textSize=26f;setPadding(0,0,0,px(12))})
  layout.addView(TextView(this).apply {
   text="Boetebedrag op Google Maps\n€ 0 = geen boete  ·  € — = onbekend  ·  € ? = geen vast tarief"
   textSize=16f;setPadding(0,0,0,px(14))
  })
  layout.addView(Button(this).apply {text="1. Toestemmingen";setOnClickListener {
   if(!Settings.canDrawOverlays(this@MainActivity))
    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName")))
   requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
    *if(Build.VERSION.SDK_INT>=33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()),1)
  }})
  layout.addView(Button(this).apply {text="2. Start overlay";setOnClickListener {
   if(!Settings.canDrawOverlays(this@MainActivity) ||
     checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=android.content.pm.PackageManager.PERMISSION_GRANTED) {
    android.widget.Toast.makeText(this@MainActivity,"Geef eerst locatie- en overlaytoestemming",android.widget.Toast.LENGTH_LONG).show()
    return@setOnClickListener
   }
   startForegroundService(Intent(this@MainActivity,DrivingService::class.java))
  }})
  layout.addView(Button(this).apply {text="3. Start Maps-herkenning";setOnClickListener {
   val manager=getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
   startActivityForResult(manager.createScreenCaptureIntent(),screenRequest)
  }})
  layout.addView(TextView(this).apply {
   text="Kies Entire screen en open daarna Google Maps. Schermdeling blijft zichtbaar in Android."
   textSize=13f;setPadding(0,px(6),0,px(10))
  })
  layout.addView(Button(this).apply {text="Stop alles";setOnClickListener {
   stopService(Intent(this@MainActivity,ScreenProbeService::class.java))
   stopService(Intent(this@MainActivity,DrivingService::class.java))
   getSharedPreferences("test_mode",MODE_PRIVATE).edit().putBoolean("enabled",false).apply()
  }})
  val displaySettings=getSharedPreferences("display",MODE_PRIVATE)
  layout.addView(CheckBox(this).apply {
   text="GPS-snelheid tonen"
   isChecked=displaySettings.getBoolean("show_speed",false)
   setOnCheckedChangeListener { _,v->displaySettings.edit().putBoolean("show_speed",v).apply() }
  })
  layout.addView(CheckBox(this).apply {
   text="Maximumsnelheid tonen"
   isChecked=displaySettings.getBoolean("show_limit",false)
   setOnCheckedChangeListener { _,v->displaySettings.edit().putBoolean("show_limit",v).apply() }
  })
  layout.addView(CheckBox(this).apply {
   text="Diagnostiek in overlay tonen (tijdelijk)"
   isChecked=displaySettings.getBoolean("show_diagnostics_overlay",false)
   setOnCheckedChangeListener { _,v->displaySettings.edit().putBoolean("show_diagnostics_overlay",v).apply() }
  })
  val testPrefs=getSharedPreferences("test_mode",MODE_PRIVATE)
  layout.addView(TextView(this).apply {
   text="TESTMODUS (stilstaand, zonder Maps)"
   textSize=17f
   setPadding(0,px(10),0,px(5))
  })
  val testInputs=LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL}
  val testSpeed=EditText(this).apply {
   hint="GPS km/u"
   inputType=InputType.TYPE_CLASS_NUMBER
   setSingleLine(true)
   setText(testPrefs.getInt("speed",50).toString())
   layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f)
  }
  val testLimit=EditText(this).apply {
   hint="Max km/u"
   inputType=InputType.TYPE_CLASS_NUMBER
   setSingleLine(true)
   setText(testPrefs.getInt("limit",30).toString())
   layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f)
  }
  testInputs.addView(testSpeed)
  testInputs.addView(testLimit)
  layout.addView(testInputs)
  val testStatus=TextView(this).apply {textSize=14f}
  fun updateTestStatus() {
   val speedValue=testPrefs.getInt("speed",50)
   val limitValue=testPrefs.getInt("limit",30)
   val correction=if(speedValue<=100) 3 else kotlin.math.ceil(speedValue*0.03).toInt()
   val excess=speedValue-correction-limitValue
   val fine=FineCalculator.calculateWorstCase(speedValue.toDouble(),limitValue)
   val amount=when(fine) {
    is FineCalculator.Result.Amount -> "€ ${fine.euros}"
    is FineCalculator.Result.Warning -> fine.label
    FineCalculator.Result.Unknown -> "€ —"
   }
   testStatus.text="Test ${if(testPrefs.getBoolean("enabled",false)) "AAN" else "UIT"}: $speedValue - $correction = ${speedValue-correction} km/u | +$excess km/u | $amount"
  }
  layout.addView(testStatus)
  layout.addView(Button(this).apply {text="Start testmodus + overlay";setOnClickListener {
   val testSpeedValue=testSpeed.text.toString().toIntOrNull()
   val testLimitValue=testLimit.text.toString().toIntOrNull()
   if(testSpeedValue==null || testSpeedValue !in 0..300 || testLimitValue==null || testLimitValue !in 1..150) {
    android.widget.Toast.makeText(this@MainActivity,"Vul geldige snelheden in (0–300 en 1–150)",android.widget.Toast.LENGTH_LONG).show()
    return@setOnClickListener
   }
   if(!Settings.canDrawOverlays(this@MainActivity) ||
     checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=android.content.pm.PackageManager.PERMISSION_GRANTED) {
    android.widget.Toast.makeText(this@MainActivity,"Geef eerst locatie- en overlaytoestemming",android.widget.Toast.LENGTH_LONG).show()
    return@setOnClickListener
   }
   testPrefs.edit().putBoolean("enabled",true).putInt("speed",testSpeedValue).putInt("limit",testLimitValue).apply()
   startForegroundService(Intent(this@MainActivity,DrivingService::class.java))
   updateTestStatus()
  }})
  layout.addView(Button(this).apply {text="Stop testmodus (terug naar live)";setOnClickListener {
   testPrefs.edit().putBoolean("enabled",false).apply()
   updateTestStatus()
  }})
  updateTestStatus()
  val details=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;visibility=View.GONE}
  layout.addView(Button(this).apply {text="Diagnostiek en extra opties ▼";setOnClickListener {
   details.visibility=if(details.visibility==View.VISIBLE) View.GONE else View.VISIBLE
   text=if(details.visibility==View.VISIBLE) "Diagnostiek en extra opties ▲" else "Diagnostiek en extra opties ▼"
  }})
  layout.addView(details)
  diagnostics=TextView(this).apply {textSize=13f;setPadding(0,px(10),0,px(10))}
  details.addView(diagnostics)
  details.addView(Button(this).apply {text="Importeer offline wegenkaart (.db)";setOnClickListener {
   startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
    type="*/*";addCategory(Intent.CATEGORY_OPENABLE)
   },mapRequest)
  }})
  details.addView(TextView(this).apply {
   text="Boetebedragen zijn schattingen. GPS is geen gecertificeerde snelheidsmeting. Het hoogste standaardtarief per wegcategorie wordt gebruikt. Schermbeelden worden lokaal geanalyseerd en niet opgeslagen."
   textSize=13f
  })
  setContentView(scroller)
  showDiagnostics()
 }

}
