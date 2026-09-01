# 全局悬浮字幕（网易云「桌面歌词」式）实施方案

> 创建：2026-08-29
> 状态：**步骤 0~4 全部完成并编译通过；步骤 5（真机全量验证）待做，验证延后**。
> 目标设备：Redmi K50（MIUI / arm64-v8a），包 `app.mihon.dev`
>
> 2026-08-30 进度：
> - 步骤 0 完成。字幕状态下沉到 `AudioPlayerController`，`AudioSubtitleState` 挪到 `data/audio/`。
> - 步骤 1 完成。Manifest 权限、`SYSTEM_ALERT_WINDOW` 引导流程、悬浮窗骨架（addView/removeView）
>   全部就位，播放页「词」按钮可用。
> - **步骤 0 + 1 已装机验证通过**（A/B/C/D 四组用例全过）。
> - 步骤 2 完成。悬浮窗换成真正的单行字幕（marquee + 跟随进度），**待装机验证**。
> - 步骤 3 完成。拖动 + 位置记忆 + 小锁/× 图标组 + 锁定穿透，**待装机验证**。
> - 步骤 4 完成。播放器页可见时窗口隐藏，**待装机验证**。
> - 与设计的偏离见第 10.1 节（三处）、10.9 节（步骤 3 的窗口拆分）与 10.11 节（步骤 4 的
>   「可见」怎么判定）；MIUI 结论见第 10.6 节。

**2026-08-29 用户确认的决策**：
1. 悬浮窗权限：接受（需跳系统设置页手动授予）
2. 样式：贴顶窄条，**高度恒定 1 行**，只显示当前这句（不是 5 行、也不是 2 行）；长台词短句静止、超宽横向滚动
3. 可见性：仅播放器页可见时隐藏；App 内其它页面与后台均显示
4. 入口与默认值：播放页底部按钮行加「词」按钮，**默认关闭**
5. 拖动与锁定：可拖动 + 记忆位置；可锁定（锁定后触摸穿透）；**小锁图标与「词」按钮都能解锁**
6. 通知栏：**不加**「词」按钮，保持现有 3 action（用户认为展开态 4 个不简洁）
7. 角落图标：未锁定 `[锁][×]`，锁定 `[锁]`（× 隐藏防误触）。× = 持久关闭
8. 与设计源站 Kikoeru（`LyricsBar.vue`）的**两处有意差异**：长台词用横向滚动而非省略号截断；默认置顶而非置底。理由见第 9 节

---

## 1. 目标与非目标

### 目标
- 播放 ASMR 时，字幕以悬浮窗形式浮在**所有界面之上**（桌面、其它 App、阅读器）。
- 退出到后台、锁屏外仍持续显示并跟随播放进度。
- 可拖动、位置记忆、可开关。

### 非目标（本次不做）
- **不做** App 内画中画 / 悬浮播放控件（指保留播放/暂停悬浮按钮）。
- **不做** 歌词逐字高亮（卡拉 OK 式进度染色）。源数据 `LyricLine` 只有整行时间戳，没有逐字时间。
- **不做** 锁屏界面上的字幕（锁屏由系统控制，第三方悬浮窗在锁屏上通常用不了，MIUI 尤其）。
- **不做** 悬浮窗内嵌翻译（仓库约定：除非用户重新明确提出，不加入 AI 翻译）。

---

## 2. 前置发现（决定了方案形态）

| 发现 | 影响 |
|---|---|
| 字幕的下载+解析写在 `AudioPlayerScreen` 的 `LaunchedEffect` 里，存 `remember { mutableStateOf }` | **离开播放页字幕即丢失**，悬浮窗够不着。必须先把字幕状态下沉 |
| `AudioPlayerController` 是 Injekt 单例（`AppModule:152`），已注入 `KikoeruApi` | 下沉不需要改 DI，也不用新建仓库 |
| `SubtitleParser` 已支持 LRC / VTT / SRT / ASS，并有单测 | 解析层零成本复用 |
| `ViewExtensions.kt:42` 已有 `ComposeView.setComposeContent` 封装（含 `TachiyomiTheme`） | 渲染形态可照抄，主题配色不用重做。但策略要换（见 5.3） |
| `AudioPlaybackService` 已是前台服务（`foregroundServiceType="mediaPlayback"`），持有 controller | 悬浮窗挂在它上面，不新增第二个服务 |
| Manifest **无** `SYSTEM_ALERT_WINDOW` | 需新增，且无法运行时弹窗授予 |

---

## 3. 核心设计

### 3.1 字幕状态下沉（第 0 步，必须先做）

**动机**：字幕生命周期 == 当前 track 的生命周期。`AudioPlayerController` 已经精确知道 track 何时切换（`onMediaItemTransition` / `updateCurrentItem`），是天然的数据归属方。按仓库约定「不另建平行实现或第二份事实来源」，不下沉到 Controller 而新建仓库会制造双份状态。

**改动**：

1. `AudioPlayerState` 增加三个字段：
   ```kotlin
   val lyrics: List<LyricLine> = emptyList(),
   val subtitleState: AudioSubtitleState = AudioSubtitleState.NOT_AVAILABLE,
   ```
2. `AudioPlayerController` 增加：
   - `private var subtitleJob: Job? = null`
   - `private fun loadSubtitles(item: AudioPlayItem)`：cancel 旧 job → `withIOContext { api.fetchSubtitle(...) }` → `SubtitleParser.parse` → `publish(state.copy(...))`
   - `fun retrySubtitles()`：供 UI 的重试按钮调用
   - 在 `updateCurrentItem()` 末尾触发 `loadSubtitles`
   - `release()` 里 cancel + 清空
3. `AudioPlayerScreen`：删掉自己的加载逻辑与 `lyrics` / `subtitleState` state，改为从 `controller.state` 读；`onRetrySubtitle` 改为调 `controller.retrySubtitles()`。
4. `AudioSubtitleState` 枚举从 Screen 文件移到 `data/audio/` 下（UI 与 Controller 都要引用，不能留在 UI 层）。

**注意**：`AudioSubtitleState` 目前定义在 `AudioPlayerScreen.kt:174`，是 UI 文件。Controller 引用它属于「数据层引用 UI 层」，必须先挪。

**回归面**：播放器页面的字幕加载/重试/空态/错误态。**改完必须人工过一遍这四种状态**。

### 3.2 悬浮窗服务挂在哪

**决定：挂在 `AudioPlaybackService` 上，不新建服务。**

理由：
- 悬浮窗只在播放时有意义，生命周期与播放完全重合。
- 若新建独立服务，Android 会要求它也有自己的前台通知（同 ID 共通知可行但易出竞态），且需额外同步「何时启动/停止」。
- 服务已在运行，不增加冷启动成本。

服务内职责：
- `onCreate` 看 preference 决定要不要显示
- 监听到 `state.item` 变化 → 更新内容
- `onDestroy` / `release()` → 必须 `windowManager.removeViewImmediate`

