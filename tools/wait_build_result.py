"""等另一个会话的构建出结果。

轮询工作区里最新的构建日志，找 BUILD SUCCESSFUL / BUILD FAILED。
只要日志在写或还有 java.exe，就继续等。
"""
import os
import subprocess
import sys
import time

ROOT = r"C:\Users\Administrator\WorkBuddy\记账软件"
OUT = os.path.join(ROOT, "_wait_result.txt")
ROUNDS = int(sys.argv[1]) if len(sys.argv) > 1 else 12
INTERVAL = int(sys.argv[2]) if len(sys.argv) > 2 else 20


def candidates():
    out = []
    for d in (ROOT, os.path.join(ROOT, "jizhang-native")):
        try:
            for n in os.listdir(d):
                if n.endswith(".log") and os.path.isfile(os.path.join(d, n)):
                    p = os.path.join(d, n)
                    out.append((os.path.getmtime(p), p))
        except OSError:
            pass
    out.sort(reverse=True)
    return out[:3]


def has_java():
    try:
        r = subprocess.run(
            ["powershell", "-NoProfile", "-Command",
             "@(Get-CimInstance Win32_Process -Filter \"Name='java.exe'\").Count"],
            capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=60,
        )
        return (r.stdout or "").strip() not in ("", "0")
    except Exception:
        return False


lines = []
verdict = "TIMEOUT"
for i in range(ROUNDS):
    time.sleep(INTERVAL)
    jp = has_java()
    note = []
    for mt, p in candidates():
        try:
            txt = open(p, encoding="utf-8", errors="replace").read()
        except OSError:
            continue
        tag = None
        if "BUILD SUCCESSFUL" in txt:
            tag = "SUCCESS"
        elif "BUILD FAILED" in txt:
            tag = "FAILED"
        note.append("%s=%s(%s)" % (os.path.basename(p), tag or "…",
                                   time.strftime("%H:%M:%S", time.localtime(mt))))
        if tag == "SUCCESS" and not jp:
            verdict = "SUCCESS:" + p
            break
    lines.append("%s java=%s  %s" % (time.strftime("%H:%M:%S"), "有" if jp else "无", "  ".join(note)))
    if verdict.startswith("SUCCESS"):
        break

lines.append("VERDICT=" + verdict)
with open(OUT, "w", encoding="utf-8") as f:
    f.write("\n".join(lines) + "\n")
print("\n".join(lines))
