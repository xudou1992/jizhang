"""把构建好的 APK 复制到工作区根目录，并检查打包内容是否正确。"""
import os
import shutil
import time
import zipfile

ROOT = r"C:\Users\Administrator\WorkBuddy\记账软件"
SRC = os.path.join(ROOT, "jizhang-native", "app", "build", "outputs", "apk", "debug", "app-debug.apk")
DST = os.path.join(ROOT, "简记-1.0.0.apk")

out = []
if not os.path.exists(SRC):
    out.append("MISSING: %s" % SRC)
else:
    shutil.copy2(SRC, DST)
    out.append("APK: %s" % DST)
    out.append("SIZE: %d bytes (%.2f MB)" % (os.path.getsize(DST), os.path.getsize(DST) / 1048576.0))
    out.append("MTIME: %s" % time.ctime(os.path.getmtime(DST)))

    with zipfile.ZipFile(DST) as z:
        names = z.namelist()
        out.append("ENTRIES: %d" % len(names))
        for probe in ("AndroidManifest.xml", "classes.dex", "assets/migration.json"):
            out.append("HAS %-26s %s" % (probe, probe in names))
        out.append("DEX: %s" % ", ".join(n for n in names if n.endswith(".dex")))
        out.append("ASSETS: %s" % ", ".join(n for n in names if n.startswith("assets/")))
        if "assets/migration.json" in names:
            raw = z.read("assets/migration.json")
            out.append("migration.json bytes=%d head=%s" % (len(raw), raw[:60]))

with open(os.path.join(ROOT, "_apk_verify.txt"), "w", encoding="utf-8") as f:
    f.write("\n".join(out))
