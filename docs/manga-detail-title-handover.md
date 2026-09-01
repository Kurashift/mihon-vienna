# 漫画详情页标题 Bug 修复 / 布局优化 —— 交接文档

> 状态：**代码已落地、编译安装过，待真机复验 / 可能还有微调**
> 本文档记录当前调查结论、最终采用方案、踩坑清单，供后续接手时快速恢复上下文。

## 0. 状态概览

- **源码已全部改完**（见第 2、3、4 节的当前实现），不再是「尚未修改」状态。最新一轮修复（把 `filter` 改回 key 白名单 + `pointerInput` 仅在选区时运行）后，用户按「继续」要求编译安装复验——**复验结果未在本会话确认**，接手后应先 `:app:assembleVienna` + `adb install -r` 真机走一遍第 5 节验收清单。
- 工作区里仍有成片 uncommitted 改动属于用户的**并行开发工作**，严禁回退、勿擅自提交。
- 所有本任务改动**均未提交 git**。

## 1. 任务来源（用户原话要点）

1. **修复 bug**：漫画详情页主封面右侧「大标题」在从下方缓慢上移（向上/切回顶端附近）时会莫名其妙消失；若大标题为两行，有时上面一行消失，有时两行都消失。
2. **布局优化**：下滑后详情页左上角出现「小标题」，当前位置不好看。希望：
   - 把小标题移到「紧贴返回箭头的上方」；
   - 与系统通知栏之间留更多距离；
   - 文字更宽裕、更舒服一些。

## 2. 关键文件与现网状态

| 文件                                                                             | 相关位置                                                                | 说明                                                                            |
| ------------------------------------------------------------------------------ | ------------------------------------------------------------------- | ----------------------------------------------------------------------------- |
| `app/src/main/java/eu/kanade/presentation/manga/components/MangaInfoHeader.kt` | `MangaContentInfo`（约 L585-L637）、`MangaAndSourceTitlesSmall`（约 L532） | 大标题；当前使用 `AndroidView(TextView)`                                              |
| `app/src/main/java/eu/kanade/presentation/manga/components/MangaToolbar.kt`    | `navigationUnderTitle`（约 L83-L102）                                  | 顶栏小标题 Text（11sp/13sp，maxLines=2，alpha 随滚动）                                    |
| `app/src/main/java/eu/kanade/presentation/components/AppBar.kt`                | `navigationUnderTitle` 覆盖层（约 L178-L189）                             | 小标题的定位容器（当前 BottomStart、bottom=2dp、start=12dp、宽度上限 0.6f）                      |
| `app/src/main/java/eu/kanade/presentation/manga/MangaScreen.kt`                | `MangaScreenSmallImpl`（约 L419-L464）                                 | 顶栏 alpha/background 由 `firstVisibleItemIndex/firstVisibleItemScrollOffset` 派生 |

## 3. Bug 1（大标题消失）调查结论与落地

### 根因（已确认）

- 「大标题」原是 `MangaContentInfo` 里的 **`AndroidView(TextView)`**（`setTextIsSelectable(true)` 的可选中标题）。它位于 `LazyColumn` 第一项 `INFO_BOX`，滚动回收/重建时原生 `TextView` 与 Compose `MeasureSpec` 测量时序不稳定，偶发 0 宽 → 整块或某行标题消失。
- 已对比上游 Mihon（`mihonapp/mihon` main）：大标题是普通 Compose `Text`，AndroidView 是本 fork 为「可选中 + 自定义搜索菜单」引入的，是 bug 直接来源。

### 最终采用的修复（已落地）

