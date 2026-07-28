#!/usr/bin/env bash
# 一键发版：构建 APK + 更新 version.json + 通过 GitHub API 上传（无需 git push）
# 适合本机 git 直连 github.com 被挡、但 api.github.com 可通的环境。
# 用法：
#   改了界面/代码想发版  ->  bash publish.sh
#   发新 APK 大版本        ->  VC=2 VN=1.0.1 bash publish.sh
cd "$(dirname "$0")"

VC="${VC:-1}"
VN="${VN:-1.0.0}"

# 注意：App 端的更新地址不再写死某个标签——MainActivity 会在运行时通过
# data.jsdelivr.com（实时、不缓存）自动发现最新版本标签，再取该标签的 version.json。
# 因此这里不再用 sed 改写 MainActivity，也不需要「浮动标签 @latest」（jsDelivR 对标签名
# 永久缓存，移动/重建同名标签、purge 都无法刷新，已证明走不通）。发版只用「全新版本标签名」。

# 取令牌：优先 .git/config 的 remote.origin.url，其次环境变量 GITHUB_TOKEN
RAW=$(git config remote.origin.url 2>/dev/null || true)
TOKEN=$(echo "$RAW" | sed -E 's#^https://([^@]+)@.*$#\1#; t; d')
if [ -z "$TOKEN" ]; then TOKEN="${GITHUB_TOKEN:-}"; fi
if [ -z "$TOKEN" ]; then
  echo "✗ 找不到 GitHub 令牌。请把 remote.origin.url 设为 https://<PAT>@github.com/Q9171/weixin.git，或 export GITHUB_TOKEN=..."
  exit 1
fi
REPO="Q9171/weixin"
API="https://api.github.com/repos/$REPO/contents"

echo "[1/4] 构建 APK (VC=$VC VN=$VN)..."
VC="$VC" VN="$VN" bash build_apk.sh

echo "[2/4] 更新 version.json + 按版本命名 APK（绕过 CDN 缓存）..."
python3 - "$VC" "$VN" <<'PY'
import sys, json, os, shutil
vc, vn = sys.argv[1], sys.argv[2]
p = 'publish/version.json'
d = {}
if os.path.exists(p):
    d = json.load(open(p, encoding='utf-8'))
d['versionCode'] = int(vc)
d['versionName'] = vn
note_lines = [
  'v' + vn + ' 更新：',
  '• 修复单人频道自己发的消息被当成对方消息回显的问题',
  '• 聊天记录本地保存，退出频道再进入不丢失',
  '• 删除频道时同时清除该频道的聊天记录',
  '• 每频道最多保留500条历史消息'
]
if int(vc) <= 9:
  # v1.0.9 之前保留旧文案兼容
  pass
d['note'] = '\n'.join(note_lines)
apk_name = 'wei-%s.apk' % vn
if os.path.exists('app-debug.apk'):
    shutil.copy('app-debug.apk', apk_name)