### 3.3 播放器页可见时隐藏（用户 2026-08-29 已确认）

规则：**只有播放器页可见时隐藏；App 内其它页面照常显示，退到后台也显示。**

即显示条件为：`已开启 && 有字幕 && 播放器页当前不可见`。

**如何判断「播放器页不可见」**：`ProcessLifecycleOwner` 只能区分 App 前后台，判断不了具体是哪个页面。需要一个页面级的可见标志：

- 在 `AudioPlayerController`（或独立的悬浮窗管理器）上维护 `playerScreenVisible: Boolean`
- `AudioPlayerScreen` 的 `Content()` 内用 `DisposableEffect(Unit)` 进入时置 `true`、`onDispose` 置 `false`
- 服务读取该标志决定是否 `removeView` / `addView`

> **2026-08-30 更正**：上面第二条照做是不够的 —— 从播放页按 Home 时组合并不销毁，`onDispose`
> 不触发，窗口会一直藏着，与「退到后台也显示」直接冲突。实际实现补了 Activity 可见性判断，
> 详见第 10.10 节。

> 注意：不能用「App 是否在后台」代替。用户明确要求 App 内其它页面（如书架、浏览页）也要能看到字幕。

### 3.4 悬浮窗内容（样式已确认：贴顶单行窄条）

**贴屏幕顶部、宽约屏宽 80%、高度恒定 1 行文字。**

- **只显示当前一句**，不做下一句预告。与源站 Kikoeru 的 `LyricsBar.vue` 一致（详见第 9 节）
- **高度永远固定 1 行**，不随内容变化（详见第 9 节）
- `maxLines = 1` + 复用既有 `marqueeTitle()`：短台词静止居中，**只有超宽才横向滚动**
- 半透明圆角背景，自适应深浅色；文字居中
- 默认位置：**贴屏幕顶部**（状态栏下方）。理由：底部是聊天输入框、手势条、虚拟按键的密集区，与用户「边看边操作别的 App」的场景直接冲突
  > 源站默认在底部（`absolute-bottom`），但那是桌面端；手机底部操作密集，故不跟随。且可拖动，默认值影响有限。

### 3.5 拖动、锁定与关闭（用户已确认，仿网易云）

| 状态 | 窗口触摸 | 角落图标 |
|---|---|---|
| 未锁定 | 整窗可拖动，拖动结束位置写入 `BasePreferences` | **锁 + ×** |
| 锁定 | 整窗 `FLAG_NOT_TOUCHABLE`，**触摸穿透**到下层 App | **仅锁**（× 隐藏） |

> 实现上这一行要求图标**单独占一个窗口**：`FLAG_NOT_TOUCHABLE` 是整窗生效的，加了它连角上的小锁
> 一起点不动；而不加它、只让空白区域不消费事件也不行 —— 事件派发到窗口后不会回落到下层 App。
> 详见第 10.9 节。

**角落图标组**（各约 20dp，位于窗口一端）：

- **锁**：点一下切换锁定/解锁。锁定态下它是**唯一可点区域**（其余区域穿透）。
- **×**：直接关闭悬浮字幕，**仅未锁定态显示**。

**为什么未锁定态要放两个图标**：若只放 ×，就没有「锁定」的入口了。锁与 × 是两个不同意图（锁=防误触，×=不要了），不能合并。

**为什么锁定态要把 × 藏起来**：锁定的目的就是防误触。锁定后窗口整体穿透，屏幕上唯一的实体就是那把锁——若此时还留着 ×，一次误触就把功能关掉了，与锁定意图相悖。想关闭就先解锁。

**× 的语义是持久的**：等同把播放页「词」开关置为**关**（写 preference），下次播放不会自动再弹。想要就回播放页重新开。
（「临时关掉这一次」不做：语义多一层就要多一个状态，且网易云的 × 也是持久的。）

- 锁定状态与位置都持久化。
- **解锁有两个入口，都做**：① 悬浮窗上的小锁图标 ② 播放页「词」按钮。锁定时点「词」也能解锁。
- 锁定时字幕**照常更新**，只是不响应触摸。
- 不做「折叠成更窄的横条」（高度本就只有 1 行，折叠无意义）。

---

## 4. 权限流程

`SYSTEM_ALERT_WINDOW` 无法运行时授予，必须跳系统设置页：

1. 用户点开悬浮字幕开关
2. `Settings.canDrawOverlays(context)` 为 false
3. 弹说明性对话框（讲清楚要去系统设置里开哪个开关，MIUI 上还可能要开「后台弹出界面」）
4. `startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))`
5. 回到 App（`onResume`）重新检查，已授予则落库并生效

**关键限制**：服务里不能跳设置页（没有 Activity 上下文承载），所以引导必须在 UI 层（播放器页或设置页）做，服务只负责「有权限就显示」。

**MIUI 额外风险**：K50 上除悬浮窗权限外，「后台弹出界面」是独立开关，只开前者可能仍不显示。需在装机验证阶段确认，并在引导文案里提示。

---

## 5. 技术风险与应对

### 5.1 MIUI 表现未知（最高风险）
代码正确不代表能显示。**必须在真机验证**，且验证项包括：权限授予后能否显示、退到后台能否保持、与其它 App 叠加时的层级、全屏场景下是否隐藏。

### 5.2 Compose 挂在 WindowManager 上的生命周期
`ViewExtensions.setComposeContent` 用的是 `DisposeOnViewTreeLifecycleDestroyed`，依赖 `ViewTreeLifecycleOwner`。悬浮窗没有 Activity，**必须换成 `DisposeOnDetachedFromWindow`**，否则 Attach 时即崩溃。

连带：没有 `ViewTreeSavedStateRegistryOwner` 时，Compose 内**不能用 `rememberSaveable`**。悬浮窗状态（锁定与否、位置）应存在外部（preference / 服务字段）而非 Composable 内。

### 5.3 内存泄漏
`ComposeView` 必须在 `onDestroy` / `release()` / 权限被撤销时 `removeViewImmediate`。否则整块 view 树与 service 互相持有，反复开关会累积泄漏。**这是本项目最需要警惕的点**（AGENTS.md 明确要求性能不下降）。

### 5.4 重组频率
controller 每 500ms publish 一次位置。悬浮窗**不能**每 500ms 重组。用 `derivedStateOf { lyrics.indexOfLast { it.timeMs <= position } }` 让重组只发生在实际换行的瞬间。

### 5.5 i18n
需新增约 3~5 条字符串（开关标题「词」、说明、权限引导）。注意仓库约定：尽量复用既有字符串，能不新增就不新增。

---

## 6. 分步计划

> 按仓库约定：**一次一个任务，做完找用户确认再做下一个**。

