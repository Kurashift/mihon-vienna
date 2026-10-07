# 首次使用功能引导（coach marks）制作计划 v4

目标：给**完全不可见、且没有替代入口**的操作加一次性引导。原则：符合直觉或存在替代路径的一律不做——引导本身也是界面噪音，宁缺毋滥。

v4 变更：阅读器序列与上游原生的「新用户点击区域图」融合——原生 overlay 继续负责区域教学，聚光灯在其消失后接力，只讲 overlay 教不了的事。

## 保留判据

一条引导值得做，须同时满足：

1. 界面上没有任何可见暗示（长按 / 拖动 / 滑动 / 纯手势 / 无文字图标）；
2. 没有可发现的替代入口（菜单里找不到、别处学不到）；
3. 不是 Android 用户会凭习惯试出来的标准模式；
4. 对 fork 特色功能加一条：**不教就当它不存在**（错过的是整个功能，不是一个快捷方式），或**不教就会误判为坏了**。

## 调查结论

### 原生引导面

- 首启 4 步向导（`presentation/more/onboarding/`）只管装好；门闩 `BasePreferences.shownOnboardingFlow`。
- **`ReaderNavigationOverlayView`**：全屏彩色点击区域图（填色 + 文字标签 MENU/PREV/NEXT），新用户首次打开阅读器自动出现一次（`reader_navigation_overlay_new_user`，默认 true，用后即焚），点任意处消失、无超时；设置里可改「每次打开都显示」。fork 给条漫默认配三分带点击区（`WebtoonNavigation`：上=上一页、中=菜单、下=下一页）。
- 换阅读模式只有模式名 Toast；fork 的随机滑动手势全链路零解释。

### fork 模块审计

| fork 模块 | 判定 | 理由 |
|---|---|---|
| 随机机制 + 好本子体系（旗舰） | **做** | 全链路零暗示：FAB 拖动手势（右拖随机、上拖好本子，提示箭头只在手指停 350ms 后出现）、阅读器滑动随机（分页上下滑 / 条漫左右滑，方向相反）、标记入口（阅读器顶栏红心、章节行滑动、选择菜单）全部不可见；而随机池子要靠标记养出来——不教这套，旗舰功能等于不存在 |
| 音声频道 | **做（最轻形态）** | 入口是本库工具栏无文字的耳机图标；后端需要搭梯子，不提示会误判为功能坏了。失败空状态一句话解决 |
| 译名导入导出 | 不做 | 主动寻找的工作流，入口有文字标签（详情页 ⋮、更多>数据与存储） |
| 本地漫画导入导出 | 不做 | 导入屏自带格式说明与选项 |
| 本库筛选 / 排序重构 | 不做 | 已带自解释装置（筛选通知、排序芯片命名、方向独立按钮） |
| 阅读器顶栏译名 / TitleOpenHint / 章节垫 / 垂直导航 | 不做 | 已有图标提示先例；章节垫随菜单引导带到；垂直导航是设置项 |
| 历史 x/y 进度、搜索历史、自然排序、抗空读、冷却池 | 不做 | 自明或完全无感 |
| 权限页 / 首启向导 | 已有 | onboarding 已覆盖 |

上游 Mihon 侧不做：阅读器两侧翻页、长按页面、详情页长按/滑动（标准模式或有替代入口）、书架、书源页、下载队列、历史滑动。

## 最终引导清单（4 个触发点，约 6 句话）

### 1. 本库首访——聚光灯 1 条（随机体系的「结果端」）

触发：第一次进入本库、列表非空、左下角 FAB 出现（全局一次）；列表为空不弹也不置标志，下次再试。

- ① 孔：FAB。「点按回到上次在看的那本；按住拖动：向右拖随机开一本，向上拖随机开一本好本子——好本子在阅读器顶栏的红心标」

### 2. 阅读器首访——聚光灯 3 条（随机体系的「供给端」，与原生 overlay 融合）

触发：第一次进入阅读器（限本地源漫画——随机手势与红心都是本地源功能）、首页对齐后、且原生导航 overlay 不可见；翻页立即收起。

- ① 孔：屏幕中央（合成锚点）。「点屏幕中间呼出菜单；翻页时会自动收起，再点中间就能叫回来」——对原生 overlay 的 MENU 区域做行为补充
- ② 无孔、气泡居中。「纵向/横向滑动页面（按当前阅读模式）：上滑/左滑随机换一本在读的书，下滑/右滑随机换一本好本子」（分页模式纵向、条漫模式横向，见 `ReaderActivity.kt:620-654`）
- ③ 孔：顶栏红心（coach 走到该步时自动打开菜单，结束后恢复）。「看到合胃心的，点顶栏的红心标进好本子，随机就从这里抽」

时序协调：聚光灯启动条件 = 首页对齐 **且** 导航 overlay 不可见（新用户先看区域图、点掉后聚光灯再起，两层教学不叠加）。给 `ReaderNavigationOverlayView` 加可见性回调。不动上游 new-user 偏好语义。

### 3. 底栏 tab 重按——一次性胶囊（无聚光灯）

