"""记账软件 · 新骨架构建跑测
沙箱里 cmd.exe 被禁，所以不走 gradlew.bat，直接 java 调 wrapper 的 main class。
用 subprocess 抓输出（PowerShell 的 `*>` 会把 stderr 吞成 truncated NativeCommandError）。
"""
import os
import subprocess
import sys
import time

ROOT = r"C:\Users\Administrator\WorkBuddy\记账软件"
PROJ = os.path.join(ROOT, "jizhang-native")
LOG = os.path.join(ROOT, "_spike_build.log")
JAVA = r"C:\Program Files\Microsoft\jdk-17.0.18.8-hotspot\bin\java.exe"
WRAPPER = os.path.join(PROJ, "gradle", "wrapper", "gradle-wrapper.jar")

env = os.environ.copy()
env["GRADLE_USER_HOME"] = os.path.join(ROOT, ".gradle-home")
env["JAVA_HOME"] = r"C:\Program Files\Microsoft\jdk-17.0.18.8-hotspot"
env["ANDROID_HOME"] = os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk")
env["ANDROID_SDK_ROOT"] = env["ANDROID_HOME"]

args = [
    JAVA, "-Xmx512m",
    "-Dfile.encoding=UTF-8",
    "-classpath", WRAPPER,
    "org.gradle.wrapper.GradleWrapperMain",
    ":app:assembleDebug",
    "--console=plain",
    "--stacktrace",
]

t0 = time.time()
with open(LOG, "w", encoding="utf-8", errors="replace") as f:
    f.write("CMD: %s\n" % " ".join(args[1:]))
    f.write("GRADLE_USER_HOME=%s\n" % env["GRADLE_USER_HOME"])
    f.write("ANDROID_HOME=%s\n" % env["ANDROID_HOME"])
    f.write("START %s\n" % time.strftime("%Y-%m-%d %H:%M:%S"))
    f.flush()
    p = subprocess.run(
        args, cwd=PROJ, env=env,
        stdout=f, stderr=subprocess.STDOUT,
        text=True, encoding="utf-8", errors="replace",
    )
    f.write("\nEXIT=%d\n" % p.returncode)
    f.write("ELAPSED=%.1fs\n" % (time.time() - t0))
    f.write("END %s\n" % time.strftime("%Y-%m-%d %H:%M:%S"))

sys.exit(0)
