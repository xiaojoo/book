"""把一支 app-release.apk 发成一个 GitHub Release。

用法：`python tools/publish_release.py v0.2`
正文取 `scratch/release_body_<tag>.md`（scratch/ 不入库，那份正文跟着 release 走、不跟着仓库走）。
口令一律现取（`git credential fill`），不落文件、不进日志。
"""
import hashlib
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request

REPO = "xiaojoo/book"
APK = "app/build/outputs/apk/release/app-release.apk"

if len(sys.argv) != 2:
    raise SystemExit("要带一个 tag：python tools/publish_release.py v0.2")
TAG = sys.argv[1]
BODY = "scratch/release_body_%s.md" % TAG
NAME = "app-release.apk"

if not os.path.exists(BODY):
    raise SystemExit("没有这份正文，先写再发：%s" % BODY)
if not os.path.exists(APK):
    raise SystemExit("没有这个包，先 assembleRelease：%s" % APK)

r = subprocess.run(["git", "credential", "fill"], input="protocol=https\nhost=github.com\n\n",
                   capture_output=True, text=True)
tok = dict(l.split("=", 1) for l in r.stdout.strip().splitlines() if "=" in l)["password"]
print("token loaded, length", len(tok))
AUTH = "token " + tok


def req(method, url, payload=None, ctype="application/json", extra=None, timeout=60):
    data = json.dumps(payload).encode() if payload is not None else None
    h = {"Authorization": AUTH, "User-Agent": "release-publisher", "Accept": "application/vnd.github+json"}
    if ctype:
        h["Content-Type"] = ctype
    if extra:
        h.update(extra)
    rq = urllib.request.Request(url, data=data, headers=h, method=method)
    with urllib.request.urlopen(rq, timeout=timeout) as resp:
        body = resp.read()
        return resp.status, (json.loads(body) if body else None)


def api(method, path, payload=None, **kw):
    return req(method, "https://api.github.com" + path, payload, **kw)


def release_exists(tag):
    """已经存在就别再 POST 一次：那会拿 422，而且没人知道是"发过了"还是"发坏了"。"""
    try:
        st, rel = api("GET", "/repos/%s/releases/tags/%s" % (REPO, tag))
    except urllib.error.HTTPError:
        return None
    return rel if st == 200 else None


rel = release_exists(TAG)
if rel is None:
    status, rel = api("POST", "/repos/%s/releases" % REPO, {
        "tag_name": TAG,
        "name": "长文阅读 %s" % TAG,
        "body": open(BODY, encoding="utf-8").read(),
        "draft": False,
        "prerelease": False,
        "generate_release_notes": False,
    })
    print("release created:", status, rel["id"], rel["html_url"], "| tag:", rel["tag_name"])
else:
    print("release 已有：", rel["id"], rel["html_url"], "| 只换资产")

raw = open(APK, "rb").read()
local_sha = hashlib.sha256(raw).hexdigest()
print("local apk:", len(raw), "bytes, sha256", local_sha[:16], "...")

up = rel["upload_url"].split("{")[0]


def upload():
    h = {"Authorization": AUTH, "User-Agent": "release-publisher",
         "Content-Type": "application/vnd.android.package-archive",
         "Content-Length": str(len(raw)),
         "Expect": "100-continue"}
    rq = urllib.request.Request(up + "?name=" + NAME, data=raw, headers=h, method="POST")
    with urllib.request.urlopen(rq, timeout=300) as resp:
        return json.loads(resp.read())


try:
    a = upload()
except urllib.error.HTTPError as e:
    # 422 = 同名资产已经在了（上一次跑到一半断的）：删掉那一支再传一次，不留两支同名
    if e.code != 422:
        raise
    print("同名资产已在（422），先删再传")
    for old in api("GET", rel["url"])[1]["assets"]:
        if old["name"] == NAME:
            api("DELETE", "/repos/%s/releases/assets/%s" % (REPO, old["id"]))
    a = upload()
print("asset:", a["id"], a["name"], a["size"], a["state"], a["browser_download_url"])

# 收尾核对：远端那份的 digest 必须和刚算出来的本地 sha256 同一个数，否则装到别人手机上的是另一支
st, rel2 = api("GET", rel["url"])
mine = [x for x in rel2["assets"] if x["name"] == NAME]
print("release assets now:", [(x["name"], x["size"]) for x in rel2["assets"]])
if not mine:
    raise SystemExit("传完再列却没有这一支 —— 别报成功")
remote_digest = (mine[0].get("digest") or "").replace("sha256:", "")
print("remote digest :", remote_digest[:16], "...")
print("local sha256  :", local_sha[:16], "...")
print("一致" if remote_digest == local_sha else "不一致：远端不是本地这一支，别发出去")
if remote_digest and remote_digest != local_sha:
    raise SystemExit(1)
