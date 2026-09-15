# 简记 1.0.4 · UI 图标审计 + 全链路打通审计 + 美化清单

审计对象：`jizhang-native/app/src/main`（28 个 Kotlin 文件，`build/` 产物已排除）
基线：`2970dbe` + 未提交的 1.0.4 修复（P0 四项已在此前处理，本次复核确认）
日期：2026-09-14
验证：`:app:assembleDebug` **BUILD SUCCESSFUL**（2m 8s），产物 13 MB / versionName 1.0.4

---

## 结论速览

| 维度 | 结果 |
|---|---|
| 图标 | 29 个定义 → **28 个，引用率 100%**（删 1 个死图标、激活 2 个闲置图标、修 1 处混用） |
| 链路 | 12 条功能链路，**原断 5 条，已全部接通**；仍留 3 条结构性缺口（见下） |
| 美化 | 分段控件三页三色 → 统一走语义令牌；灰值 4 处硬编码 → 1 个令牌；卡片形状/按钮圆角/返回键风格统一 |

**先确认一件事**：`docs/代码体检报告-1.0.3.md` 里的 4 条 P0 在当前工作区**已经修好了**
（凭据已清空、`parseYuanToCents` 已抽、`toStoredLong()` 已统一、`SeedMarker` 已引入），
只是还没提交。本次审计基于**当前真实代码**，不照搬旧结论。

---

## 一、图标审计

### 1.1 清单

自绘图标集 `JizhangIcons` 原有 29 个。逐个比对引用点后：

| 处置 | 图标 | 原因 |
|---|---|---|
| **删除** | `Folder` | 全项目 0 引用。空态已由 `Empty` 承担，属于纯死代码 |
| **激活** | `Calendar` | 原本 0 引用 —— 画好了却没人用。现已用于**日期选择器**（补记入口） |
| **激活** | `Wallet` | 原本 0 引用。现已用于设置页「账户管理」行首图标 |
| **修混用** | `ArrowBack` | `ExportScreen` 用的是 Material 的 `Icons.AutoMirrored.Filled.ArrowBack`，笔画粗细与自绘集不一致，是全局唯一一处混用。已换成 `JizhangIcons.ArrowBack` |

### 1.2 结果

**28 个图标，28 个有引用点，0 个死图标。**

按语义分组（全部在用）：

| 组 | 图标 |
|---|---|
| 底栏 | Home · Bill · Plus · Stats · Settings |
| 导航 | ArrowBack · ChevronLeft · ChevronRight · Close · MoreVert |
| 操作 | Search · Edit · Trash · Check · Rotate |
| 备份 | Cloud · CloudUpload · Upload · Download · Doc · Lock · Eye · EyeOff · Device |
| 数据 | Wallet · Calendar · Empty · Palette |

> 顺带说明为什么不用 `material-icons-extended`：那个包 11,398 个图标类，
> 会把 dex 撑大约 42 MB。`build.gradle.kts` 里仍保留 `material-icons-core` 是**有意的**
> —— 它的注释写明是为了把 material3 传递依赖的版本从 1.6.0 钉到缓存里已有的 1.6.8，
> 删掉会在离线环境解析失败。所以只换调用点，不动依赖。

---

## 二、全链路打通审计

### 2.1 逐条链路状态

