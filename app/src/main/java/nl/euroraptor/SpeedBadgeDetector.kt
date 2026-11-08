package nl.euroraptor

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.roundToInt

/** Pixel-only Google Maps speed-limit sign detector, also used by JVM fixture tests. */
object SpeedBadgeDetector {
 data class Circle(val x:Int,val y:Int,val radius:Int,val score:Double)
 fun find(width:Int,height:Int,pixel:(Int,Int)->Int):Circle? {
  if(width<100 || height<200) return null
  val right=(width*0.31).toInt()
  val top=(height*0.28).toInt()
  val bottom=(height*0.97).toInt()
  fun red(c:Int):Boolean {
   val r=(c ushr 16) and 255;val g=(c ushr 8) and 255;val b=c and 255
   return r>125 && r>g*1.40 && r>b*1.30 && g<175
  }
  fun pale(c:Int):Boolean {
   val r=(c ushr 16) and 255;val g=(c ushr 8) and 255;val b=c and 255
   return r>155 && g>155 && b>155 && (maxOf(r,g,b)-minOf(r,g,b))<75
  }
  var best:Circle?=null
  // Search 2.3%-7.5% of screen width: accommodates Google Maps layout and scaling.
  val radii=(23..75 step 3).map { (width*it/1000.0).roundToInt() }.distinct().filter { it>=7 }
  for(r in radii) {
   if(top+r>=bottom-r || right<=2*r) continue
   val step=(r/6).coerceAtLeast(3)
   for(cy in top+r until bottom-r step step) for(cx in r until right-r step step) {
    var ring=0;var inside=0;var exterior=0
    for(i in 0 until 32) {
     val a=i*Math.PI/16.0
     val dx=cos(a);val dy=sin(a)
     if(red(pixel(cx+(dx*r).roundToInt(),cy+(dy*r).roundToInt()))) ring++
     if(pale(pixel(cx+(dx*r*0.48).roundToInt(),cy+(dy*r*0.48).roundToInt()))) inside++
     if(red(pixel(cx+(dx*r*1.30).roundToInt().coerceIn(-cx,width-cx-1),
                  cy+(dy*r*1.30).roundToInt().coerceIn(-cy,height-cy-1)))) exterior++
    }
    // The outer ring must be red and the interior light, but outside should not
    // also be red. This prevents mistaking a large red UI element for a sign.
    val score=ring/32.0*0.76+inside/32.0*0.24-exterior/32.0*0.12
    if(ring>=12 && inside>=17 && score>(best?.score?:0.0))
     best=Circle(cx,cy,r,score)
   }
  }
  return best
 }
}
