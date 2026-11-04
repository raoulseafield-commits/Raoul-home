"""DStv deep link research: what JustWatch ZA gives for DStv titles, and which app owns DStv links."""
import json, os, re, urllib.request

OUT = "ci-out"; os.makedirs(OUT, exist_ok=True)
UA = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124 Safari/537.36"}

def get(url, data=None, headers=None):
    h = dict(UA); h.update(headers or {})
    with urllib.request.urlopen(urllib.request.Request(url, data=data, headers=h), timeout=40) as r:
        return r.read().decode("utf-8", "replace")

log = []
Q = """query S($country: Country!, $language: Language!, $first: Int!, $q: String!) {
  popularTitles(country: $country, first: $first, filter: {searchQuery: $q}) { edges { node {
    objectType content(country: $country, language: $language) { title originalReleaseYear }
    offers(country: $country, platform: WEB) { monetizationType standardWebURL package { clearName technicalName } }
  } } } }"""
for q in ["spinners", "house of the dragon", "the last of us", "big bang theory", "shaka ilembe", "the real housewives of durban"]:
    try:
        body = json.dumps({"query": Q, "variables": {"country": "ZA", "language": "en", "first": 3, "q": q}}).encode()
        d = json.loads(get("https://apis.justwatch.com/graphql", body, {"Content-Type": "application/json"}))
        for e in d["data"]["popularTitles"]["edges"]:
            n = e["node"]
            for o in n.get("offers") or []:
                pk = o["package"]
                if True:
                    log.append(f"JW {q!r}: {n['content']['title']} | {pk['clearName']} ({pk['technicalName']}) {o['monetizationType']} {o['standardWebURL']}")
    except Exception as ex:
        log.append(f"JW {q} error {ex}")

for host in ["stream.dstv.com", "now.dstv.com", "www.dstv.com", "dstv.com", "www.showmax.com"]:
    try:
        log.append(f"assetlinks {host}: {get('https://' + host + '/.well-known/assetlinks.json')[:1500]}")
    except Exception as ex:
        log.append(f"assetlinks {host}: error {ex}")

for q in ["dstv stream", "dstv"]:
    try:
        html = get("https://play.google.com/store/search?q=" + q.replace(" ", "+") + "&c=apps&hl=en&gl=ZA")
        ids = sorted(set(re.findall(r"details\?id=([a-zA-Z0-9_.]+)", html)))
        log.append(f"play {q}: {[i for i in ids if re.search('dstv|multichoice|showmax', i, re.I)]}")
    except Exception as ex:
        log.append(f"play {q}: error {ex}")

open(OUT + "/dstv.txt", "w").write("\n".join(log))
print("\n".join(log))