| # | 链路 | 原状态 | 处置 |
|---|---|---|---|
| 1 | 底栏「+」→ 记一笔 → 保存 → Room → 首页/账单页刷新 | ✅ 通 | — |
| 2 | **记账时选择账户** | ❌ **断**：`addTx` 里账户永远写死 `accounts.first()`，`AddScreen` 没有账户控件 | ✅ **已接通** |
| 3 | **补记 / 改日期** | ❌ **断**：`addTx` 写死 `System.currentTimeMillis()`，详情页编辑态固定回传原值，全 App 无日期入口 | ✅ **已接通** |
| 4 | 首页「最近账单」→ 详情 | ✅ 通 | — |
| 5 | **账单 tab 列表 → 详情** | ❌ **断**：`TxRow` 的 `onClick = { }` 是空实现，`BillListScreen` 没有 `onOpenTx` 参数，`MainActivity` 也没接 | ✅ **已接通** |
| 6 | 搜索 → 详情 | ✅ 通 | — |
| 7 | 长按 → 复制一笔 → 预填记一笔 | ⚠️ 半通：不预填账户 | ✅ 已补账户预填 |
| 8 | 分类管理 增/改/删 | ✅ 通（颜色转换已修） | — |
| 9 | 账户管理 增/改/删 | ⚠️ 能存，但记账侧不消费，余额恒 0 | ✅ 随 #2 打通 |
| 10 | 统计：月份切换 / 收支切换 / 环形 / 排行 / 趋势 | ⚠️ 有重复：4 张图里 2 张是同一件事 | ✅ 删 1 张 |
| 11 | 导出 CSV | ✅ 通 | 视觉已统一 |
| 12 | 本地备份 / 恢复 / 坚果云自动备份 | ⚠️ 防抖逻辑两处漏洞 | ✅ 已修 |
| 13 | **深色 / 纯黑 AMOLED** | ❌ **断**：`trueBlack` 参数、`PureBlack` 令牌、`JizhangTheme.isDark` 全部 0 引用，设置页没有入口 | ✅ **已接通** |

### 2.2 本次接通的 5 条断链

**① 账单列表点不动**（`BillListScreen.kt` / `MainActivity.kt`）

首页点一条账单能进详情，账单 tab 点了**什么都不发生** —— 同一个 App 两种相反的交互习惯。

- `BillListScreen` 新增 `onOpenTx` 参数，`TxRow` 的 `onClick` 从空实现改为回调
- `MainActivity` 的 `AppTab.BILLS` 分支接上已有的 `Overlay.DETAIL` 通路
- 提示文案同步改为「点按查看详情，长按可复制或删除」

**② 不能补记 / 改日期**（新增 `ui/components/Pickers.kt`）

昨天的饭今天才想起来记，只能记成今天。`JizhangIcons.Calendar` 早就画好了，一直没人用。

- 新增 `DateField`：点击弹 Material3 日历，`JizhangIcons.Calendar` 做前缀
- 关键细节：**只换日期、保留原时刻**。合并函数按「本地日期 + 原时分秒」重组，
  否则改一笔昨天 21:30 的账，时间会被抹成 00:00，排序和「今天/昨天」分组全乱
- 时区处理：Material3 的 `selectedDateMillis` 是「UTC 当日 0 点」，
  必须先按 `ZoneOffset.UTC` 解出日历日，再按系统时区落库，否则会整体差一天
- `AddScreen` 和 `TxDetailScreen` 编辑态都接上了

**③ 多账户半截**（`AddScreen.kt` / `LedgerViewModel.kt`）

能建多个账户，但记账时账户永远写死第一个 —— 新建的账户永远收不到账单。

- 新增 `AccountSelector`（FilterChip 横滑，带账户色点）
- **只在账户数 > 1 时显示**：只有一个账户时它没有可选项，纯噪音
- `addTx` / `updateTx` 改为吃 `TxDraft`，账户兜底顺序：
  表单明确选的 → 原账户（编辑场景）→ 第一个账户
- 详情查看态新增「账户」信息行（同样只在多账户时显示）
- 设置页副标题从「多账户与总资产」改为「多账户与总资产，记账时可选择账户」—— 文案与能力对齐

**④ 纯黑 AMOLED 无入口**（新增 `ui/theme/UiPrefs.kt`）

`Theme.kt` 的 `trueBlack` 参数、`Color.kt` 的 `PureBlack` 令牌实现得很完整，但引用数全是 0。

- 新增 `UiPrefs.kt`：DataStore 名称 `ui`（不能和 `settings`/`backup`/`seed` 重名，同名同进程会崩）
- 设置页「外观」组新增开关
- `MainActivity` 读 flow 并传给 `JizhangTheme(trueBlack = ...)`
- 浅色模式下该开关被 `JizhangTheme` 自动忽略（只作用于深色方案）

