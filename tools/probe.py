"""Runs on GitHub Actions (it has open internet). Writes everything into ./ci-out for review."""
import json, os, sys, urllib.request, urllib.parse

OUT = "ci-out"
os.makedirs(OUT + "/walls", exist_ok=True)
UA = {"User-Agent": "MiTV-personal-launcher/1.0 (home TV project; contact via github raoulseafield-commits)"}

def get(url, data=None, headers=None, timeout=40):
    h = dict(UA); h.update(headers or {})
    req = urllib.request.Request(url, data=data, headers=h)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()

# ---------------- JustWatch (South Africa) ----------------
Q = """
query Search($country: Country!, $language: Language!, $first: Int!, $q: String!) {
  popularTitles(country: $country, first: $first, filter: {searchQuery: $q}) {
    edges { node {
      id objectId objectType
      content(country: $country, language: $language) { title originalReleaseYear fullPath posterUrl shortDescription }
      offers(country: $country, platform: WEB) {
        monetizationType presentationType standardWebURL
        package { clearName technicalName packageId shortName }
      }
    } }
  }
}"""
res = {}
for q in ["superman", "big bang theory", "the night agent"]:
    body = json.dumps({"query": Q, "variables": {"country": "ZA", "language": "en", "first": 6, "q": q}}).encode()
    try:
        res[q] = json.loads(get("https://apis.justwatch.com/graphql", body, {"Content-Type": "application/json"}))
    except Exception as e:
        res[q] = {"error": str(e)}
json.dump(res, open(OUT + "/justwatch.json", "w"), indent=1)
print("justwatch done")

# ---------------- Wallpaper candidates (Wikimedia Commons, free licences) ----------------
queries = [
    "Mount Rainier sunset", "Mount Rainier alpenglow", "Mount Hood sunset", "Mount Shuksan sunset",
    "alpenglow mountain peak", "Matterhorn sunset", "Fitz Roy sunrise", "Torres del Paine sunrise",
    "Dolomites sunset", "Drakensberg sunset", "Mount Assiniboine sunset", "Grand Teton sunrise",
    "Mount Fuji sunset", "Cerro Torre sunset", "mountain pink sky sunset"
]
seen, cands = set(), []
for q in queries:
    url = ("https://commons.wikimedia.org/w/api.php?action=query&format=json&generator=search&gsrnamespace=6"
           "&gsrlimit=12&prop=imageinfo&iiprop=url|size|extmetadata&iiurlwidth=1920&gsrsearch=" + urllib.parse.quote(q))
    try:
        pages = json.loads(get(url)).get("query", {}).get("pages", {})
    except Exception as e:
        print("search failed", q, e); continue
    for p in pages.values():
        ii = (p.get("imageinfo") or [{}])[0]
        w, h = ii.get("width", 0), ii.get("height", 0)
        if not w or not h or w < 2400 or not (1.3 <= w / h <= 2.3): continue
        md = ii.get("extmetadata", {})
        lic = md.get("LicenseShortName", {}).get("value", "")
        if not any(k in lic for k in ["CC0", "Public domain", "CC BY", "CC-BY"]): continue
        if "NC" in lic or "ND" in lic: continue
        if p["title"] in seen or not ii.get("thumburl"): continue
        seen.add(p["title"])
        cands.append({"q": q, "title": p["title"], "thumb": ii["thumburl"], "license": lic,
                      "artist": md.get("Artist", {}).get("value", ""), "w": w, "h": h, "page": ii.get("descriptionurl", "")})
print(len(cands), "candidates")
keep = []
for i, c in enumerate(cands[:60]):
    try:
        data = get(c["thumb"])
        name = f"{len(keep):02d}.jpg"
        open(f"{OUT}/walls/{name}", "wb").write(data)
        c["file"] = name; keep.append(c)
    except Exception as e:
        print("dl failed", c["title"], e)
json.dump(keep, open(OUT + "/walls/meta.json", "w"), indent=1)
print(len(keep), "downloaded")
