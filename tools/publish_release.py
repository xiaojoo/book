import hashlib
import json
import subprocess
import urllib.request

REPO = "xiaojoo/book"
APK = "H:/book/app/build/outputs/apk/release/app-release.apk"
BODY = "H:/book/scratch/release_body_v0.1.md"
NAME = "app-release.apk"
TAG = "v0.1"

p = subprocess.run(["git", "credential", "fill"], input="protocol=https\nhost=github.com\n\n",
                   capture_output=True, text=True)
tok = dict(l.split("=", 1) for l in p.stdout.strip().splitlines() if "=" in l)["password"]
print("token loaded, length", len(tok))
AUTH = "token " + tok


def req(method, url, payload=None, ctype="application/json", extra=None):
    data = json.dumps(payload).encode() if payload is not None else None
    h = {"Authorization": AUTH, "User-Agent": "release-publisher", "Accept": "application/vnd.github+json"}
    if ctype:
        h["Content-Type"] = ctype
    if extra:
        h.update(extra)
    r = urllib.request.Request(url, data=data, headers=h, method=method)
    with urllib.request.urlopen(r, timeout=60) as resp:
        body = resp.read()
        return resp.status, (json.loads(body) if body else None)


status, rel = req("POST", f"https://api.github.com/repos/{REPO}/releases", {
    "tag_name": TAG,
    "name": "长文阅读 v0.1",
    "body": open(BODY, encoding="utf-8").read(),
    "draft": False,
    "prerelease": False,
    "generate_release_notes": False,
})
print("release created:", status, rel["id"], rel["html_url"], "| tag:", rel["tag_name"])

raw = open(APK, "rb").read()
local_sha = hashlib.sha256(raw).hexdigest()
print("local apk:", len(raw), "bytes, sha256", local_sha[:16], "...")

up = rel["upload_url"].split("{")[0]
url = up + "?name=" + NAME
data = raw
h = {"Authorization": AUTH, "User-Agent": "release-publisher",
     "Content-Type": "application/vnd.android.package-archive",
     "Content-Length": str(len(data)),
     "Expect": "100-continue"}
r = urllib.request.Request(url, data=data, headers=h, method="POST")
with urllib.request.urlopen(r, timeout=300) as resp:
    a = json.loads(resp.read())
    print("asset:", resp.status, a["name"], a["size"], a["state"], a["browser_download_url"])

print("release assets now:", [(x["name"], x["size"]) for x in req("GET", rel["url"])[1]["assets"]])
