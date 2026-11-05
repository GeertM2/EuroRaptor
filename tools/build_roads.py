#!/usr/bin/env python3
"""Convert an OSM .pbf extract to EuroRaptor's SQLite road index.
Requires: pip install osmium
Usage: python tools/build_roads.py netherlands-latest.osm.pbf roads-nl.db
"""
import argparse
import json
import os
import sqlite3
import osmium

HIGHWAYS = {"motorway","motorway_link","trunk","trunk_link","primary","primary_link",
            "secondary","secondary_link","tertiary","tertiary_link","residential",
            "unclassified","living_street","service"}
TAGS = ("highway","maxspeed","maxspeed:conditional","maxspeed:variable","oneway",
        "zone:traffic","source:maxspeed","zone:maxspeed")

class Roads(osmium.SimpleHandler):
    def __init__(self, db):
        super().__init__()
        self.db=db
        self.count=0
        self.batch=[]
    def way(self,w):
        if w.tags.get("highway") not in HIGHWAYS:
            return
        if not w.tags.get("maxspeed") and not w.tags.get("maxspeed:conditional"):
            return
        try:
            pts=[(round(n.lat,6),round(n.lon,6)) for n in w.nodes]
        except osmium.InvalidLocationError:
            return
        if len(pts)<2:
            return
        tags={k:w.tags[k] for k in TAGS if k in w.tags}
        bounds=(min(p[0] for p in pts),max(p[0] for p in pts),
                min(p[1] for p in pts),max(p[1] for p in pts))
        self.batch.append((int(w.id),json.dumps(pts,separators=(",",":")),
                           json.dumps(tags,separators=(",",":")),*bounds))
        if len(self.batch)>=1000:
            self.flush()
    def flush(self):
        if not self.batch: return
        self.db.executemany("INSERT OR REPLACE INTO roads VALUES (?,?,?,?,?,?,?)",self.batch)
        self.count+=len(self.batch)
        self.batch.clear()

def main():
    p=argparse.ArgumentParser()
    p.add_argument("input",help="OSM PBF extract")
    p.add_argument("output",help="SQLite output")
    args=p.parse_args()
    if os.path.exists(args.output): os.remove(args.output)
    db=sqlite3.connect(args.output)
    db.execute("PRAGMA journal_mode=OFF")
    db.execute("PRAGMA synchronous=OFF")
    db.execute("CREATE TABLE roads(id INTEGER PRIMARY KEY, geometry TEXT NOT NULL, tags TEXT NOT NULL, minlat REAL, maxlat REAL, minlon REAL, maxlon REAL)")
    handler=Roads(db)
    handler.apply_file(args.input,locations=True)
    handler.flush()
    db.execute("CREATE VIRTUAL TABLE road_index USING rtree(id,minlat,maxlat,minlon,maxlon)")
    db.execute("INSERT INTO road_index SELECT id,minlat,maxlat,minlon,maxlon FROM roads")
    db.execute("CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT)")
    db.execute("INSERT INTO metadata VALUES('schema','1')")
    db.commit()
    db.execute("VACUUM")
    db.close()
    print(f"{handler.count} roads, {os.path.getsize(args.output)/1048576:.1f} MiB")

if __name__=="__main__": main()
