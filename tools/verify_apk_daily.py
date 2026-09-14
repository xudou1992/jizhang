"""端到端验证：新的「数据备份」页真的进了 APK（不是只看"编译通过"）。

做法：APK 是 zip，dex 里 Kotlin 的字符串常量是 MUTF-8；BMP 范围内的中文与 UTF-8 同字节，
所以直接在 dex 里搜 UTF-8 字节即可确认代码进了包。
图标/插画这类资源不进 dex，改去 resources.arsc 的字符串池里找资源名。
同时复核我的源文件有没有被并发写入者改掉。
"""
import os
import shutil
import time
import zipfile

ROOT = r"C:\Users\Administrator\WorkBuddy\记账软件"
APK = os.path.join(ROOT, "jizhang-native", "app", "build", "outputs", "apk", "debug", "app-debug.apk")
DST = os.path.join(ROOT, "记账软件-1.0.3.apk")
OUT = os.path.join(ROOT, "_apk_verify_daily.txt")

lines = []

apk_stat = os.stat(APK)
lines.append("APK: %s" % APK)
lines.append("  改动时间 %s  大小 %.2f MB" % (
    time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(apk_stat.st_mtime)),
    apk_stat.st_size / 1024 / 1024,
))

# (标记, 期望)  —— 期望 False 表示「必须已经不在了」
DEX_MARKERS = [
    # 备份来源三态与命名
    ("简记-", True),
    ("manual", True),
    ("auto", True),
    ("safety", True),
    ("手工", True),
    ("自动", True),
    ("恢复留底", True),
    # 凭据默认收起
    ("坚果云账号", True),
    ("•••", True),
    ("未填写", True),
    ("隐藏密码", True),
    ("显示密码", True),
    ("收起", True),
    ("修改", True),
    # 新图标
    ("JzEye", True),
    ("JzEyeOff", True),
    ("JzLock", True),
    ("JzDevice", True),
    # 页面骨架
    ("云端备份列表", True),
    ("最新版", True),
    ("导出数据", True),
    ("导入数据", True),
    ("确认恢复这份备份？", True),
    # 旧命名必须清干净
    ("记账备份-", False),
    ("暂无备份文件", False),
]

# 资源名在 resources.arsc 的字符串池里
ARSC_MARKERS = ["ic_launcher_round", "ic_launcher_background", "ic_empty_backup"]

blobs = []
arsc = b""
res_png = 0
with zipfile.ZipFile(APK) as z:
    names = z.namelist()
    dexes = [n for n in names if n.endswith(".dex")]
    res_png = sum(1 for n in names if n.startswith("res/") and n.endswith(".png"))
    lines.append("  条目 %d 个，dex %d 个，res 下 PNG %d 张" % (len(names), len(dexes), res_png))
    for n in dexes:
        blobs.append(z.read(n))
    if "resources.arsc" in names:
        arsc = z.read("resources.arsc")
    if "assets/migration.json" in names:
        lines.append("  assets/migration.json 大小 %d 字节" % z.getinfo("assets/migration.json").file_size)
    else:
        lines.append("  !! assets/migration.json 缺失")

lines.append("=== dex 中搜索（命中即代码已进包） ===")
bad = 0
for m, want in DEX_MARKERS:
    b = m.encode("utf-8")
    hit = any(b in blob for blob in blobs)
    ok = (hit == want)
    if not ok:
        bad += 1
    note = "命中" if hit else "未命中"
    if not want:
        note += "（期望未命中）"
    lines.append("  %-24s %-14s %s" % (m, note, "" if ok else "<<< 不符合预期"))

lines.append("=== resources.arsc 中搜索（图标/插画资源名） ===")
for m in ARSC_MARKERS:
    b = m.encode("utf-8")
    ok = b in arsc
    if not ok:
        bad += 1
    lines.append("  %-24s %s" % (m, "命中" if ok else "!!! 未命中"))

lines.append("=== 源文件复核（防止被并发写入者覆盖） ===")
SRC = os.path.join(ROOT, "jizhang-native", "app", "src", "main", "java", "com", "jianji", "jizhang")
CHECK = {
    r"data\backup\BackupNaming.kt": ["localBackupFileName", "deviceName", "cloudArchiveFileName"],
    r"data\backup\LocalBackupStore.kt": ["localBackupFileName", "origin = BackupOrigin.fromTag"],
    r"data\backup\BackupWorker.kt": ["archive", "BackupOrigin.AUTO", "origin.tag"],
    r"data\backup\NutstoreConfig.kt": ["NutstoreSettings"],
    r"ui\backup\BackupScreen.kt": ["CredentialsCard", "maskEmail", "ORIGIN_COLORS"],
    r"ui\theme\JizhangIcons.kt": ["JzEyeOff", "JzLock", "JzDevice"],
    r"LedgerViewModel.kt": ["BackupOrigin.AUTO"],
}
for rel, keys in CHECK.items():
    p = os.path.join(SRC, rel)
    if not os.path.isfile(p):
        lines.append("  !! 不存在 %s" % rel)
        bad += 1
        continue
    txt = open(p, encoding="utf-8", errors="replace").read()
    lines.append("  %s (%s)" % (rel, time.strftime("%H:%M:%S", time.localtime(os.path.getmtime(p)))))
    for k in keys:
        ok = k in txt
        if not ok:
            bad += 1
        lines.append("      %-28s %s" % (k, "OK" if ok else "!!! 被改掉了"))

lines.append("")
lines.append("结论：%s" % ("全部通过" if bad == 0 else "%d 项不符合预期" % bad))

# 复制一份到工作区根，方便直接装机
for attempt in range(3):
    try:
        shutil.copy2(APK, DST)
        lines.append("已复制到 %s（%.2f MB）" % (DST, os.path.getsize(DST) / 1024 / 1024))
        break
    except Exception as e:
        lines.append("复制第 %d 次失败: %r" % (attempt + 1, e))
        time.sleep(3)

with open(OUT, "w", encoding="utf-8") as f:
    f.write("\n".join(lines) + "\n")

print("ok, bad=%d" % bad)