| 步骤 | 内容 | 体量 | 验证方式 | 状态 |
|---|---|---|---|---|
| **0** | 字幕状态下沉到 Controller；`AudioSubtitleState` 挪到 data 层；播放器 UI 改从 controller 读 | 中，有回归风险 | 编译 + **人工过字幕四种状态**（加载/就绪/空/错误） | ✅ 2026-08-30 完成，**装机已验证** |
| 1 | Manifest 加权限；悬浮窗服务骨架（addView / removeView）+ 权限检查与引导流程 | 中 | 编译 + 装机看权限页能否拉起 | ✅ 2026-08-30 完成，**装机已验证** |
| 2 | 悬浮窗 Compose UI（贴顶窄条 1 行 + marquee + 跟随进度） | 中 | 装机看显示与跟随 | ✅ 2026-08-30 完成，编译通过，**待装机验证** |
| 3 | 拖动、位置记忆、小锁 + × 图标组、锁定穿透、i18n | 中（比预估大，见 10.10） | 装机看拖动与锁定 | ✅ 2026-08-30 完成，编译通过，**待装机验证** |
| 4 | 播放器页可见时隐藏的逻辑 | 小 | 装机 | ✅ 2026-08-30 完成，编译通过，**待装机验证** |
| 5 | 真机全量验证（含 MIUI 后台限制） | — | 见 5.1 | **待做（验证整体延后，见文首）** |

> **交接提示**：**代码已全部写完（步骤 0~4），只剩步骤 5 的真机验证**。若中途换人，先看
> 第 10 节的「实现期发现」，尤其是 10.2（Compose 挂悬浮窗的四个 owner 及 `@JvmName` 坑）、
> 10.6（MIUI 实测结论）、10.9（步骤 3 为什么是两个窗口）和 10.11（步骤 4 的「可见」怎么判定）。
> 装机时要一次过掉 10.7（步骤 2）、10.9（步骤 3）、10.11（步骤 4）三张验收清单。

### 已完成步骤的文件清单

| 文件 | 改动 |
|---|---|
| `data/audio/AudioSubtitleState.kt` | 新增。枚举从 UI 层搬到数据层 |
| `ui/audio/AudioPlayerController.kt` | `AudioPlayerState` 加 `lyrics`/`subtitleState`；`loadSubtitles()` + `retrySubtitles()` + `ensureSubtitlesLoaded()`；`updateCurrentItem()` 触发；`release()` 清理。步骤 4 再加 `playerScreenVisible` + `notifyPlayerScreenVisibility()` |
| `ui/audio/AudioPlayerScreen.kt` | 删自有加载逻辑，改读 `controller.state`；「词」按钮回调 + 权限引导 + `onResume` 补授予；步骤 3 的解锁语义；步骤 4 的可见性 `DisposableEffect` |
| `presentation/audio/AudioPlayerContent.kt` | 按钮行末尾加「词」；新增 `OverlayPermissionDialog` |
| `ui/audio/FloatingWindowLifecycleOwner.kt` | 新增。手工驱动的 Lifecycle/SavedState/ViewModelStore/OnBackPressed owner |
| `ui/audio/AudioFloatingSubtitleOverlay.kt` | 新增。addView / removeViewImmediate（**两个窗口**），跟 preference 走 |
| `ui/audio/AudioPlaybackService.kt` | `onCreate` attach、`onDestroy` detach |
| `util/view/ViewExtensions.kt` | `setComposeContent` 增 `disposeStrategy` 参数 |
| `domain/base/BasePreferences.kt` | 新增 `audioFloatingSubtitle`（默认 false）；步骤 3 再加 `...Locked`、`...X`、`...Y` 与 `UNSET_POSITION` |
| `AndroidManifest.xml` | 新增 `SYSTEM_ALERT_WINDOW` |
| `i18n` base + zh-rCN | 新增 2 条权限引导字符串；步骤 3 再加锁定/解锁 2 条（关闭复用既有 `action_close`） |
| `i18n` base + zh-rCN + zh-rTW | 新增 `audio_floating_subtitle_short`（「词」按钮的单字标签） |

**总估**：6~9 个文件，300~600 行代码 + 若干 i18n。（比初版少：通知栏不动、单行单行渲染更简单）

---

## 7. 决策点（2026-08-29 已全部确认）

| # | 决策 | 结论 |
|---|---|---|
| 1 | 样式 | **贴顶窄条，高度恒定 1 行**，只显示当前这句，见第 9 节 |
| 2 | 前台行为 | **仅播放器页可见时隐藏**；App 内其它页面与后台均显示 |
| 3 | 入口 | **播放页底部按钮行加「词」按钮** + **悬浮窗小锁图标**（两者都能解锁）。不新增设置页 |
| 4 | 默认开关 | **默认关闭**，用户主动开启时才触发权限申请 |
| 5 | 通知栏 | **不加**「词」按钮，保持现有 3 action 不变（用户嫌展开态不简洁） |
| 6 | 关闭方式 | 未锁定态角落有 **×**，点一下直接关（= 开关置关）。锁定态 × 隐藏，防止误触 |

### 两个开关入口（用户 2026-08-29 最终确认）

| # | 入口 | 作用 |
|---|---|---|
| 1 | 播放页底部按钮行的「词」按钮 | 主入口。开/关 + **也能解锁** |
| 2 | 悬浮窗一角的小锁图标 | 锁定态下唯一可点区域，点它解锁 |

锁定与解锁有两个入口，**用户明确要求两者都要**，图的是最灵活。

### 通知栏：明确不做「词」按钮（已决定）

**决定：通知栏保持现状的 3 个 action，完全不改。**

**原因（用户 2026-08-29）**：展开态加第 4 个按钮后「好丑好不简洁」，宁可不要这个快捷入口。

**附带的平台限制（2026-08-29 查证，供以后参考）**：

androidx media 的 `MediaStyle` 源码（media3 对应 `MediaStyleNotificationHelper.MediaStyle`）：

```java
private static final int MAX_MEDIA_BUTTONS_IN_COMPACT = 3;
private static final int MAX_MEDIA_BUTTONS = 5;
```

```java
final int numActionsInCompact = Math.min(actions.length, MAX_MEDIA_BUTTONS_IN_COMPACT);
```

- **折叠态硬上限 3 个**：传 4 个索引会被静默截断成前 3 个，第 4 个根本不出现。
  > 2026-08-29 更正：本方案此前写的「折叠态直接塞 4 个」在技术上**不可行**，已删除。
- **展开态最多 5 个**，所以展开态放第 4 个本身是可行的——但用户因观感不佳否掉了。
- 若日后要折叠态就有 4 个，唯一途径是**放弃 MediaStyle 改用自定义 `RemoteViews` 通知**（不能用 Compose，深浅色/图标/间距全得自己适配）。网易云大概正是这么做的，但成本不低，且会偏离系统媒体通知的常规观感。

