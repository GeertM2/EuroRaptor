# Offline road database (experimental v1.0)

The APK is small; the offline road map is a separate SQLite file, **not bundled with the APK**.

## Build a Netherlands map

1. Install Python 3 and `pip install osmium`.
2. Download a Netherlands `.osm.pbf` extract from a trusted OpenStreetMap extract provider (e.g. Geofabrik).
3. Run `python tools/build_roads.py netherlands-latest.osm.pbf roads-nl.db`.
4. Transfer `roads-nl.db` to your phone.
5. Open EuroRaptor → **Importeer offline wegenkaart (.db)** → select the file.
6. Stop and restart the overlay to switch from online to offline roads.

The converter prints the **actual database size**. The Netherlands-wide database has not yet been built or measured in this repository.

### Limitations

- Only roads with an explicit OSM `maxspeed` or `maxspeed:conditional` tag are included. Missing tags remain unknown; no assumed defaults.
- The road matcher supports simple daily time ranges and some weekday conditions, not live matrix signs or temporary roadworks.
- The map file must be updated manually; automatic downloads/updates are not implemented.
- Large full-country extracts may require substantial memory/time to convert on a computer. Never run the converter on the phone.
- The initial offline index is a prototype. Validate map accuracy and tariffs before relying on any fine estimate.
- OSM data attribution and ODbL obligations apply when redistributing a derived map database.

When no offline map is installed, the app still uses the experimental online Overpass fallback.
