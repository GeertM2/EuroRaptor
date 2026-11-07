package nl.euroraptor

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.hypot

/** Synthetic screenshot fixtures: same pixel detector as live MediaProjection. */
class SpeedBadgeDetectorTest {
 private val w=400
 private val h=900
 private fun screenshot(badges:List<Pair<Int,Int>>, decoy:Boolean=false):IntArray {
  val pixels=IntArray(w*h) { 0xffeeeeee.toInt() }
  for((cx,cy) in badges) for(y in (cy-30).coerceAtLeast(0) until (cy+31).coerceAtMost(h))
   for(x in (cx-30).coerceAtLeast(0) until (cx+31).coerceAtMost(w)) {
    val d=hypot((x-cx).toDouble(),(y-cy).toDouble())
    if(d in 20.0..28.0) pixels[y*w+x]=0xffd02020.toInt()
    else if(d<20) pixels[y*w+x]=0xffffffff.toInt()
   }
  if(decoy) for(y in 500..560) for(x in 250..310) pixels[y*w+x]=0xffd02020.toInt()
  return pixels
 }
 private fun find(p:IntArray)=SpeedBadgeDetector.find(w,h) { x,y -> p[y*w+x] }
 @Test fun badgeHigherOnScreen() {
  val c=find(screenshot(listOf(65 to 420)))
  assertNotNull(c)
  assertTrue(kotlin.math.abs(c!!.y-420)<18)
 }
 @Test fun badgeLowerOnScreen() {
  val c=find(screenshot(listOf(65 to 780)))
  assertNotNull(c)
  assertTrue(kotlin.math.abs(c!!.y-780)<18)
 }
 @Test fun unrelatedRedElementIsIgnored() {
  assertNull(find(screenshot(emptyList(),decoy=true)))
 }
 @Test fun badgeOutsideLeftStripIsIgnored() {
  assertNull(find(screenshot(listOf(300 to 650))))
 }
}