**影响**：因为不做通知栏入口，也就不存在「服务内跳权限页」的问题（见第 4 节）——权限引导只在播放页发生，天然有 Activity。方案简化。

### 关于「词」按钮的具体落位

底部按钮行现为 4 个（`AudioPlayerContent.kt`）：

```
[0.75x]  [音质：流畅]  [🔁单曲循环]  [🌙睡眠定时]
```

「词」按钮加在这行**末尾**。

**2026-08-30 用户追加要求（已实现）**：不要图标 + 文字，只要**一个「词」字**，像网易云那样；
字形要跟旁边的普通文字有区分。最终形态见下节。

### 「词」按钮的最终样式（2026-08-30 定稿并已实现）

```
[0.75x]  [音质：流畅]  [🔁单曲循环]  [🌙睡眠定时]  [(词)]
                                                    ↑ 圆形容器，关闭态灰底，开启态主题色填充
```

| 决定 | 结论 | 理由 |
|---|---|---|
| 内容 | **单个汉字「词」**，无图标 | 用户要求，与网易云一致 |
| 区分方式 | **圆形容器 + 加粗**，不是换字体 | 见下方「为什么没换字体」 |
| 尺寸 | 圆直径 `32.dp`，字 `titleMedium` + `Bold` | 比相邻的 24dp 图标略大（色块视觉重量本就更大），但不至于突兀 |
| 触摸区 | 外层 `IconButton`（48dp） | 与同行另外两个 `IconButton` 一致，点击区域不缩水 |
| 关闭态 | `surfaceVariant` 底 + `onSurfaceVariant` 字 | 灰底圆，一眼可辨是按钮而非文字 |
| 开启态 | `primary` 底 + `onPrimary` 字 | 与「单曲循环」「睡眠定时」的开启态同为 `primary`，沿用既有状态表达 |
| 无障碍 | `contentDescription` 用完整的 `audio_subtitle`（"歌词" / "Lyrics"） | 单字「词」对读屏太简略 |

#### 为什么没换「圆润字体」

用户提的是「字体可以变一点点，比如圆润点啥的」——是建议，核心诉求是**和普通字区分**。查证后未采用换字体：

- 项目里**零自定义字体资源**（全仓库只有 `FontFamily.Monospace` 三处，见 `WorkerInfoScreen` /
  `BackupSchemaScreen` / `MarkdownRender`），没有现成的可复用
- 系统 CJK 字体（Noto Sans CJK / 思源黑体）**无法单独改圆角**，要圆润字形必须引入字体文件
- 为一个按钮里的**一个字**引入字体资源（即便子集化也有几百 KB）不划算
- 圆形容器 + 加粗的区分度比字形微调更强，且零成本

> 若日后确实要圆润字形，需要引入一个 CJK 字体资源并做子集化（只保留「词」「詞」「L」等少数字符），
> 再在该按钮上单独 `fontFamily`。成本主要是 APK 体积与构建复杂度。

#### i18n

新增 `audio_floating_subtitle_short`：

| 语言 | 值 |
|---|---|
| base (en) | `L` |
| zh-rCN | `词` |
| zh-rTW | `詞` |

英文用单字母 `L`（Lyrics），中文/繁体用单字。读屏仍读完整的 `audio_subtitle`。

### 已知限制（接受）

开关只在播放页。悬浮窗显示后，若用户在其它 App 里想关闭，必须先回来。网易云同样如此。
**缓解**：通知栏 MediaStyle 可考虑加第五个 action 作为快捷开关，但会挤占紧凑视图空间，**本次不做**。

---

## 8. 与既有待办的关系

- 通知栏播放器三件套（封面 / 线控 / 拔耳机）已完成但**未装机验证**。
- 待办 ⑤⑥（转场）**已处理完毕，本方案不再涉及**：⑤ 阅读器 → 详情页淡化已完成；⑥ 三处淡化经用户重新定范围后取消（ASMR 页面切换、书架 → 详情均保持原样）。详见 `docs/audio-ux-handover.md` 第 3 节。
- **风险提示**：本方案开工前，建议先出一包验证既有三件套，避免未验证改动累积后难以定位问题。

---

## 9. 附：样式如何定下来的（含源站源码依据）

### 源站 Kikoeru 的实现（2026-08-29 查证）

`kikoeru-quasar` 前端的歌词组件是 `src/components/LyricsBar.vue`，核心结构：

```html
<q-card id="draggable"
        @mousedown="onCursorDown" @mouseup="onCursorUp"
        @touchstart="onCursorDown" @touchend="onCursorUp">
    <div id="lyricsBar" class="text-center text-h6 text-bold ellipsis-2-lines
                               text-purple q-mb-md absolute-bottom">
        <span id="lyric">{{ currentLyric }}</span>
    </div>
</q-card>
```

```css
#lyricsBar {
    background-color: rgba($grey-4, $alpha: 0.6);
    min-width: 1vw;
    position: absolute;
}
```

**关键结论**：
- `{{ currentLyric }}` 是**单数变量**——**只显示当前这一句，没有下一行预告**。用户据此提出「是不是只显示一行」，核对属实。
- 可拖动（`id="draggable"` + mousedown/touchstart），与我们的设计一致。
- 半透明灰底、文字居中、大号粗体——与我们的视觉方向一致。
- `absolute-bottom`：源站默认在**底部**。
- `ellipsis-2-lines`：最多 2 行，**超出用省略号截断**。

> 说明：`api.asmr-200.com` 是 asmr.one 风格的公开实例，以上是其上游开源前端 `kikoeru-quasar` 的源码，同源但不保证公开站逐字一致。

### 我们与源站的三处差异及理由

| 项 | 源站 | 我们 | 理由 |
|---|---|---|---|
| 行数 | 单行 | **单行** | 一致 |
| 拖动 | 支持 | **支持** | 一致 |
| 长台词 | `ellipsis-2-lines` 省略号截断 | **横向滚动**（`marqueeTitle()`） | 源站跑在桌面宽屏，触发省略的概率低；手机窄得多，省略会**丢失内容**。滚动能看全，代价是要等它滚完 |
| 默认位置 | 底部 | **顶部** | 手机底部有手势条、虚拟按键、输入法，与「边看边操作别的 App」直接冲突。且可拖动，一次即记住 |

### 方案演变

| 方案 | 问题 |
|---|---|
| 初版：窗口 5 行、高度自适应 | 遮挡过大。用户场景是「边看边操作别的 App」，遮挡是首要矛盾 |
| 中间版：高度随内容在 2~3 行浮动 | 用户指出**窗口会不自觉地变化大小，很不自然**。抖动的窗口比遮挡更烦 |
| 再版：恒定 2 行 + 下一句预告 | 与源站不符；ASMR 是连续对白，预告下一句听感上反而干扰 |
| **最终：恒定 1 行 + 横向滚动** | 采纳（2026-08-29） |

### 为什么横向滚动能成立

