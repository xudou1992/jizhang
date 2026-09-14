"""并发构建探测：谁在动 jizhang-native / .gradle-home，以及产物时间线。"""
import os
import subprocess
import time

ROOT = r"C:\Users\Administrator\WorkBuddy\记账软件"
OUT = os.path.join(ROOT, "_concurrency_probe.txt")
lines = []


def log(m):
    lines.append(str(m))


ps = subprocess.run(
    ["powershell", "-NoProfile", "-Command",
     "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | "
     "Select-Object ProcessId,ParentProcessId,CreationDate,CommandLine | "
     "ConvertTo-Csv -NoTypeInformation"],
    capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=120,
)
log("=== java.exe ===")
log(ps.stdout or "(none)")
log("stderr: " + (ps.stderr or "")[:800])

log("\n=== 产物 / 构建目录时间线 ===")
for rel in [
    r"jizhang-native\app\build\outputs\apk\debug\app-debug.apk",
    r"jizhang-native\app\build\kotlin\compileDebugKotlin\cacheable\caches-jvm",
    r".gradle-home\caches\journal-1",
]:
    p = os.path.join(ROOT, rel)
    if os.path.exists(p):
        st = os.stat(p)
        log("%-70s %s  size=%s" % (rel, time.strftime("%H:%M:%S", time.localtime(st.st_mtime)), st.st_size))
    else:
        log("%-70s (不存在)" % rel)

log("\n=== gradle.properties ===")
gp = os.path.join(ROOT, "jizhang-native", "gradle.properties")
if os.path.isfile(gp):
    with open(gp, encoding="utf-8", errors="replace") as f:
        log(f.read())
else:
    log("(没有)")

with open(OUT, "w", encoding="utf-8") as f:
    f.write("\n".join(lines) + "\n")