- **彻底移除 `AndroidView`/`TextView`/`MangaTitleSelectionCallback`/`TITLE_ACTION_*`**，大标题改回 Compose `Text`，从根因消除测量竞态。
- **保留「选中部分文字搜索」能力**（这是原方案里预计要牺牲的，最终用 Compose 原生选区做到了）：用 `SelectionContainer(state = selectionState)` 包住 `Text`，`selectionState` 由 `MangaScreen` 持有。
- 单击标题 → 整标题源搜索（`doSearch(title, true)`）；长按选区 → 系统原生横向工具栏，三项：**复制 / 本地标题搜索（`searchLocal`）/ 源标题搜索（`doSearch(q, true)`）**，自带震动反馈。
- 三项全部自定义提供（不再用系统 `CopyKey`），避免系统复制项与自定义项销毁不同步导致闪成两项的问题。

### 关键代码位置（当前实现）

- `MangaInfoHeader.kt`：
  - `MangaContentInfo`（约 L602-L698）`SelectionContainer` + `appendTextContextMenuComponents` 三项 + `filterTextContextMenuComponents`（key 白名单）。
  - 常量 `TITLE_SEARCH_COPY_KEY` / `TITLE_SEARCH_LOCAL_KEY` / `TITLE_SEARCH_SOURCES_KEY`（约 L996-L998）。
  - `MangaAndSourceTitlesLarge`（L500）、`MangaAndSourceTitlesSmall`（L548）、`MangaInfoBox`（L131）均已加 `selectionState` + `onTitleRectChange` 形参并向下透传。
- `MangaScreen.kt`（`MangaScreenSmallImpl` 约 L422-L446）：`val titleSelection = rememberSelectionState()`、`var titleRect`、`hasTitleSelection`、`BackHandler(enabled=hasTitleSelection)` 清除、`Scaffold` 上 `pointerInput(hasTitleSelection)` 监听点外部取消。

### 已踩的坑（接手前必读，避免重蹈）

1. `SelectionContainer` 的参数是 `state`，不是 `selectionState`（反编译 Kotlin metadata 确认）。
2. 上下文菜单 API（`foundation`）：只有 `item` 需 import；`separator` 是 `TextContextMenuBuilderScope` 成员函数，不能 import。
3. **菜单必须挂在 `SelectionContainer` 这一层**，不能挂在内部 `Text`——Modifier 提供的 CompositionLocal 只向下传，父节点查不到，挂内部只会剩系统默认项。
4. **`filter` 不能用 `{ false }`**：`filter` 与 `append` 按 modifier「从下到上」顺序应用，写成一律 false 会把刚 append 的三项也滤掉，菜单变空。**必须用 key 白名单**只留自定义三项。
5. 选区状态必须提升到 `MangaScreen`：`SelectionContainer` 只处理自身范围内的按下，点到标题外收不到事件，高亮会一直挂着。外层用全局 `pointerInput` + `BackHandler` 补「点外部 / 返回键取消」。
6. `pointerInput` 必须写成 `pointerInput(hasTitleSelection)` 且仅在选区时跑循环；无选区时协程立即结束、不碰事件，否则会吞掉长按、菜单出不来。
7. `onGloballyPositioned { it.boundsInWindow() }` 返回 `Rect`（Float），外层用 `rect.contains(down.position)` 判断按下点是否在标题内；`titleRect.contains` 命中时把按下让给 `SelectionContainer`，不清选区。
8. 短标题点击区误判：用 `Text` 上 `wrapContentWidth(align=...)` 收拢，否则右侧空白也算进点击区触发搜索；`textAlign` 补偿在对齐位置。
9. 大标题点击波纹：`combinedClickable(indication = null, interactionSource = null)`，保持与原生 TextView 一致无波纹。

## 4. 任务 2（顶栏小标题布局）调查结论与落地

### 现状回顾

- 小标题非 M3 居中 title 槽，而是 `AppBar` 里叠在顶栏某处的 `navigationUnderTitle` 覆盖层。64dp 顶栏里返回箭头（48dp IconButton）居中，箭头上方仅约 8~12dp 窄条，放不下两行。

### 最终定位（已落地，经过多轮调整）