复用既有 `marqueeTitle()`（`presentation/util/Marquee.kt`）。它的注释写明：

> Text that fits stays still either way, so short titles are unaffected.

即**短文本完全静止，只有超出宽度时才滚动**。所以：

- 短台词 → 静止居中，无动画（绝大多数情况）
- 长台词 → 横向滚过去读完，再回弹

窗口高度因此恒定。该组件的动画模式、速度、重复间距均针对本项目调过（注释记录了两个已踩的坑：不能用 `WhileFocused`、不能用零间距），复用比新写稳。

**取舍代价**：长台词需等待滚动才看得全。这是为固定高度 + 单行付出的代价，用户已知晓并接受。

---

## 10. 实现期发现（2026-08-30）

### 10.1 与设计的两处偏离

1. **多了一个 `ensureSubtitlesLoaded()`**
   `restoreLastSession()` 会把历史曲目直接塞进 `state.item`，但**不走** `updateCurrentItem()`。
   从迷你播放器进播放页时也不会调 `start()`，若只按设计挂在 `updateCurrentItem`，字幕要等用户按下播放
   才加载——相比改动前是回归。故播放页留一行 `LaunchedEffect(currentItem?.mediaStreamUrl)` 主动要一次，
   该方法幂等（按 `subtitleUrl|subtitleFallbackUrl` 做键），已加载/加载中不发请求。

2. **字幕 publish 用 `notifyService = false`**
   通知栏不显示台词，否则每个音轨会多两次通知重建（`requestCoverArt` + `updateMediaSession` + `notify`）。

### 10.2 Compose 挂悬浮窗必需的四个 owner（实测）

`ComposeView` attach 时若缺 owner 会直接崩。需手工提供并 `set` 到 view 树上：

```kotlin
view.setViewTreeLifecycleOwner(owner)
view.setViewTreeViewModelStoreOwner(owner)
view.setViewTreeSavedStateRegistryOwner(owner)
view.setViewTreeOnBackPressedDispatcherOwner(owner)
```

**坑**：这四个都是 Kotlin file facade 且带 `@JvmName`，**不能用 `ViewTreeXxxOwner.set()` 的 Java 写法**
（Kotlin 侧 `Unresolved reference`），必须 import 扩展函数形式 `setViewTreeXxxOwner`。
`LifecycleRegistry` / `SavedStateRegistryController` 反倒是普通类，可直接用。

生命周期顺序：`performRestore(null)` → `ON_CREATE` → `ON_START` → `ON_RESUME`；
销毁 `ON_PAUSE` → `ON_STOP` → `ON_DESTROY` → `viewModelStore.clear()`。

配置：`DisposeOnDetachedFromWindow`（不能用默认的 `DisposeOnViewTreeLifecycleDestroyed`）。

### 10.3 窗口参数选择

- `TYPE_APPLICATION_OVERLAY`（minSdk = 26，无需 `Build.VERSION` 分支）
- 只用 `FLAG_NOT_FOCUSABLE`：不抢键盘与返回手势给下层 App。锁定态再叠加 `FLAG_NOT_TOUCHABLE`（步骤 3）
- 默认位置：`gravity = TOP or CENTER_HORIZONTAL`，`y = 状态栏高度 + 4dp`。
  状态栏高度只能靠 `getIdentifier("status_bar_height", "dimen", "android")` 取
- 宽度 = 屏宽 × 0.8，高度 `WRAP_CONTENT`

### 10.4 权限引导的落点

服务无法 `startActivity`（无 Activity 上下文），引导只能放 UI 层。最终形态：

- 播放页「词」按钮 → 若 `!Settings.canDrawOverlays` 则弹 `OverlayPermissionDialog` 说明 → 跳
  `ACTION_MANAGE_OVERLAY_PERMISSION`
- 跳前置 `awaitingOverlayPermission = true`（`rememberSaveable`），回到页面时靠
  `Lifecycle.Event.ON_RESUME` 观察者重新检查，已授予则直接写 preference 置开
- 服务侧只做「有权限才 addView」，`addView` 包 try/catch 兜底（MIUI 可能拒绝）

### 10.5 步骤 0 + 1 装机验证结果（2026-08-30，**全部通过**）

测试机 Redmi K50（MIUI / arm64-v8a），包 `app.mihon.dev` v2.1.9 (code 184)。

- [x] 播放页「词」按钮出现，默认关闭态（描边图标）
- [x] 点「词」弹权限说明 → 跳系统设置页 → 返回后自动置开（实心 + 主题色）
- [x] **悬浮窗能在 MIUI 上出现**
- [x] 退到桌面 / 切到其它 App 后仍然存在
- [x] 停止播放后窗口消失，无泄漏；反复开关无异常
- [x] 字幕四种状态（加载/就绪/空/错误）回归通过，反复进出播放页不再重复下载

### 10.6 MIUI 结论

**不需要额外开「后台弹出界面」。** 只授予「显示在其他应用上层」即可正常显示并保持在后台。

第 4 节把「后台弹出界面」列为独立风险，实测在本机不成立。

> **2026-08-30 追加**：该提示已从引导文案中**删除**。原先留在文案里的理由是「各机型策略不一，
> 留着无害」，但它是把用户支使去做一件本机并不需要的事，属于误导而非无害。
> 引导对话框的文案另经一轮精简（用户反馈原文啰嗦）：不解释「为什么要这个权限」，
> 只说清楚是**哪个功能**要用、去**哪儿**开。当前文案：
>
> - 标题：需要「显示在其他应用上层」权限
> - 正文：悬浮字幕需要这项权限，请到系统设置中为 Mihon 开启。

### 10.7 步骤 2 的验收清单（**待验证**）

- [x] 编译通过，无新增警告
- [ ] 悬浮窗显示的是**当前这句歌词**，不是占位文字
- [ ] 播放中文字随进度自动换行
- [ ] **窗口高度恒定**：短句与长句之间切换时高度不跳动
- [ ] 短句静止居中；长句超出宽度时**横向滚动**
- [ ] 无字幕 / 字幕加载失败 / 歌词为空的音轨上，**不出现空白横条**（窗口不添加）
- [ ] 退到后台后仍跟随进度（即窗口不被移除后的状态冻结）

### 10.8 「词」按钮样式改版（2026-08-30）

用户实测后追加要求：改成单个「词」字（网易云式），字形要跟普通文字有区分。
详见第 7 节「「词」按钮的最终样式」。要点：圆形容器 + 加粗，未引入字体文件；
新增一条 i18n 单字标签。**已编译通过，待装机验证。**

验收：
- [ ] 按钮显示为**单个「词」字**在圆形色块里，不再是图标 + 文字
- [ ] 关闭态灰底圆，开启态主题色填充圆（与单曲循环/睡眠定时的开启态同为 primary）
- [ ] 圆的大小与同行按钮协调，不突兀；点击区域不小于旁边图标按钮
- [ ] 开启/关闭切换正常，权限引导流程不受影响

