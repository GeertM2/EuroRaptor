package nl.euroraptor

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Diagnostic probe only. Never assumes a numeric label is a speed limit.
 * Reads Google Maps accessibility nodes, not screenshots, and stores counts
 * and short numeric candidates locally; does not upload accessibility content.
 */
class MapsAccessibilityProbe : AccessibilityService() {
 override fun onAccessibilityEvent(event:AccessibilityEvent?) {
  if(event?.packageName?.toString()!="com.google.android.apps.maps") return
  val root=rootInActiveWindow?:return
  val numbers=linkedSetOf<Int>()
  var visited=0
  val queue=ArrayDeque<AccessibilityNodeInfo>()
  queue.add(root)
  while(queue.isNotEmpty() && visited<400) {
   val node=queue.removeFirst()
   visited++
   for(value in listOf(node.text?.toString(),node.contentDescription?.toString())) {
    if(value==null) continue
    // Diagnostic candidates only: may be street numbers, ETA or GPS speed.
    val pattern=Regex("(?<![0-9])(?:[1-9][0-9]?|1[0-4][0-9]|150)(?![0-9])")
    pattern.findAll(value).forEach { match ->
     val n=match.value.toIntOrNull()
     if(n!=null && n in 5..150) numbers.add(n)
    }
   }
   for(i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
  }
  getSharedPreferences("maps_probe",MODE_PRIVATE).edit()
   .putLong("last_event",System.currentTimeMillis())
   .putInt("nodes",visited)
   .putString("candidates",numbers.sorted().take(25).joinToString(", ").ifBlank { "geen" })
   .putString("event",event.eventType.toString())
   .apply()
 }
 override fun onInterrupt() {}
}
