package nl.euroraptor

import android.location.Location
import org.json.JSONObject
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.ZoneId
import kotlin.math.*

/** Conservative offline matching against a small cached Overpass road graph. */
object RoadMatcher {
 data class Pt(val lat:Double,val lon:Double)
 data class Road(val id:Long,val nodes:List<Pt>,val tags:JSONObject)
 data class Match(val id:Long,val limit:Int,val type:FineCalculator.RoadType?,val special:Boolean,val distance:Double)
 fun parse(json:String):List<Road> {
  val a=JSONObject(json).getJSONArray("elements")
  val roads=ArrayList<Road>()
  for(i in 0 until a.length()) {
   val w=a.getJSONObject(i)
   val g=w.optJSONArray("geometry")?:continue
   val tags=w.optJSONObject("tags")?:continue
   if(g.length()<2) continue
   val nodes=(0 until g.length()).map { j -> val p=g.getJSONObject(j);Pt(p.getDouble("lat"),p.getDouble("lon")) }
   roads.add(Road(w.getLong("id"),nodes,tags))
  }
  return roads
 }
 private fun metres(a:Pt,b:Pt):Pair<Double,Double> {
  val x=(b.lon-a.lon)*111320*cos(Math.toRadians(a.lat))
  val y=(b.lat-a.lat)*111320
  return Pair(x,y)
 }
 private fun delta(a:Double,b:Double)=abs((a-b+540)%360-180)
 private fun limit(tags:JSONObject):Int? {
  if(tags.optString("maxspeed:variable") !in listOf("","no")) return null
  var result=tags.optString("maxspeed").toIntOrNull()
  val conditional=tags.optString("maxspeed:conditional")
  if(conditional.isNotBlank()) {
   val now=ZonedDateTime.now(ZoneId.of("Europe/Amsterdam"))
   // Only simple daily time intervals; fail closed for unsupported conditions.
   val pieces=conditional.split(";")
   for(piece in pieces) {
    val match=Regex("""^\s*(\d{1,3})\s*@\s*\(?\s*(\d\d:\d\d)\s*-\s*(\d\d:\d\d)\s*\)?\s*$""").matchEntire(piece)?:return null
    val start=try { LocalTime.parse(match.groupValues[2]) } catch(e:Exception){return null}
    val end=try { LocalTime.parse(match.groupValues[3]) } catch(e:Exception){return null}
    val active=if(start<=end) now.toLocalTime()>=start && now.toLocalTime()<end
               else now.toLocalTime()>=start || now.toLocalTime()<end
    if(active) result=match.groupValues[1].toIntOrNull()
   }
  }
  return result?.takeIf { it in 5..130 }
 }
 fun choose(roads:List<Road>,location:Location,previousId:Long?):Match? {
  if(!location.hasAccuracy() || location.accuracy>25f) return null
  val here=Pt(location.latitude,location.longitude)
  val candidates=ArrayList<Pair<Match,Double>>()
  for(road in roads) {
   val highway=road.tags.optString("highway")
   if(highway !in setOf("motorway","motorway_link","trunk","trunk_link","primary","primary_link","secondary","secondary_link","tertiary","tertiary_link","residential","unclassified","living_street","service")) continue
   val max=limit(road.tags)?:continue
   var best=Double.MAX_VALUE
   var bearing=0.0
   for(i in 0 until road.nodes.size-1) {
    val (x,y)=metres(here,road.nodes[i])
    val (dx,dy)=metres(road.nodes[i],road.nodes[i+1])
    val len=dx*dx+dy*dy
    if(len<0.01) continue
    val t=(-(x*dx+y*dy)/len).coerceIn(0.0,1.0)
    val dist=hypot(x+t*dx,y+t*dy)
    if(dist<best) {best=dist;bearing=(Math.toDegrees(atan2(dx,dy))+360)%360}
   }
   if(best>max(12.0,location.accuracy.toDouble()+8.0)) continue
   val one=road.tags.optString("oneway")
   val headingPenalty=if(location.hasBearing() && location.hasSpeed() && location.speed>3) {
    val d=delta(location.bearing.toDouble(),bearing)
    val deviation=if(one=="yes" || highway=="motorway" || highway=="motorway_link") d
                  else if(one=="-1") abs(180-d) else min(d,abs(180-d))
    if(deviation>75) continue
    deviation*0.16
   } else 0.0
   val zone=road.tags.optString("zone:traffic")+" "+road.tags.optString("source:maxspeed")+" "+road.tags.optString("zone:maxspeed")
   val type=when {
    highway=="motorway" || highway=="motorway_link" -> FineCalculator.RoadType.MOTORWAY
    zone.contains("NL:urban",true) -> FineCalculator.RoadType.URBAN
    zone.contains("NL:rural",true) -> FineCalculator.RoadType.RURAL
    else -> null
   }
   val special=highway=="living_street" || road.tags.optString("zone:maxspeed").contains("zone30",true)
   val match=Match(road.id,max,type,special,best)
   candidates.add(match to (best+headingPenalty-if(road.id==previousId) 4.0 else 0.0))
  }
  val sorted=candidates.sortedBy { it.second }
  val winner=sorted.firstOrNull()?:return null
  // If nearby roads have conflicting limits, do not claim certainty.
  if(sorted.drop(1).any { it.second-winner.second<8 && (it.first.limit!=winner.first.limit || it.first.type!=winner.first.type) }) return null
  return winner.first
 }
}