d['apkUrl'] = 'https://cdn.jsdelivr.net/gh/Q9171/weixin@v' + vn + '/' + apk_name
json.dump(d, open(p, 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
# 同步到仓库根目录的 version.json —— App 默认读取的就是根目录这个（publish/ 下只是源码备份）
json.dump(d, open('version.json', 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
print('version.json -> code', vc, 'name', vn, '| apkUrl', d['apkUrl'])
PY

echo "[3/4] 上传文件到 GitHub（Contents API）..."
upload() {
  local f="$1" p="$2"
  local sha="" resp code tmp
  resp=$(curl -sS -H "Authorization: Bearer $TOKEN" -H "Accept: application/vnd.github+json" "$API/$p")
  if echo "$resp" | grep -q '"sha"'; then
    sha=$(echo "$resp" | python3 -c "import sys,json;print(json.load(sys.stdin).get('sha',''))" 2>/dev/null)
  fi
  # base64 + JSON 写临时文件：用 python 自己的临时目录（Windows 原生路径），
  # 因为本沙箱里 /tmp 对 python/curl 不可见；同时避免超长命令行参数（ARG_MAX）致大文件失败。
  tmp=$(python3 - "$f" "$p" "$sha" <<'PY'
import sys, base64, json, tempfile, os
f, p, sha = sys.argv[1], sys.argv[2], sys.argv[3]
b64 = base64.b64encode(open(f, 'rb').read()).decode()
d = {'message': 'publish: ' + p, 'content': b64}
if sha:
    d['sha'] = sha
path = os.path.join(tempfile.gettempdir(), 'pub_%d.json' % os.getpid())
json.dump(d, open(path, 'w'))
print(path)
PY
)
  code=$(curl -sS -X PUT -H "Authorization: Bearer $TOKEN" -H "Accept: application/vnd.github+json" -H "Content-Type: application/json" -d "@$tmp" "$API/$p" -o /dev/null -w "%{http_code}")
  rm -f "$tmp"
  echo "  $p -> HTTP $code"
}

upload "wei-$VN.apk" "wei-$VN.apk"
upload publish/version.json publish/version.json
upload version.json version.json
upload app/src/main/AndroidManifest.xml app/src/main/AndroidManifest.xml
upload app/src/main/assets/index.html app/src/main/assets/index.html
upload app/src/main/assets/mqtt.min.js app/src/main/assets/mqtt.min.js
upload app/src/main/java/com/workbuddy/roomchat/MainActivity.java app/src/main/java/com/workbuddy/roomchat/MainActivity.java
upload app/src/main/java/com/workbuddy/roomchat/ApkFileProvider.java app/src/main/java/com/workbuddy/roomchat/ApkFileProvider.java
upload app/src/main/res/drawable/ic_launcher.xml app/src/main/res/drawable/ic_launcher.xml
upload app/src/main/res/values/strings.xml app/src/main/res/values/strings.xml
upload app/src/main/res/values/styles.xml app/src/main/res/values/styles.xml
upload web/index.html web/index.html
upload web/mqtt.min.js web/mqtt.min.js
upload build_apk.sh build_apk.sh
upload inject_dex.py inject_dex.py
upload build.gradle build.gradle
upload app/build.gradle app/build.gradle
upload settings.gradle settings.gradle
upload gradle.properties gradle.properties
upload local.properties local.properties

echo "[4/4] 创建版本标签 v$VN（全新标签名→jsDelivR 即时刷新）并验证..."
# 取 main 最新提交 SHA（上传之后）
MAIN_SHA=$(curl -sS -H "Authorization: Bearer $TOKEN" "https://api.github.com/repos/$REPO/git/refs/heads/main" | python3 -c "import sys,json; print(json.load(sys.stdin)['object']['sha'])" 2>/dev/null)
echo "  → main 最新提交：$MAIN_SHA"

# 创建或前移一个标签（已存在则 force 移动）
move_tag() {
  local tag="$1" sha="$2"
  python3 - "$TOKEN" "$REPO" "$tag" "$sha" <<'PY'
import sys, urllib.request, json
TOKEN,REPO,tag,sha=sys.argv[1],sys.argv[2],sys.argv[3],sys.argv[4]
H={"Authorization":"Bearer "+TOKEN,"Accept":"application/vnd.github+json","Content-Type":"application/json","User-Agent":"Mozilla/5.0"}
def req(method,url,data=None):
    body=json.dumps(data).encode() if data else None
    r=urllib.request.Request(url,data=body,headers=H,method=method)
    try: return urllib.request.urlopen(r,timeout=30).status
    except urllib.error.HTTPError as e: return e.code
st=req("POST","https://api.github.com/repos/%s/git/refs"%REPO,{"ref":"refs/tags/"+tag,"sha":sha})
if st==422:
    st=req("PATCH","https://api.github.com/repos/%s/git/refs/tags/%s"%(REPO,tag),{"sha":sha,"force":True})
    print("  -> 前移标签 %s: HTTP %s"%(tag,st))
else:
    print("  -> 创建标签 %s: HTTP %s"%(tag,st))
PY
}

# 只创建「全新版本标签名」v$VN（不可变）：
#   - 供 App 通过 data.jsdelivr.com 自动发现最新版
#   - 也是 version.json 里 apkUrl 的下载地址（@v$VN）
# 不再使用浮动标签 @latest（jsDelivR 对标签名永久缓存，无法刷新，已废弃）。
move_tag "v$VN" "$MAIN_SHA"

# 验证 1（即时、权威）：GitHub API 直接读 v$VN 指向的 version.json 的 versionCode
API_CODE=$(curl -sS -H "Authorization: Bearer $TOKEN" "https://api.github.com/repos/$REPO/contents/version.json?ref=v$VN" \
  | python3 -c "import sys,json,base64; d=json.load(sys.stdin); print(json.load(__import__('io').StringIO(base64.b64decode(d['content']).decode()))['versionCode'])" 2>/dev/null)
if [ "$API_CODE" = "$VC" ]; then
  echo "  ✅ GitHub v$VN 已生效：versionCode=$API_CODE"
else
  echo "  ⚠️ GitHub v$VN versionCode=$API_CODE（期望 $VC），请检查上传。"
fi

# 强制刷新 data.jsdelivr.com 包级列表端点缓存（否则新建标签可能不在列表里，App 发现不到新版）
echo "  → purge jsDelivr 包级列表端点（确保 App 能发现 v$VN）..."
curl -sS -o /dev/null -w "  → 包级 purge: HTTP %{http_code}\n" "https://purge.jsdelivr.net/api/gh/$REPO"

# 验证 2（实时、不依赖 CDN 缓存）：data.jsdelivr.com 应已列出 v$VN，App 靠它发现最新版
echo "  → data.jsdelivr.com 实时版本列表（App 据此自动选最新）："
curl -sS "https://data.jsdelivr.com/v1/packages/gh/$REPO" 2>/dev/null \
  | python3 -c "import sys,json; [print('     ', v['version']) for v in json.load(sys.stdin).get('versions',[])]"

echo "✅ 已发版 v$VN（versionCode=$VC）。App 启动后通过 data.jsdelivr.com 自动发现 v$VN 并检查更新，存量/新装用户均可收到更新提示（CDN 对全新标签名即时生效，无需 purge）。"