- **位置：`AppBar.kt` 覆盖层 `Alignment.BottomCenter`**（位于返回箭头图标下方那条空白带），`padding(start=16.dp, end=16.dp, bottom=2.dp)`，**不加 `statusBarsPadding`**（Box 总高已是「状态栏+64dp」，BottomCenter 对齐的就是 64dp 顶栏底边，再补一次会把字顶进状态栏）。
- **不要改成 `TopStart`/`TopCenter`**：用户反馈靠左/顶部居中太吵、飞进状态栏都试过并否决。
- 已删除原 `UNDER_TITLE_MAX_WIDTH_FRACTION = 0.6f` 常量与菜单宽度测量逻辑；改为对称 `padding` 用满 x 轴，避免过早省略号。
- `MangaToolbar.kt` 小标题：`fontSize 11sp→12sp`、`lineHeight 15sp`、`maxLines=1`、`textAlign=Center`，注释为「图标下方空白带居中」。

### 已踩的坑

1. 小标题飞进状态栏 = 外层 Box 缺 `statusBarsPadding` 但用了 `TopStart` 对齐顶边；切到 `BottomCenter` 后该 padding 必须移除。
2. 过早省略号 = 过度避让菜单宽度（0.6f）；改成对称 padding 用满 x 轴后消失。

## 5. 验证计划（接手后第一步就跑）

- 构建目标：`vienna`（`:app:assembleVienna`），应用 ID `app.mihon.dev`。
- 设备：`NBEUWSNFWGGEEMSK`（1080x2400 @ 420dpi）。APK：`app/build/outputs/apk/vienna/app-arm64-v8a-vienna.apk`，安装 `adb install -r`。
- **Bug 1 验收**：
  1. 详情页反复「下滑离顶 → 缓慢上回顶端」，确认两行大标题不再缺行/整体消失（根因修复）。
  2. 单击大标题 → 跳整标题源搜索。
  3. 长按大标题选区 → 出现系统原生横向工具栏三项「复制 / 本地标题搜索 / 源标题搜索」；点外部 / 按返回键 → 选区与工具栏收起（返回键只退选区，不退出页面）。
  4. 短标题右侧空白点击不应触发搜索（wrapContentWidth 收拢）。
- **任务 2 验收**：下滑后小标题在返回箭头下方空白带居中、单行不挤、不飞进状态栏；回顶端大标题照常。

## 6. 红线 / 注意事项

- **不要回退**工作区里已有的并行改动（`git status` 中大片 M/A/D/?? 均属用户并行工作）。
- **不要** commit / push（用户未要求）。
- 不动 `isDebuggable`、不清应用数据、不重建本地库、不撤销存储权限。
- 本次任务已产生源码改动（见第 2/3/4 节），均未提交；后续微调只动列过的文件。
- PowerShell 对中文路径会编码损坏，路径操作用 Python（`pathlib`/`runpy`）而非 shell 直传中文路径。
- 并发 Gradle 在 Windows 会文件句柄冲突污染缓存：若编译报诡异 `Unresolved reference`/`Could not pack tree`，对受影响模块同一次调用里 `--rerun` 编译+jar 任务（如 `gradlew :app:compileViennaKotlin :app:bundle... --rerun`），不要删 build 目录。

## 7. 接手建议顺序

1. **先 `:app:assembleVienna` + `adb install -r` 真机跑第 5 节验收**——本会话最新改动（filter 白名单 + pointerInput 条件运行）后用户按「继续」要求装包，复验结果尚未确认。
2. 若菜单/点外部收起有异常，优先回看第 3 节「已踩的坑」第 3/4/5/6 条（modifier 顺序、filter 白名单、状态提升、pointerInput 条件）。
3. 若小标题位置/字号不满意，调 `AppBar.kt` 的 `Alignment.BottomCenter` + padding、`MangaToolbar.kt` 字号，勿改回 `TopStart`/`0.6f`。
4. 如需改选区相关，记住选区状态在 `MangaScreen` 持有，menu 挂在 `SelectionContainer` 层。
