"""等另一个并发构建结束（它跑的是本工程 _tools/build_native.py，日志 _build_native.log）。

并发的原因是工作区里有另一个 agent 会话在做别的功能；两个 Gradle 同时写 app/build
会互相踩（实测报 "Could not close incremental caches"）。所以这里只等，不去杀。
"""
import os
import time

ROOT = r"C:\Users\Administrator\WorkBuddy\记账软件"
LOG = os.path.join(ROOT, "_build_native.log")
OUT = os.path.join(ROOT, "_wait_result.txt")

deadline = time.time() + 780  # 13 分钟
done = False
while time.time() < deadline:
    if os.path.isfile(LOG):
        with open(LOG, encoding="utf-8", errors="replace") as f:
            txt = f.read()
        if "EXIT=" in txt:
            done = True
            break
    time.sleep(10)

lines = ["done=%s  waited_until=%s" % (done, time.strftime("%H:%M:%S"))]
if os.path.isfile(LOG):
    with open(LOG, encoding="utf-8", errors="replace") as f:
        content = f.read().splitlines()
    keys = ("BUILD SUCCESSFUL", "BUILD FAILED", "EXIT=", "ELAPSED=", "START ", "> Task :app:compile",
            "> Task :app:assemble", "^e: ", "error:", "Could not close incremental",
            "plugin classpath entry", "What went wrong")
    lines.append("=== 关键行 ===")
    for ln in content:
        if any(k in ln for k in keys):
            lines.append(ln[:300])
    lines.append("=== 尾部 25 行 ===")
    lines.extend(ln[:300] for ln in content[-25:])

with open(OUT, "w", encoding="utf-8") as f:
    f.write("\n".join(lines) + "\n")