**⑤ 自动备份防抖两处漏洞**（`LedgerViewModel.kt`）

```kotlin
// 改前：开关在 delay 之前读 —— 3 分钟窗口内关掉自动备份，这次上传照样会发出去
val enabled = app.nutstoreSettingsFlow().first().enabled
if (!enabled) return@launch
delay(AUTO_BACKUP_DEBOUNCE_MS)

// 改后：防抖结束再读一次
delay(AUTO_BACKUP_DEBOUNCE_MS)
if (!app.nutstoreSettingsFlow().first().enabled) return@launch
```

另一处：`updateTx()` 没有调 `scheduleAutoBackup()`，而 `addTx()` / `deleteTx()` 都有 ——
**编辑一笔账单不会触发同步**。已补上。

### 2.3 顺手修掉的两个隐患

**「本月」筛选只有下界。** `monthStartMillis()` 在首页、账单页、导出页**各写了一遍**（三份重复代码），
而且三处都是 `dateTime >= monthStart` —— 未来月份会被算进「本月」。过去不能记未来日期所以没暴露，
但**一旦支持补记（链路 #3）就会立刻出问题**，属于必须同时修的连带项。

已抽出 `data/DateRange.kt`，**成对**提供 `todayStart/todayEnd/monthStart/monthEnd`，
调用方用 `in start..end` 就不会漏掉上界。

**`POST_NOTIFICATIONS` 是多余权限。** Manifest 里声明了，但全项目没有任何 `Notification` 调用
（`BackupWorker` 只写 DataStore，不弹通知）。已删除。构建产物已确认该权限消失。

---

## 三、UI 美化清单

### 3.1 一致性收敛（都是"同一件事在多处各写一遍"）

| 项 | 改前 | 改后 |
|---|---|---|
| 中性头像灰 | `Color(0xFF9CA0AB)` 在**4 处**各写一遍，且与主题里最接近的 `TextTertiary`(0xFF8B93A1) 并不相等 —— 凭空多出第 5 种灰 | 新增 `NeutralAvatar` 令牌，4 处统一引用 |
| 收支分段控件配色 | **三个页面三种取值**：详情页走令牌 ✅、`AddScreen` 写死 `#FCEBEB`/`#EAF3EE` ❌、`StatsScreen` 同样写死 ❌ | 三页统一走 `semantic.expenseContainer` / `incomeContainer` |
| 卡片圆角 | 首页用 `shapes.large`(20dp)，统计页写死 `RoundedCornerShape(16.dp)` | 统计页统一走 `MaterialTheme.shapes.large` |
| 按钮圆角 | `AddScreen` 14dp、`ExportScreen` 14dp、详情页 `shapes.medium`(16dp) | 统一走 `MaterialTheme.shapes.medium` |
| 返回键 | `ExportScreen` 用 Material 图标 + 默认 IconButton 尺寸，其余页用自绘图标 | 统一为自绘 `ArrowBack` + 24dp，与搜索页同款 |
| 表头正负号 | 同一份列表里月份头写「支出 ¥x」、日期头写「支出 -x」 | 统一为「支出 ¥x / 收入 ¥y」 |
| 应用名 | `strings.xml` 是「记账软件」，README/备份文件名/设置页一律「简记」 | 统一为「简记」 |
| 月份边界 | 三个文件各有一份 `monthStartMillis()` | 收进 `data/DateRange.kt` |

### 3.2 视觉打磨

- **底栏**：加一条发丝分隔线把内容区和导航区分开；选中项图标 20→22dp + 标签加粗，
  未选中走 `NeutralAvatar` 令牌。选中态从"只有颜色差"变成"颜色 + 字号 + 字重"三重区分
- **统计页**：删掉重复的 `MonthlyTrendCard`。原来 4 张图里，
  `TrendCard`（跟随月份选择器）和 `MonthlyTrendCard`（固定锚定当前月）是同一件事的两种实现 ——
  用户切到 3 月时一张跟着变、一张不动，只会让人以为数据错了。现在只剩跟随选择器的那张
