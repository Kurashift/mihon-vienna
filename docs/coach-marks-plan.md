# 首次使用功能引导（coach marks）制作计划 v5

v5 变更（来自真机反馈）：整体从「聚光灯教程」降级为「app 自己的提示语言」——胶囊（点穿）、图标短词、原生区域图。砍掉底栏胶囊与音声代理文案，阅读器聚光灯换成一条非阻塞胶囊。

## 保留判据

1. 界面上没有任何可见暗示（长按 / 拖动 / 滑动 / 纯手势 / 无文字图标）；
2. 没有可发现的替代入口；
3. 不是 Android 用户凭习惯能试出来的标准模式；
4. fork 特色功能：不教就当它不存在。

## 最终形态（2 个触发点）

### 1. 本库首访——聚光灯 1 条，图标短词气泡

触发：首次进本库、列表非空、FAB 出现（全局一次）；列表为空不弹也不置标志。

挖孔锚定左下角 FAB，说明拆成三枚方向小标注（图标+短词，各就各位，不堆在一个气泡里）：

- 按钮正上方（近）：↑ 上拖 · 好本子
- 按钮正上方（远）：👆 点按 · 回到上次在看
- 按钮右侧：→ 右拖 · 随机一本

### 2. 阅读器首访——一次性胶囊 1 条（无聚光灯）

触发：首次进阅读器（限本地源）、首页出现后、原生导航 overlay 不可见且菜单关闭。**从 `onInitialPageSelected` 和 `onPageSelected` 两处武装**——webtoon 没有页对齐事件，只挂前者会永远不触发（v4 的真 bug）。

- 条漫模式：「左滑随机换一本 · 右滑换好本子」
- 分页模式：「上滑随机换一本 · 下滑换好本子」
- 位置在屏幕中央（MENU 标签正下方）——那正是点击与滑动发生的地方

ExitHintPill 同款视觉（点穿不拦截），首次翻页即收，6 秒自动消失。

## 已砍（真机反馈）

- 底栏 tab 重按胶囊——「太蠢了」，删代码、删标志、删文案。
- 阅读器三步聚光灯（中央菜单 / 滑动 / 红心）——「样式好丑」「和原本风格不融洽」。菜单与红心交还原生手段：点击区域图自带 MENU 标注；红心待定。
- 音声「需要网络代理」文案——「废话」，`AudioBrowseContent.kt` 还原。

## 组件（`presentation/components/CoachMark.kt`，零新依赖）

- `CoachMarkState` + `Modifier.coachAnchor(id)` + `LocalCoachAnchorRegistry`（CompositionLocal 分发，FAB 自己注册）。
- `CoachMarkOverlay`：挖孔聚光灯，步骤支持 `text` 长句或 `iconRows` 图标短词，跳过 / Back / 3 秒锚点超时。
- `CoachHintPill`：非阻塞胶囊，同 ExitHintPill 语言。

## 状态与门闩

`BasePreferences` 两个 `appStateKey` 标志：`coach_local_fab_shown`、`coach_reader_shown`。设置 → 高级「重新显示功能引导」清零两者并把 `reader_navigation_overlay_new_user` 置回 true。`isBenchmarkBuildType` 不弹。

## 验证

`:app:compileViennaKotlin` + `:app:testDebugUnitTest`；设备 `installVienna`；重看入口回归。注意 `ReaderViewModel.kt`、`BrowseSourceViewModel.kt` 等文件有并行改动，只提交自己名下的文件。
