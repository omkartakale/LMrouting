"""
Extract Pune-area pincodes from the All India pincode boundary GeoJSON.
Filters features whose centroid is within 60km of PNQ HDP hub.
Outputs a smaller GeoJSON file for use in the allocation engine.
"""
import json
import math
import sys

HUB_LAT = 18.4600561
HUB_LNG = 73.8884305
MAX_DIST_KM = 60.0  # generous radius to capture all Pune pincodes

INPUT_FILE = r"C:\Users\Omkar Takele\Downloads\All_India_pincode_Boundary-19312.geojson"
OUTPUT_FILE = "src/main/resources/pune-pincode-boundaries.geojson"

def haversine(lat1, lon1, lat2, lon2):
    R = 6371.0
    dlat = math.radians(lat2 - lat1)
    dlon = math.radians(lon2 - lon1)
    a = math.sin(dlat/2)**2 + math.cos(math.radians(lat1)) * math.cos(math.radians(lat2)) * math.sin(dlon/2)**2
    return R * 2 * math.atan2(math.sqrt(a), math.sqrt(1-a))

def centroid_of_geometry(geometry):
    """Compute rough centroid from all coordinates in a geometry."""
    coords = []
    def extract(obj):
        if isinstance(obj, list):
            if len(obj) >= 2 and isinstance(obj[0], (int, float)):
                coords.append(obj)
            else:
                for item in obj:
                    extract(item)
    extract(geometry.get("coordinates", []))
    if not coords:
        return None, None
    avg_lng = sum(c[0] for c in coords) / len(coords)
    avg_lat = sum(c[1] for c in coords) / len(coords)
    return avg_lat, avg_lng

print(f"Loading {INPUT_FILE}...")
with open(INPUT_FILE, "r", encoding="utf-8") as f:
    data = json.load(f)

features = data.get("features", [])
print(f"Total features: {len(features)}")

pune_features = []
for feat in features:
    geom = feat.get("geometry")
    if not geom:
        continue
    lat, lng = centroid_of_geometry(geom)
    if lat is None:
        continue
    dist = haversine(HUB_LAT, HUB_LNG, lat, lng)
    if dist <= MAX_DIST_KM:
        # Add distance to properties for reference
        if "properties" not in feat:
            feat["properties"] = {}
        feat["properties"]["_distFromHubKm"] = round(dist, 2)
        pune_features.append(feat)

print(f"Pune-area features (within {MAX_DIST_KM}km): {len(pune_features)}")

# Print pincode field names from first feature
if pune_features:
    props = pune_features[0].get("properties", {})
    print(f"Property keys: {list(props.keys())}")
    print(f"Sample properties: {json.dumps({k:v for k,v in list(props.items())[:5]}, default=str)}")

output = {
    "type": "FeatureCollection",
    "features": pune_features
}

with open(OUTPUT_FILE, "w", encoding="utf-8") as f:
    json.dump(output, f)

size_mb = len(json.dumps(output)) / (1024*1024)
print(f"Output: {OUTPUT_FILE} ({size_mb:.1f} MB, {len(pune_features)} features)")
