#!/usr/bin/env python3
import sys, zipfile, os

if len(sys.argv) != 3:
    sys.exit("usage: inject_dex.py <apk> <classes.dex>")

apk, dex = sys.argv[1], sys.argv[2]
tmp = apk + ".new"

with zipfile.ZipFile(apk, "r") as zin, zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        if item.filename == "classes.dex":
            continue
        zout.writestr(item, zin.read(item.filename))
    with open(dex, "rb") as f:
        zout.writestr("classes.dex", f.read())

# os.replace 在 Windows 上可原子覆盖已存在文件
os.replace(tmp, apk)
print("injected classes.dex into", apk)
