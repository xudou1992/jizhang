"""观察工作区是否还有别的会话在写代码。

只看两件事：.kt 文件的 mtime 是否还在变、有没有 java.exe 在跑。
连续采样 N 次，最后一次变化的时间点写进结果文件。
"""
import os
import subprocess
import sys
import time

ROOT = r"C:\Users\Administrator\WorkBuddy\记账软件"
SRC = os.path.join(ROOT, "jizhang-native", "app", "src", "main", "java", "com", "jianji", "jizhang")
OUT = os.path.join(ROOT, "_watch_other.txt")

ROUNDS = int(sys.argv[1]) if len(sys.argv) > 1 else 8
INTERVAL = int(sys.argv[2]) if len(sys.argv) > 2 else 15


def snapshot():
    out = {}
    for dirpath, _, files in os.walk(SRC):
        for f in files:
            if f.endswith(".kt"):
                p = os.path.join(dirpath, f)
                out[os.path.relpath(p, SRC)] = os.path.getmtime(p)
    return out


def java_procs():
    try:
        r = subprocess.run(
            ["powershell", "-NoProfile", "-Command",
             "(Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | "
             "Select-Object -ExpandProperty CommandLine) -join ' | '"],
            capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=60,
        )
        return (r.stdout or "").strip()
    except Exception as e:
        return "probe failed: %r" % e


lines = []
prev = snapshot()
last_change = time.time()
lines.append("start %s  共 %d 个 .kt" % (time.strftime("%H:%M:%S"), len(prev)))

for i in range(ROUNDS):
    time.sleep(INTERVAL)
    cur = snapshot()
    changed = [k for k in cur if prev.get(k) != cur[k]]
    added = [k for k in cur if k not in prev]
    jp = java_procs()
    if changed or added:
        last_change = time.time()
        lines.append("%s  !! 有写入: %s%s   java=%s" % (
            time.strftime("%H:%M:%S"), changed[:6], (" +新增 " + str(added[:4])) if added else "",
            "有" if jp else "无"))
    else:
        lines.append("%s  静止（已静止 %.0fs）  java=%s" % (
            time.strftime("%H:%M:%S"), time.time() - last_change, "有" if jp else "无"))
    prev = cur

lines.append("")
lines.append("最后写入距结束: %.0f 秒" % (time.time() - last_change))
lines.append("VERDICT=%s" % ("OTHER_ACTIVE" if time.time() - last_change < 60 else "QUIET"))

with open(OUT, "w", encoding="utf-8") as f:
    f.write("\n".join(lines) + "\n")
print("\n".join(lines))