### 10.9 步骤 3：为什么变成两个窗口（2026-08-30）

设计稿写的是「一个悬浮窗，角落放锁 + ×，锁定后 `FLAG_NOT_TOUCHABLE` 穿透」。实现时发现这两条
**在同一个窗口上无法同时成立**：

- `FLAG_NOT_TOUCHABLE` 是**整窗**生效的，加了它之后连角上的小锁一起收不到触摸 ——「锁定态下锁是
  唯一可点区域」落空。
- 不加它、改成「只有锁那块 `clickable`、其余区域不消费」也不行：Android 的触摸事件按**窗口**分派，
  事件一旦派发到某个窗口，即使它的 View 树无人消费也会被丢弃，**不会**回落到下层窗口。也就是说
  空白区域照样挡住下面的 App，"穿透"同样落空。

结论：**穿透与可点必须分给两个窗口**。

| 窗口 | 内容 | 未锁定 | 锁定 |
|---|---|---|---|
| 字幕窗（先添加） | 当前这句，整窗可拖动 | `FLAG_NOT_FOCUSABLE` | 叠加 `FLAG_NOT_TOUCHABLE` |
| 控件窗（后添加，落在字幕框下方） | 锁 + × / 仅锁 | 可点 | 可点（唯一的实体） |

控件窗在字幕框**之外**（下方、无背景、可被拖出屏幕下界），白框只包字幕 —— 这是第三轮
按用户反馈定的形状，详见第 10.11 节。

代价是多一个窗口要 add/remove —— 这正是第 5.3 节点名的泄漏风险点，故 `hide()` 里**先**摘控件窗
**再**摘字幕窗，两个 owner 各自 `stop()`，任一 add 失败当场 `stop()` 掉自己的 owner。

**其余落点**：

- **对齐靠实测**：控件窗 `y` 取字幕窗 `y + subtitleView.height`（实测高度，非固定常量），
  因为卡片允许在大字体下长高。两者各自 `heightIn(min=…)` / `height(…)` 定尺寸。
- **拖动不走重组**，且每帧只推一个窗口：见第 10.11 节 ①④。
- **只有松手才落盘**：`onDragEnd` / `onDragCancel` 才写 preference，拖动过程中不写。
- **位置用 `Int` + `UNSET_POSITION = Int.MIN_VALUE`**：区分「没拖过」和「拖到了左上角 (0,0)」。
  `show()` 时按**当前**屏幕 clamp 一次，旋转后旧坐标不会把窗口丢到屏幕外。
- **锁定只改 flags**：`updateViewLayout` 直接改 `FLAG_NOT_TOUCHABLE`，不重建窗口。**待装机确认**
  MIUI 是否买账；若改 flags 不生效，退路是 `hide()` + `show()` 重建。
- **× 与开关是同一个事实来源**：× 写的是 `audioFloatingSubtitle = false`，与播放页「词」按钮一致，
  不存在第二份开关状态。
- **「词」按钮的解锁语义**：锁定态下第一次点只**解锁**（不关），第二次才关。因为锁定的窗口
  触摸穿透，用户从 App 内根本够不着它，若这一下直接关掉，看起来像点了没反应。
- **i18n**：新增锁定/解锁 2 条（base + zh-rCN），关闭复用既有 `action_close`。
- **已知取舍**：控件按钮 32dp，小于 Material 的 48dp 最小触摸区。与 `AudioReaderFloatingBar`
  的既有做法一致，为的是让这个窗口尽量不挡视线。

#### 步骤 3 的验收清单

- [x] 编译通过，无新增警告
- [x] 未锁定态整条可拖动，松手后位置被记住（换曲 / 重开播放仍在原位）
- [x] 拖动时下方的锁与 × 跟着一起走，不脱节
- [x] 未锁定态显示**锁 + ×**，锁定态只剩**锁**
- [x] 点 ×：窗口消失，播放页「词」按钮同步回到关闭态
- [x] 锁定态点播放页「词」：只解锁不关闭；再点一次才关闭
- [x] 停止播放后两个窗口都消失；反复开关无残留窗口
- [ ] 点锁：窗口不再响应拖动，**下面的 App 能收到点击**（本次唯一未验的一条）
- [ ] 旋转屏幕后窗口不会跑到屏幕外
- [ ] 反复开关 10 次以上无泄漏

### 10.10 步骤 4：「可见」怎么判定（2026-08-30）

设计稿第 3.3 节给的写法是「`AudioPlayerScreen` 的 `Content()` 内用 `DisposableEffect(Unit)`
进入时置 true、`onDispose` 置 false」。照抄会**漏掉最常见的一条路径**：

**从播放页直接按 Home（或多任务切走）时，组合并不销毁。** `onDispose` 不触发，标志始终为 true，
窗口一直藏着。但文档 3.3 明确要求「退到后台也显示」，用户按 Home 正是冲着这个去的。

所以判定必须补上 Activity 的可见性，两个条件取交集：

| 条件 | 来源 | 作用 |
|---|---|---|
| 播放页在组合中 | `DisposableEffect` | 排除书架、浏览页、详情页等（Voyager 只渲染栈顶，所以组合活着 ⟺ 它是当前页） |
| Activity 处于 `STARTED` 以上 | `LifecycleEventObserver` + `currentState` | 排除按 Home、锁屏、切到别的 App |

两个都不满足改动前的写法：只写第一条漏掉按 Home，只写第二条会在书架页也误判为「播放页可见」。

**为什么是 `STARTED` 而不是 `RESUMED`**：`onStart`/`onStop` 在 Android 里就是「可见 / 不可见」的
边界，而 `onResume` 是「可交互」。用 `RESUMED` 会让分屏里没拿到焦点的那一半被判成不可见，
窗口反而浮出来盖住正在看的播放页。按 Home 时 Activity 最终走到 `onStop`（`CREATED`），
`STARTED` 判断同样能正确翻成 false。

**落点**：

- `AudioPlayerController.playerScreenVisible`（`mutableStateOf`）+ `notifyPlayerScreenVisibility()`。
  放在 controller 是因为它是 UI 与悬浮窗服务都拿得到的唯一单例，且 `readerControlsVisible`
  已有先例。方法名不能叫 `setPlayerScreenVisible` —— 与属性生成的 setter JVM 签名冲突
  （实测编译报 `Platform declaration clash`）。
- 悬浮窗侧只是 `combine` 里多并一个 `snapshotFlow { controller.playerScreenVisible }`，
  显示条件变成 `enabled && hasContent && !playerScreenVisible`。这正是当初预留的
  「同个 combine 再加一个条件」，没有额外结构改动。
- `release()` **刻意不重置**这个标志：释放播放时播放页往往还开着，重置反而会让窗口在用户
  眼皮底下冒出来。标志的权威来源始终是那个 `DisposableEffect`。

