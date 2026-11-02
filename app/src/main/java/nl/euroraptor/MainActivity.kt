package nl.euroraptor

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
 override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState)
  val layout = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(40,60,40,30) }
  layout.addView(TextView(this).apply { text="EuroRaptor — prototype\n\nGroen: € 0. Onbekende limiet: € —.\n\nBoetetarieven zijn nog niet aangesloten: de app toont daarom nog geen boetebedragen."; textSize=19f })
  layout.addView(Button(this).apply { text="1. Toestemmingen"; setOnClickListener {
   if (!Settings.canDrawOverlays(this@MainActivity)) startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName")))
   requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, *if(Build.VERSION.SDK_INT>=33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()),1)
  } })
  layout.addView(Button(this).apply { text="2. Start overlay"; setOnClickListener {
   if (!Settings.canDrawOverlays(this@MainActivity) || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=android.content.pm.PackageManager.PERMISSION_GRANTED) { android.widget.Toast.makeText(this@MainActivity,"Geef eerst locatie- en overlaytoestemming",android.widget.Toast.LENGTH_LONG).show(); return@setOnClickListener }
   startForegroundService(Intent(this@MainActivity,DrivingService::class.java))
  } })
  layout.addView(Button(this).apply { text="Stop overlay"; setOnClickListener { stopService(Intent(this@MainActivity,DrivingService::class.java)) } })
  setContentView(layout)
 }
}
