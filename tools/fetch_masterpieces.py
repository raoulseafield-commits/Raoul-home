"""Hand-picked public domain masterpieces from Wikimedia Commons -> ci-out/art (jpg + index.json with mood)."""
import json, os, re, urllib.request, urllib.parse
OUT = "ci-out/art"; os.makedirs(OUT, exist_ok=True)
UA = {"User-Agent": "MiTV-personal-launcher/1.0 (home TV screensaver; github raoulseafield-commits)"}
PICKS = [
 # (search, mood, artist)
 ("Van Gogh The Starry Night MoMA", "night", "Vincent van Gogh"),
 ("Van Gogh Starry Night Over the Rhone", "night", "Vincent van Gogh"),
 ("Kuindzhi Moonlit Night on the Dnieper", "night", "Arkhip Kuindzhi"),
 ("Caspar David Friedrich Moonrise over the Sea", "night", "Caspar David Friedrich"),
 ("Caspar David Friedrich Two Men Contemplating the Moon", "night", "Caspar David Friedrich"),
 ("Whistler Nocturne Blue and Gold Old Battersea Bridge", "night", "James McNeill Whistler"),
 ("Whistler Nocturne Blue and Silver Chelsea", "fog", "James McNeill Whistler"),
 ("Atkinson Grimshaw Liverpool Quay by Moonlight", "halloween", "John Atkinson Grimshaw"),
 ("Atkinson Grimshaw Reflections on the Thames Westminster", "night", "John Atkinson Grimshaw"),
 ("Atkinson Grimshaw Autumn Glory", "halloween", "John Atkinson Grimshaw"),
 ("Van Gogh Wheatfield with Crows", "halloween", "Vincent van Gogh"),
 ("Aivazovsky Moonlit Seascape", "night", "Ivan Aivazovsky"),
 ("Caillebotte Paris Street Rainy Day", "rain", "Gustave Caillebotte"),
 ("Turner Rain Steam and Speed", "rain", "J. M. W. Turner"),
 ("Pissarro Boulevard Montmartre at Night", "rain", "Camille Pissarro"),
 ("Hokusai The Great Wave off Kanagawa", "storm", "Katsushika Hokusai"),
 ("Aivazovsky The Ninth Wave", "storm", "Ivan Aivazovsky"),
 ("Turner Snow Storm Steam-Boat off a Harbour's Mouth", "storm", "J. M. W. Turner"),
 ("Thomas Cole The Oxbow", "cloudy", "Thomas Cole"),
 ("Bruegel Hunters in the Snow", "snow", "Pieter Bruegel the Elder"),
 ("Monet The Magpie", "snow", "Claude Monet"),
 ("Hiroshige Evening Snow at Kanbara", "snow", "Utagawa Hiroshige"),
 ("Caspar David Friedrich The Sea of Ice", "snow", "Caspar David Friedrich"),
 ("Monet Haystacks snow effect", "snow", "Claude Monet"),
 ("Monet Houses of Parliament London fog", "fog", "Claude Monet"),
 ("Monet Impression Sunrise", "fog", "Claude Monet"),
 ("Shishkin Morning in a Pine Forest", "fog", "Ivan Shishkin"),
 ("Constable The Hay Wain", "cloudy", "John Constable"),
 ("Turner The Fighting Temeraire", "cloudy", "J. M. W. Turner"),
 ("Hokusai Ejiri in Suruga Province", "cloudy", "Katsushika Hokusai"),
 ("Monet Poppy Field Argenteuil", "sunny", "Claude Monet"),
 ("Monet Water Lilies Nympheas", "sunny", "Claude Monet"),
 ("Van Gogh Wheat Field with Cypresses", "sunny", "Vincent van Gogh"),
 ("Van Gogh The Harvest 1888", "sunny", "Vincent van Gogh"),
 ("Hokusai Fine Wind Clear Morning Red Fuji", "sunny", "Katsushika Hokusai"),
 ("Bierstadt Among the Sierra Nevada California", "sunny", "Albert Bierstadt"),
 ("Frederic Edwin Church Twilight in the Wilderness", "sunny", "Frederic Edwin Church"),
 ("Frederic Edwin Church Heart of the Andes", "sunny", "Frederic Edwin Church"),
 ("Monet Haystacks end of summer", "sunny", "Claude Monet"),
 ("Van Gogh Almond Blossoms", "sunny", "Vincent van Gogh"),
 ("Bierstadt Rocky Mountains Lander's Peak", "sunny", "Albert Bierstadt"),
 ("Monet Sunrise Marine", "fog", "Claude Monet"),
]
def get(u):
    with urllib.request.urlopen(urllib.request.Request(u, headers=UA), timeout=40) as r: return r.read()
index = []
for q, mood, artist in PICKS:
    url = ("https://commons.wikimedia.org/w/api.php?action=query&format=json&generator=search&gsrnamespace=6&gsrlimit=10"
           "&prop=imageinfo&iiprop=url|size|extmetadata&iiurlwidth=2400&gsrsearch=" + urllib.parse.quote(q + " filetype:bitmap"))
    try:
        pages = sorted(json.loads(get(url)).get("query", {}).get("pages", {}).values(), key=lambda p: p.get("index", 99))
    except Exception as e:
        print("search failed", q, e); continue
    best = None
    for p in pages:
        ii = (p.get("imageinfo") or [{}])[0]
        w, h = ii.get("width", 0), ii.get("height", 0)
        lic = ii.get("extmetadata", {}).get("LicenseShortName", {}).get("value", "")
        if w < 1800 or not h or w / h < 1.12: continue
        if "Public domain" not in lic and "PD" not in lic and "CC0" not in lic: continue
        t = p["title"].lower()
        if any(x in t for x in ["detail", "frame", "museum", "gallery", "exhibition", "room", "photo of"]): continue
        best = (p, ii); break
    if not best: print("no match", q); continue
    p, ii = best
    slug = re.sub(r"[^a-z0-9]+", "_", q.lower()).strip("_")[:40]
    try:
        open(f"{OUT}/{slug}.jpg", "wb").write(get(ii["thumburl"]))
    except Exception as e:
        print("dl failed", q, e); continue
    index.append({"id": slug, "title": q, "artist": artist, "mood": mood, "file": f"art/{slug}.jpg", "source": p["title"], "w": ii["width"], "h": ii["height"]})
    print("ok", mood, q, "->", p["title"])
json.dump(index, open(f"{OUT}/index.json", "w"), indent=1)
print(len(index), "masterpieces")