#### 步骤 4 的验收清单

- [x] 编译通过，无新增警告
- [x] 在播放页时不出现悬浮窗；返回上一页（详情页）后窗口立即出现
- [x] 在书架 / 浏览 / 历史等**其它页面**时窗口正常显示
- [x] **在播放页按 Home → 窗口出现**（最易漏的一条，见上；已验通过）
- [x] 从桌面点图标回到播放页 → 窗口消失
- [x] 切到别的 App → 窗口出现；切回来 → 窗口消失
- [ ] 跳系统权限设置页（未授予时点「词」）→ 窗口不出现；返回后流程不受影响
      （权限当前已是授予态，本次未复现该路径）
- [ ] 进出播放页 10 次以上，窗口不会残留在屏幕上

### 10.11 装机反馈后的返工（2026-08-30，**第三轮已装机**）

首轮装机后用户反馈三点，都改了；第二、三、四轮的修法各引入一个 bug，分别由第三、四、五轮修正。
每轮的"观感调整"都牵出了一个**真实的平台行为**（见 ④⑥⑦），值得单独记住。

#### ① 拖动在 App 内发飘、跟不上手指（性能缺陷，不是观感问题）

**现象**：在**桌面**拖动正常，在 **App 内**连续拖动时窗口延迟、像在追上一个位置。

**根因**：原实现每一帧调用两次 `WindowManager.updateViewLayout`（字幕窗 + 控件窗）。它是
到 system_server 的**同步 Binder 调用**，且每次都触发 `requestLayout` → Compose 重新测量/
重组。单次不贵，但：

- 桌面时 App 在后台，主线程空闲，能吃下；
- App 内时主线程还在跑 App 自己的 UI，这些调用开始**排队积压** —— 积压就是延迟，
  表现正是"在追上一个位置"。

**修法（第二轮，已被 ④ 取代）**：用 View 的 `translationX/Y` 代替移动窗口。

**④ translation 方案失败：拖动时窗口「遁入分界线」，松手才出现（第二轮引入的 bug）**

**现象**：拖动时卡片像滑进了某条看不见的线里消失，松手才在对应位置出现。

**根因**：translation 是 **View 级别**的渲染偏移，而悬浮窗的绘制区域（Surface）由
`LayoutParams` 的 `width/height` 决定。translation 只在**窗口内部**移动绘制内容，
**移出窗口 bounds 的部分被系统裁掉**。

> 教训：逃避每帧 IPC 的方向是对的，但选错了手段。窗口的位置只有 `LayoutParams` 说了算，
> 任何"在窗口内偏移绘制内容"的做法都会被窗口自己的矩形裁掉。

**最终修法**：回到 `updateViewLayout` 移动窗口，但把每帧调用从 **2 次降到 1 次** ——
拖动开始时把控件窗设为 `GONE`（不再布局、不再合成），拖动中只推字幕窗，
松手后先定位再 `VISIBLE`。

这同时更贴合视觉：拖动时卡片是独立的、下面不挂东西，松手后按钮才归位。

**附带收益**：③ 的改动移除了无限循环 marquee（它本来每帧都在跑），每帧开销进一步下降。

#### ② 窗口太矮、字幕没用满宽度、图标挤占 x 轴

改成上下两块：

```
[        字幕，占满整行宽度        ]   LINE_HEIGHT        40dp   ← 白框只包这里
      [      🔒  ×      ]              CONTROL_ROW_HEIGHT 32dp   ← 在框外，无背景
```

- 字幕独占上行，**用满整个宽度**（原右侧被图标占去 64dp）
- 图标在下行、**白框之外**、居中、无背景

**第二轮做错、第三轮修正的一点**：第二轮把两块做进**同一张卡片**（`heightIn(min = 40 + 32)`，
背景覆盖整体），结果图标虽然有自己的窗口，视觉上仍被大白框包着 —— 用户要的恰恰是
**白框只包字幕**。第三轮把字幕窗高度收回 `WRAP_CONTENT`、背景只覆盖字幕行，
图标彻底落到框外。

控件窗的 `y` 用字幕窗的**实测高度**（`subtitleView.height`）而非常量：卡片允许在大字体
下长高，按钮得跟着实际的它走。

#### ③ 滚动来回循环，与说话节奏对不上

原用 `marqueeTitle()`（`basicMarquee`）**无限循环**、恒定 45dp/s，与台词时长毫无关系。
用户点出：说话不需要回顾前面，网易云是一次性滚到尾。

改成：**按该句的说话时长滚一次，滚到尾停住**。

- 时长 = 下一句 `timeMs` − 本句 `timeMs`（末句用 `durationMs`）
- 短句不超出宽度 → `maxValue == 0` → **完全不启动动画，零开销**
- 长句 → `animateScrollTo(max, tween(时长, LinearEasing))`，滚到尾即停
- 换句时 `LaunchedEffect(line)` 重启，先 `scrollTo(0)` 再滚
- 边界：`coerceIn(1200ms, 12000ms)`。未知时长（0）不至于瞬移，超长句不至于爬行

`marqueeTitle()` 在本窗口**不再使用**（项目其它处仍用，未改动 `Marquee.kt`）。

#### ⑤ 图标降低存在感，且放不下就隐藏

用户要求「不抢夺注意力」，并且：*往下拖时让锁和 × 移到手机下界之外，只显示白框字幕*。

- 图标 18dp → **16dp**，alpha 0.75 → **0.6**（比所在 32dp 点击区小，视觉比手感轻）
- `maxY()` 只按**字幕窗**的高度算，不把下方的按钮算进去 —— 卡片可以拖到贴屏幕底边

**⑥ 「拖出屏幕外」不可行：MIUI 会把出界的窗口拉回来（第三轮引入，第四轮修正）**

**现象**：拖到底部时，锁与 × **盖到了字幕上**。

**根因**：不是算错位置，是**系统行为**。第三轮靠"让按钮落到屏幕外"来实现极简状态，
但 **MIUI 会把移出屏幕的 overlay 窗口拉回可见区域** —— 按钮被拽回来后正好贴在底部，
而卡片此刻也在底部，于是压在字幕上。

> **可复用结论（MIUI）**：`TYPE_APPLICATION_OVERLAY` 窗口无法靠"移到屏幕外"来隐藏。
> 想让它消失，只能改 visibility。这条与 10.6 节的发现并列，都属本机实测。

**修法**：不依赖出界，改为**主动判断** —— `controlsFitBelow()` 检查按钮那一行能否完整
放进屏幕，放不下就 `GONE`（"gone"而非"透明"：不布局、不合成，也更省）。往回拖时自动恢复。

效果与用户要的完全一致（拖到底只剩白框字幕），且不依赖系统行为。
这也复用了拖动时已有的 `GONE` 机制，没有新增概念。

#### ⑦ 旋转屏幕后窗口畸变、突出屏幕外（第五轮）

