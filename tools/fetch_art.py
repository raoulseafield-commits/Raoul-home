"""Bake a starter set of public domain paintings into the APK (assets/art).
Runs on GitHub Actions during the build. Source: Art Institute of Chicago open API."""
import json, os, sys, urllib.request, urllib.parse

out = sys.argv[1]
os.makedirs(out, exist_ok=True)
HEAD = {"AIC-User-Agent": "DipixelHome build", "User-Agent": "DipixelHome/1.0"}
topics = ["landscape", "impressionism", "seascape", "mountains", "garden", "japanese woodblock"]
WANT = 14

def get(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers=HEAD), timeout=30) as r:
        return r.read()

index, seen = [], set()
for t in topics:
    if len(index) >= WANT: break
    url = ("https://api.artic.edu/api/v1/artworks/search?q=" + urllib.parse.quote(t) +
           "&query%5Bterm%5D%5Bis_public_domain%5D=true&fields=id,title,artist_title,image_id,thumbnail&limit=30")
    try:
        data = json.loads(get(url))["data"]
    except Exception as e:
        print("search failed", t, e); continue
    picked = 0
    for a in data:
        if len(index) >= WANT or picked >= 3: break
        th = a.get("thumbnail") or {}
        w, h = th.get("width") or 0, th.get("height") or 0
        if not a.get("image_id") or a["id"] in seen or not w or not h or w / h < 1.25: continue
        try:
            img = get(f"https://www.artic.edu/iiif/2/{a['image_id']}/full/1686,/0/default.jpg")
        except Exception as e:
            print("image failed", a["id"], e); continue
        if len(img) < 30000: continue
        name = f"{a['id']}.jpg"
        open(os.path.join(out, name), "wb").write(img)
        index.append({"id": str(a["id"]), "title": a.get("title") or "", "artist": a.get("artist_title") or "", "file": "art/" + name})
        seen.add(a["id"]); picked += 1
        print("baked", a["id"], a.get("title"))

json.dump(index, open(os.path.join(out, "index.json"), "w"))
print(len(index), "paintings baked in")
