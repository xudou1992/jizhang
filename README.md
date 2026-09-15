# 简记

一个给自己用的安卓记账 App。所有数据只存在本机，不经过任何自己的服务器；
可选开着坚果云（WebDAV）做每日自动备份。

当前版本 **1.0.5**（versionCode 6）。

## 技术栈

Kotlin 2.0.20 · Jetpack Compose 1.6.8 · Material3 · Room 2.6.1（KSP）
· WorkManager 2.9.1 · DataStore · Ktor + OkHttp

Gradle 8.9 / AGP 8.6.1，compileSdk 35 / buildTools 35.0.1 / targetSdk 34，JDK 17。
单模块结构，只有一个 `:app` —— 改一行 UI 到出包约 30 秒。

## 目录

```
jizhang-native/     Android 工程（唯一在维护的代码）
tools/              构建、校验、素材处理的脚本
assets-src/         App 图标与空态插画的 AI 生成原图
```

## 构建

```bash
./gradlew :app:assembleDebug
```

产物在 `jizhang-native/app/build/outputs/apk/debug/app-debug.apk`。

如果想在本机（Windows）构建，需要设好：

```powershell
$env:ANDROID_HOME     = "$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:JAVA_HOME        = "<JDK 17 路径>"
```

`tools/build_native.py` 封装了这套流程（绕过 `gradlew.bat`，直接用 java 调 wrapper 的
main class，并把输出写进日志文件）。`tools/verify_apk_daily.py` 是把 APK 当 zip 拆开、
进 dex 里搜字节来确认改动真的进了包 —— 不只看「编译通过」。

## 备份机制

文件名一律带 **设备名 + 来源 + 时间戳**，一眼能看出是哪台机器、自动还是手点、什么时候。

本机：`简记-<设备>-<来源>-<yyyyMMdd-HHmmss>.json`

坚果云 `/jianji` 目录：

| 路径 | 性质 |
|---|---|
| `jianji-backup.json` | 最新版，**刻意覆盖**，给「一键恢复最新」用 |
| `auto/<设备>-<时间戳>.json` | 每日定时任务写的，只增不删 |
| `manual/<设备>-<时间戳>.json` | 手动点「立即备份一次」写的，只增不删 |

来源有三种：`manual`（手工）、`auto`（自动）、`safety`（恢复前自动留底的现状快照）。
记账后的防抖同步只刷新最新版、不写归档，避免一天几十份撑爆网盘。
恢复前会把本机现状留底一份，恢复错了能找回。

**凭据不入库、不入代码。** 坚果云账号与应用密码只存在本机的 DataStore 里，由用户在
备份页自行填写；源码中不保留任何默认值 —— 本仓库是公开的，写死的凭据等于公开。

## 素材

App 图标与空态插画由 AI 生成（原图在 `assets-src/`），再由 `tools/make_icon_assets.py`
裁成各密度资源。生成图右下角带水印，脚本按比例裁掉底部。

图标那张原图是**黑底白线的 RGB 图、没有真 alpha**，所以必须「亮度即 alpha」；
插画那张是白底，按「离白距离」阈值化抠图。直接读 alpha 通道会得到一张纯白方块。
有新素材时先跑 `tools/probe_assets.py` 探一下真实像素结构。