**现象**：横屏转回竖屏，卡片变得很长并突出到屏幕外。

**根因**：卡片宽度是 `cardWidthPx()` 在**首次显示时**按当时屏幕宽算出的**固定像素值**
（`WIDTH_RATIO * screenWidth`）。`WindowManager.LayoutParams.width` 不会随配置变化自行更新，
于是横屏算出的宽度被沿用到竖屏，而 `x` 也没有按新屏幕重新 clamp —— 又长又出界。

**修法**：`AudioPlaybackService.onConfigurationChanged()` 转发给 overlay，重新取宽、
clamp `x`/`y`、推 `updateViewLayout`、再定位按钮。

- Service 在旋转时**不重建**，所以必须由它把变化转给窗口；`onConfigurationChanged`
  对 Service 是无条件回调的（不像 Activity 需要声明 `configChanges`）
- clamp 后的位置**不落盘**：转回来时能恢复原位，而不是留下另一个方向强行压出来的值
- 无需等重新测量：字幕是 `softWrap = false` + `horizontalScroll`，**恒为一行**，
  高度不随宽度变，所以按钮可以立即定位

**顺带修的相关问题**：滚动距离 `scrollState.maxValue` 也是按旧宽度算的，旋转时正在滚动的长句
会滚到错误位置。`LaunchedEffect` 的 key 加上 `LocalConfiguration.screenWidthDp`，
旋转即重启滚动并重算距离。

> **可复用结论**：任何写进 `WindowManager.LayoutParams` 的像素尺寸都是"那一刻"的快照，
> 配置变化必须自己重算。

#### ⑧ 滚动改为由播放进度驱动（第六轮）

用户反馈：*暂停时滚动仍在擅自滚过；滚动应与此刻这句台词的说话时长匹配。*

**原实现的毛病**：`animateScrollTo(distance, tween(该句时长))`。它是一个**时长固定的动画**，
一旦启动就走自己的时钟，与播放状态完全脱节：

- 暂停 → 动画继续跑完 → "擅自滚动过去"
- 长句 → 与真实说话进度逐渐漂移，无法自我纠正
- seek → 动画不理会，仍按自己的剩余时间走

**改法**：不再用动画，滚动位置直接由**播放进度**算出。

```
progress = (positionMs − 本句起始) / 本句时长
scrollTo(maxValue × progress)
```

- **暂停**：`isPlaying` 为 false 即跳出帧循环 → 滚动当场停在当前位置，就地等待
- **恢复播放**：重新进入循环、重置锚点，从暂停处接着走
- **seek**：position 一变就被检测到，锚点更新，滚动立即跳到对应位置
- **换句**：`LaunchedEffect(line, …)` key 变化，滚动归零重新开始

**为什么仍然平滑**：`positionMs` 每 500ms 才来一次，直接用它会每半秒跳一格。
所以做法是**以真实 position 为锚，帧间用经过时间外推**：

```
每帧：若 position 变了 → 重设锚点(锚点进度 = f(position)，锚点时刻 = 本帧)
      进度 = 锚点进度 + (本帧 − 锚点时刻) / 时长
```

平滑由帧时间提供，**不漂移**由每 500ms 的真实 position 校准保证。二者结合，
比原来的固定时长动画既更准又更省。

**暂停时不耗电**：暂停期间用 `snapshotFlow { isPlaying }.first { it }` 挂起等待，
而不是继续跑帧回调 —— 恢复播放时立即唤醒，无轮询、无空转。

**短句零开销**：`maxValue == 0`（不超宽）时直接返回，帧循环根本不启动。

**去掉了 `MIN_SCROLL_MS` / `MAX_SCROLL_MS`**：那两个 clamp 是固定动画时代的产物
（防止未知时长瞬移、超长句爬行）。现在时长就是真实说话时长，clamp 反而会破坏
"与说话人匹配"这个目标，故删除。极端情况（时长为 0）改为直接显示整句。

> **坑**：`withFrameMillis { … }` 的 lambda **不是** suspend 作用域，
> 而 `ScrollState.scrollTo()` 是 suspend 函数。必须写成
> `val frameTime = withFrameMillis { it }`，把滚动放到协程体里。

#### 第四轮验收清单（**已装机**）

> 2026-08-30 用户确认「其他似乎没啥问题了」，以下均已通过（旋转一项除外，见第五轮）。

- [x] 编译通过，无新增警告
- [x] **拖动时窗口不再「遁入分界线」**，全程跟手（④的回归重点）
- [x] **App 内连续上下拖动不再有延迟**（①的核心）
- [x] 白框只包字幕一行；锁与 × 在框外下方、居中、淡而小
- [x] **向下拖到底时按钮消失，只剩字幕白框，且不再盖住字幕**（⑥的核心）
- [x] 往回拖一点，按钮重新出现在卡片下方
- [x] 拖动中按钮隐藏，松手后归位到卡片下方
- [x] 长台词**一次性滚到尾后停住**；短台词完全静止
- [x] 松手位置被记住；停在下边缘的位置重开播放后，按钮仍是隐藏的
- [x] **锁定后下层 App 能收到点击**（本机通过）

#### 第五轮验收清单（**已装机，待验**）

- [x] 编译通过，无新增警告
- [ ] **竖屏 → 横屏 → 竖屏，卡片恢复正确宽度，不再畸变、不出界**（⑦的核心）
- [ ] 旋转后卡片仍在屏幕内（即使原位置在新方向下超出，应被 clamp 回来）
- [ ] 旋转后按钮重新定位到卡片下方；放不下时正确隐藏
- [ ] 旋转时正在滚动的长句，滚动距离按新宽度重算
- [ ] 转回原方向后，位置恢复为旋转前记住的那一处

#### 第六轮验收清单（**已装机，待验**）

- [x] 编译通过，无新增警告
- [ ] **暂停播放 → 滚动立即停住**（⑧的核心，重点验）
- [ ] 恢复播放 → 从暂停处接着滚，不跳回开头
- [ ] 长句滚动与说话节奏同步：说到一半时滚动也在一半，说完时刚好滚到尾
- [ ] 拖动进度条 seek → 滚动跳到对应位置
- [ ] 短句仍完全静止

### 10.12 其它

- `spotlessCheck`：`ReaderActivity.kt` 有**既有**的两处违规（import 组空行、注释前空行），
  属用户正在编辑的文件，**本次未动**，也因此未运行 `spotlessApply`（新增代码同样是手工对齐
  `.editorconfig`：导入按 IntelliJ 布局 `kotlin.**` 置尾、尾逗号、行宽 120）。
- 步骤 2 顺带把「有内容才显示」的条件做进 `attach()` 了（本属步骤 4 的范围）。
  理由：去掉占位文字后，无字幕音轨会浮出一条空白横条，比不做更糟。
  步骤 4 确实如当初预判，只在同一个 `combine` 里多加了一个条件，无结构改动。
