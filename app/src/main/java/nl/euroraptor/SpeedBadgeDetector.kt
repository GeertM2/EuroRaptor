package nl.euroraptor

import kotlin.math.cos
import kotlin.math.sin

/** Pixel-only badge detector shared by live screen capture and JVM screenshot tests. */
object SpeedBadgeDetector {
 data class Circle(val x:Int,val y:Int,val radius:Int,val score:Double)
 fun find(width:Int,height:Int,pixel:(Int,Int)->Int):Circle? {
  val right=(width*0.28).toInt()
  val top=(height*0.30).toInt()
  val bottom=(height*0.985).toInt()
  fun red(c:Int):Boolean {
   val r=(c ushr 16) and 255;val g=(c ushr 8) and 255;val b=c and 255
   return r>140 && r>g*1.55 && r>b*1.4 && g<160
  }
  fun white(c:Int):Boolean = ((c ushr 16) and 255)>155 &&
    ((c ushr 8) and 255)>155 && (c and 255)>155
  var best:Circle?=null
  val radii=(18..65 step 3).map { (width*it/1000.0).toInt() }.distinct().filter { it>=7 }
  for(r in radii) {
   if(r<1 || top+r>=bottom-r || right<=2*r) continue
   val step=(r/5).coerceAtLeast(3)
   for(cy in top+r until bottom-r step step) for(cx in r until right-r step step) {
    var ring=0;var inside=0
    for(i in 0 until 32) {
     val a=i*Math.PI/16.0
     val dx=(cos(a)*r).toInt();val dy=(sin(a)*r).toInt()
     if(red(pixel(cx+dx,cy+dy))) ring++
     if(white(pixel(cx+(dx*0.60).toInt(),cy+(dy*0.60).toInt()))) inside++
    }
    val score=ring/32.0*0.8+inside/32.0*0.2
    if(ring>=15 && inside>=20 && score>(best?.score?:0.0))
     best=Circle(cx,cy,r,score)
   }
  }
  return best
 }
}
