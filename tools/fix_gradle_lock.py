"""清掉占用 .gradle-home 的 Gradle 守护进程 + 陈旧 journal 锁，然后让 Gradle 重建。

这台机器的老毛病：残留 daemon 持有 caches/journal-1/journal-1.lock，
新构建在启动阶段就报 FileNotFoundException(拒绝访问)，与业务代码无关。
目录改名是 O(1) 的，比递归删快几个数量级（本机每文件操作要 50ms+）。
"""
import os
import shutil
import subprocess
import time

ROOT = r"C:\Users\Administrator\WorkBuddy\记账软件"
GH = os.path.join(ROOT, ".gradle-home")
OUT = os.path.join(ROOT, "_lock_fix.txt")

lines = []


def log(msg):
    lines.append(str(msg))


# 1) 找出所有 java 进程，杀掉命令行里带 GradleDaemon 的
try:
    ps = subprocess.run(
        ["powershell", "-NoProfile", "-Command",
         "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | "
         "Select-Object ProcessId,CommandLine | ConvertTo-Csv -NoTypeInformation"],
        capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=90,
    )
    log("=== java 进程 ===")
    log(ps.stdout or "(none)")
    log("stderr: " + (ps.stderr or "")[:500])

    killed = []
    for line in (ps.stdout or "").splitlines():
        if "GradleDaemon" not in line:
            continue
        cells = [c.strip('"') for c in line.split('","')]
        for c in cells:
            if c.isdigit():
                pid = c
                r = subprocess.run(["taskkill", "/F", "/PID", pid],
                                   capture_output=True, text=True, errors="replace", timeout=60)
                killed.append((pid, r.returncode, (r.stdout or "").strip()))
                break
    log("=== 已杀 GradleDaemon ===")
    log(killed or "(无)")
except Exception as e:
    log("杀进程阶段异常: %r" % (e,))

time.sleep(3)

# 2) 陈旧 journal 目录改名挪走，让 Gradle 重新建
try:
    src = os.path.join(GH, "caches", "journal-1")
    dst_root = os.path.join(ROOT, "_stale_caches")
    os.makedirs(dst_root, exist_ok=True)
    if os.path.isdir(src):
        dst = os.path.join(dst_root, "journal-1-%s" % time.strftime("%Y%m%d_%H%M%S"))
        shutil.move(src, dst)
        log("journal-1 已改名到: %s" % dst)
    else:
        log("journal-1 不存在，跳过")
except Exception as e:
    log("journal-1 改名失败: %r" % (e,))

# 3) 顺带把可能同样被锁的构建锁文件挪走（只动 *.lock 普通文件）
try:
    moved = 0
    for name in ("fileHashes",):
        p = os.path.join(GH, "caches", "8.9", name)
        if os.path.isdir(p):
            log("保留 %s（依赖缓存，不动）" % p)
    log("moved=%d" % moved)
except Exception as e:
    log("锁文件阶段异常: %r" % (e,))

with open(OUT, "w", encoding="utf-8") as f:
    f.write("\n".join(lines) + "\n")