触发：首启向导完成后第一次回到主界面，底栏上方出一条胶囊（ExitHintPill 样式，点穿不拦截），点任意 tab 或 5 秒后消失。

- 「再次点按底部标签有快捷方式：书架开筛选、更新看下载队列、历史直接续读」

（非 fork 特色，但一条讲完 5 个隐藏快捷方式，成本最低；若嫌吵第一个砍它。）

### 4. 音声频道——失败空状态文案（零打扰）

音声浏览页加载失败的空状态（`AudioBrowseContent.kt`）追加一行：「音声内容需要网络代理才能访问；登录后可同步收听进度」。不弹窗、不拦截、不需要标志。

## 组件设计

`presentation/components/CoachMark.kt`，零新依赖：

- `CoachMarkState`：步骤列表、当前序号、锚点注册表（`SnapshotStateMap<String, Rect>`）。
- `Modifier.coachAnchor(state, id)`：`onGloballyPositioned` 上报窗口坐标，离开组合时清除。
- `CoachMarkOverlay`：Canvas 全屏遮罩 + 圆角挖孔（`Path` + `EvenOdd`），气泡自适应上/下并夹边，无锚点步骤居中显示；点任意处前进/结束，「跳过」整段退出，Back 等价跳过；锚点 3 秒量不到坐标自动跳过该条。
- 锚点分发用 `CompositionLocal`（`LocalCoachAnchorRegistry`）：阅读器顶栏红心、本库 FAB 在自己内部注册，不层层传参。
- 阅读器手动合成「屏幕中央」锚点（第 ① 步的孔）。

## 状态与门闩

`BasePreferences` 新增三个标志，全部 `appStateKey`，默认 false，播完置 true：

- `coach_local_fab_shown`
- `coach_reader_shown`
- `coach_tab_reselect_shown`

设置 → 高级加「重新显示功能引导」：清零三个标志，并把上游 `reader_navigation_overlay_new_user` 置回 true——重放体验与真正新用户首开完全一致。兼作回归测试开关。`isBenchmarkBuildType` 不弹（benchmark 会开阅读器/浏览页跑宏基准）。

## 实施步骤

1. `presentation/components/CoachMark.kt`：组件三件套。
2. `domain/base/BasePreferences.kt` 加三个标志。
3. `presentation/more/settings/screen/SettingsAdvancedScreen.kt` 加重看入口。
4. `ui/reader/ReaderNavigationOverlayView.kt` 加可见性回调；`ui/reader/ReaderActivity.kt` 接阅读器序列（`onInitialPageSelected` 触发、`onPageSelected` 翻页即收、菜单开着不弹、第 ③ 步代开菜单）；`presentation/reader/appbars/ReaderTopBar.kt` 红心注册锚点。
5. 本库 `ui/browse/source/browse/BrowseSourceScreen.kt` 接 FAB 序列；`presentation/browse/components/BrowseSourceLastReadFab.kt` 注册锚点。
6. `ui/home/HomeScreen.kt` 接底栏重按胶囊。
7. `presentation/audio/AudioBrowseContent.kt` 失败空状态补文案。
8. i18n：`i18n/src/commonMain/moko-resources/{base,zh-rCN,zh-rTW}/strings.xml`，约 9 条新文案（跳过按钮复用 `onboarding_action_skip`）。

## 真机操作方法（验收）

1. agent 构建、安装并挂 logcat：`.\gradlew --init-script .gradle\no-abi-split.gradle :app:installVienna`。
2. 用户按顺序操作：
   - 设置 → 高级 → **重新显示功能引导**（老用户重放入口；新装包自然触发，不用点）
   - 回主界面 → 底栏上方出现「再次点按底部标签…」胶囊；点任意 tab 消失
   - 进本库 → 左下角 FAB 聚光灯；点按任意处收起
   - 打开一本本地漫画进阅读器 → 先看到原生彩色点击区域图，点掉 → 聚光灯 3 条接力（中央菜单 → 滑动随机 → 顶栏红心，第 ③ 条会自动打开菜单）；中途翻页立即收起
   - 阅读器设置里把阅读模式切成条漫、重置引导后再进一次，确认条漫方向文案（左滑=在读、右滑=好本子）
   - 本库工具栏耳机图标进音声频道，无代理状态下 → 失败空状态显示「需要网络代理」
   - 想重看：回设置 → 高级 → 重新显示功能引导
3. agent 全程读 logcat；文案或时机问题反馈后用重置入口复测。

## 风险与边界

- **双 overlay 撞车**：靠「导航 overlay 不可见才启动」门闩解决，不叠加。
- **并行改动**：`ReaderViewModel.kt`、`BrowseSourceViewModel.kt`、`RandomSelectionCooldown.kt`、`RandomDiagnostics.kt` 等有并行会话在改，动手前 `git status` 确认，只提交自己名下文件。
- **打扰感**：4 个触发点、约 6 句话，全部一次性、可跳过。阅读器 3 条是上限，备选：第 ③ 条挪到第二次进入阅读器。
- appState 不进备份，换机重放一遍属预期。
