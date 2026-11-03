package nl.euroraptor

import android.Manifest
import android.app.Activity
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
  diagnostics.text="Diagnostiek ($age)\\nGPS: ${p.getString("speed","—")} | nauwkeurigheid: ${p.getString("accuracy","—")}\\nLimiet: ${p.getString("limit","—")} | weg-ID: ${p.getString("road","—")}\\nCache: ${p.getString("cache","—")}\\nStatus: ${p.getString("status","—")}\\nNetwerk: ${p.getString("network","—")}\\nServer: ${p.getString("server","—")}\\nLaatste aanvraag: $requestAge | duur: $duration ms".replace("\\n","\n")
 }
 override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState)
  val layout = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(40,60,40,30) }
  layout.addView(TextView(this).apply { text="EuroRaptor v0.8 — experimenteel\n\nGroen: € 0. Onbekend: € —. De boetebedragen zijn schattingen."; textSize=19f })
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
