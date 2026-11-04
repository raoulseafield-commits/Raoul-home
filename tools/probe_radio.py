"""Which free lofi radio streams work? Writes ci-out/radio.json."""
import json, os, urllib.request
os.makedirs("ci-out", exist_ok=True)
UA = {"User-Agent": "MiTV/1.0"}
def get(u, n=None, t=15):
    r = urllib.request.urlopen(urllib.request.Request(u, headers=UA), timeout=t)
    return r.read(n) if n else r.read(), r
out = {"directory": [], "checked": []}
try:
    data, _ = get("https://de1.api.radio-browser.info/json/stations/bytag/lofi?order=votes&reverse=true&limit=40&hidebroken=true")
    for s in json.loads(data):
        out["directory"].append({k: s.get(k) for k in ["name", "url_resolved", "codec", "bitrate", "votes", "countrycode", "lastcheckok"]})
except Exception as e:
    out["directory_error"] = str(e)
cands = [d["url_resolved"] for d in out["directory"] if d.get("codec") in ("MP3", "AAC", "AAC+") and (d.get("bitrate") or 0) >= 64][:25]
cands += ["https://streams.fluxfm.de/Chillhop/mp3-128/streams.fluxfm.de/", "https://stream.laut.fm/lofi", "https://stream.laut.fm/lofi-hip-hop",
          "https://ice1.somafm.com/fluid-128-mp3", "https://ice1.somafm.com/groovesalad-128-mp3", "https://usa9.fastcast4u.com/proxy/jamz?mp=/1"]
for u in cands:
    try:
        data, r = get(u, 32000, 12)
        out["checked"].append({"url": u, "ok": len(data) > 16000, "bytes": len(data), "type": r.headers.get("Content-Type"), "icy": r.headers.get("icy-name")})
    except Exception as e:
        out["checked"].append({"url": u, "ok": False, "error": str(e)[:120]})
json.dump(out, open("ci-out/radio.json", "w"), indent=1)
print(sum(1 for c in out["checked"] if c["ok"]), "streams ok")