- **统计页**：`slices.take(10)` 是无效代码（`slices` 上游已收敛为最多 6 项），已删
- **导出页**：补上 `background`、标题行与搜索页对齐、范围切换走品牌色
  `primaryContainer`（「本月」不是支出语义，不该用红色容器）
- **记一笔**：分段控件配色与详情页对齐；账户与日期作为独立小节插入分类网格之后

### 3.3 顺手做的结构重构

`AddScreen` 的保存回调原本是**六个位置参数** `(categoryId, isExpense, amountCents, note, ...)`，
详情页是五个 —— 参数一多，调用方很容易把 `isExpense` 和 `amountCents` 写反，
而且每加一个字段（日期、账户）都要改三处签名。

已抽出 `data/TxDraft.kt`，改成具名字段对象。以后加字段只动一处。

---

## 四、验证

```
> Task :app:compileDebugKotlin
> Task :app:packageDebug
> Task :app:assembleDebug

BUILD SUCCESSFUL in 2m 8s
38 actionable tasks: 16 executed, 22 up-to-date
```

产物：`app/build/outputs/apk/debug/app-debug.apk`（13 MB）

```
package: name='com.jianji.jizhang' versionCode='5' versionName='1.0.4'
minSdkVersion:'28'  targetSdkVersion:'34'
uses-permission: android.permission.INTERNET      ← POST_NOTIFICATIONS 已消失
```

---

## 五、改动文件（19 个）

**新增 4 个**

| 文件 | 作用 |
|---|---|
| `data/TxDraft.kt` | 记账表单产物，替掉六参数回调 |
| `data/DateRange.kt` | 今天/本月的成对上下界，三处重复代码收口 |
| `ui/theme/UiPrefs.kt` | 纯黑开关的 DataStore（name=`ui`） |
| `ui/components/Pickers.kt` | `DateField` + `AccountSelector` 两个共享选择器 |

**修改 15 个**：`MainActivity` · `LedgerViewModel` · `AddScreen` · `TxDetailScreen` ·
`BillListScreen` · `HomeScreen` · `StatsScreen` · `TrendCards` · `ExportScreen` ·
`SettingsScreen` · `AppShell` · `theme/Color` · `theme/JizhangIcons` ·
`AndroidManifest.xml` · `res/values/strings.xml`

---

## 六、仍然存在的缺口（未动，需你定夺）

> **更新**：下表中的前三条已经处理完毕 —— `exportSchema = true` + `schemas/` 已落盘并入库、
> 6 个测试类 28 个用例全绿、日期选择器已限制未来日期。
> 详见 `测试与迁移-1.0.4.md`（含一个中文路径导致单测全部假失败的环境坑）。

这几条不是"断链"，是**结构性风险或未开发的功能**，改动面比上面大，先列出来：

| 级别 | 问题 | 说明 | 状态 |
|---|---|---|---|
| 高 | `fallbackToDestructiveMigration()` + `exportSchema = false` | 当前 `version = 1` 用不上迁移。但**今后任何一次加字段都会静默清空整个库** —— 608 笔种子 + 用户之后记的全部，而且 schema 历史没留档。建议现在就把 `exportSchema = true` 打开并提交 `schemas/` | ✅ 已修 |
| 中 | 零测试 | `app/src` 下没有 `test` 源集。金额解析、颜色 ↔ Long 转换、备份文件名正则都是纯函数，各写几行就能永久拦住回归 | ✅ 已补（28 用例） |
| 中 | 转账语义空转 | `Transfer` / `transferContainer` / `ExpenseContainerDark` 等令牌定义了但全项目 0 引用，也没有转账功能。要么做，要么删令牌 | 待定夺 |
| 低 | 未来日期可选 | 新的日期选择器没限制 `selectableDates`，可以选到明天。对记账 App 通常不想要，但没做限制（「本月」筛选已加上界，不会被污染） | ✅ 已修 |
| 低 | 主题令牌闲置 | `SurfaceVariantStrong` · `OutlineStrong` · `TextTertiary` · `BrandDark` · `LocalDarkTheme` · `JizhangTheme.isDark` 引用数仍为 0 | 待定夺 |
