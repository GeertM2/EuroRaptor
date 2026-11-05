package nl.euroraptor

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Local read-only OSM road index, imported separately from the APK. */
class OfflineRoads(context:Context) {
 private val file=File(context.filesDir,"roads.db")
 fun available()=file.exists() && file.length()>0
 fun query(lat:Double,lon:Double,radiusMetres:Double=650.0):List<RoadMatcher.Road> {
  if(!available()) return emptyList()
  val dLat=radiusMetres/111320.0
  val dLon=radiusMetres/(111320.0*kotlin.math.cos(Math.toRadians(lat)))
  val db=SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READONLY)
  try {
   val sql="""SELECT r.id,r.geometry,r.tags FROM road_index i
      JOIN roads r ON r.id=i.id
      WHERE i.minlat<=? AND i.maxlat>=? AND i.minlon<=? AND i.maxlon>=?
      LIMIT 2000"""
   val result=ArrayList<RoadMatcher.Road>()
   db.rawQuery(sql,arrayOf((lat+dLat).toString(),(lat-dLat).toString(),
       (lon+dLon).toString(),(lon-dLon).toString())).use { cursor ->
    while(cursor.moveToNext()) {
     val geometry=JSONArray(cursor.getString(1))
     val nodes=ArrayList<RoadMatcher.Pt>()
     for(i in 0 until geometry.length()) {
      val p=geometry.getJSONArray(i)
      nodes.add(RoadMatcher.Pt(p.getDouble(0),p.getDouble(1)))
     }
     result.add(RoadMatcher.Road(cursor.getLong(0),nodes,JSONObject(cursor.getString(2))))
    }
   }
   return result
  } finally {db.close()}
 }
}
