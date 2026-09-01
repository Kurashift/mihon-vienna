# 搜索结果「匹配章节徽章」滚动标题修复 — 交接文档

> 状态：**代码改动已完成，待构建与真机验收**。
> 最近一次接手（2026-08-30）已完成第 5 步的全部代码改动：修复 3.1 布局 bug、回退阈值、保留结尾停顿；第 6 步的并行编译阻塞已解除。剩余工作只剩构建 + 设备验收（第 7 步）。

---

## 1. 问题描述（用户原话）

搜索结果出来后，每个图片（封面）上方会有一个滚动的篇目标题。用户抱怨：

- 「每次都是一大片空白在那里」
- 「有时候滚到后面都没内容了，它还在滚」
- 希望：**只对显示不全的进行滚动**，不要循环滚动。
- 理想形态：自然缓慢滚到结尾 → 停顿几秒让读者看清 → 瞬移回开头重新滚。

## 2. 相关组件定位

问题的「滚动的篇目标题」是搜索结果网格封面右上角的 **匹配章节徽章**：

- 组件：`app/src/main/java/eu/kanade/presentation/browse/components/BrowseSourceMatchedChapterBadge.kt`
  - `MatchedChapterBadge(chapter)` → 内部 `MarqueeText(...)`（私有，自定义 marquee）。
- 使用处（两处 grid，各一行）：
  - `BrowseSourceComfortableGrid.kt:277`
  - `BrowseSourceCompactGrid.kt:277`
    → `MatchedChapterBadge(chapter = item.matchedChapter)`
- 列表视图 `BrowseSourceList.kt:264` 的 `matchedChapter` 是当 subtitle 显示的，**不滚动**，与本问题无关。

> 注意：项目里还有另一套 marquee —— `presentation/util/Marquee.kt` 的 `marqueeTitle()`（基于官方 `basicMarquee`，**无限循环**），用于 AppBar / 音频播放器 / 阅读器顶栏，**与搜索结果徽章无关**，本轮不要动它。

## 3. 根因分析（关键）

磁盘上的 `MarqueeText` 原本就是「非循环」的 keyframes 实现（停顿 → 滚 → 停顿 → `RepeatMode.Restart` 瞬移回开头），**方向是对的**，但有一个真正的布局 bug，导致滚到后面变成大片空白：

### 3.1 真正的 bug：文本被外层宽度约束裁掉，滚动时平移的是一个「已截断的盒子」

`MatchedChapterBadge` 外层 `Box` 有 `.widthIn(max = 110.dp)`，把 `maxWidth ≈ 102dp` 的约束传给 `MarqueeText` 的 `Text`。

`Text` 设置 `softWrap = false, maxLines = 1, overflow = TextOverflow.Clip`，在收到 `maxWidth` 约束后，**测量宽度 = min(完整文本宽, 102dp)**，即文本被裁成 102dp、只保留开头那一小段。

滚动用 `graphicsLayer { translationX = offset }`，`offset` 从 0 滚到 `-scrollDistance`（`scrollDistance = textWidth - boxWidth`）。但平移的是这个**只有 102dp 宽的盒子**，里面是「被裁掉的开头一小段文字」。于是：

- 文本后半部分**永远不会出现**（它早被 `Clip` 裁掉了）；
- 盒子平移量 ≥ 盒子自身宽（当 `textWidth ≥ 2 × boxWidth`）时，整个盒子移出可视区，屏幕只剩**大片空白**。
- 视觉表现完全吻合用户描述：「滚到后面没内容了还在滚」「大片空白」。

### 3.2 正确的 marquee 结构

要正确滚动，必须满足两点：

1. **视口固定宽度**（= badge 内容区宽，约 102dp），用 `clipToBounds` 裁剪绘制。
2. **文本以完整 `textWidth` 布局**（不受 max 约束），再整体平移，让视口裁剪。

关键是给滚动分支的 `Text` 加 `wrapContentWidth(unbounded = true)`，并让视口 `Box` 用 `fillMaxWidth()`（不是 wrap content）固定宽度、`onSizeChanged` 采集的才是稳定视口宽。

当前代码**缺 `wrapContentWidth(unbounded = true)`**，所以文本被裁、viewport 也不稳，就是根因。

> ⚠️ 原文此处曾写着「并可 `align(Alignment.CenterStart)`」，是凭印象写的，**编译不过**：
> `wrapContentWidth` 的 `align` 形参类型是 `Alignment.Horizontal`（只管水平一维），
> 而 `Alignment.CenterStart` 是 `BiasAlignment(-1f, 0f)`（二维 `Alignment`），类型不匹配。
> 正确写法是 `align = Alignment.Start`。已在 §4.1 更正。

## 4. 已做改动（当前工作区状态）

对 `BrowseSourceMatchedChapterBadge.kt` 的最终改动（**均已落地，未构建/未真机验证**）：

1. **修复 3.1 布局 bug（核心）**：滚动分支的 `Text` 加
   `Modifier.wrapContentWidth(unbounded = true, align = Alignment.Start).graphicsLayer { translationX = offset }`。
   - `align` 只接受 `Alignment.Horizontal`，故用 `Alignment.Start`（不是 `Alignment.CenterStart`，见 §3.2 的更正注记）。
   - 文本以完整 `textWidth` 布局，视口 `Box` 仍保持 `widthIn(max = 110.dp)` 推导出的稳定宽度（约 102dp）并 `clipToBounds()`，由视口裁剪而不是由 `Text` 自我截断。
   - 未采用 `fillMaxWidth()`：那会让短名字的徽章也固定撑到 110dp，反而制造空白。用 `wrapContentWidth(unbounded = true)` 即可让视口宽度 = `min(textWidth, 102dp)`，短名字仍然贴合内容。
2. **回退阈值**：删除 `MARQUEE_OVERFLOW_THRESHOLD = 1.3f`，触发条件恢复为 `textWidth > boxWidth`（即用户诉求「显示不全才滚」）。
3. **保留结尾停顿**：`MARQUEE_END_PAUSE_MILLIS = 2_300`，`MARQUEE_INITIAL_PAUSE_MILLIS = 1_000`，速度 40dp/s，`MIN_MARQUEE_SCROLL_MILLIS = 800` 下限保留。

## 5. 尚未完成 / 下一步

代码侧已无待办，剩余只有验证：

1. 构建 `./gradlew.bat :app:assembleVienna`（构建时留意工作区里是否有并发 Gradle 在跑，避免文件句柄冲突导致的假报错）。
2. 按第 7 步做真机验收；如发现轻微超宽的标题滚起来观感不佳，再考虑是否重新引入阈值（目前按用户字面诉求未加）。

## 6. 曾经的构建阻塞（已解除）

2026-08-30 复核：`LibraryViewModel.kt` 的并行改动（库搜索通过章节中文译名匹配漫画）**已经补齐依赖，不再阻塞**：

- `mihon/feature/library/QueryNodeExtensions.kt:22` 已提供
  `fun QueryNode.matches(item: LibraryItem, chapterTranslatedNames: Map<Long, List<String>> = emptyMap())`。
- `domain/.../GetChapterTranslatedNames.observe()` 返回 `Flow<Map<Long, List<String>>>`，与 `getChapterTranslatedNamesFlow()` 的返回类型一致。
- `combine` 不再是 6 流解构，而是把 favorites 与译名 map 先 `combine(..., ::Pair)`，类型可推断。

该并行改动仍属用户工作，**不要擅自回退或改动**；只是它不再挡住 marquee 的验证。

## 7. 验证方式（待完成）

- 构建：`./gradlew.bat :app:assembleVienna`
- 设备：主设备 `22041211AC`（rubens，Redmi），arm64-v8a，已 `adb devices` 确认连接。
- 安装：`adb install -r` arm64-v8a APK。
- 验收点（对应 AGENTS.md「验证实际页面状态」）：
  1. 搜索结果网格里，**超长**章节名徽章：初始停顿 → 缓慢滚到结尾 → 结尾停顿约 2~3s、末尾字符清晰可见 → 瞬移回开头重滚；**中间不应出现大片空白**。
  2. **未超宽**的章节名徽章：静止、显示省略号，不滚动。
  3. 轻微超宽的标题（阈值已回退，因此会滚一小段，滚到末尾时尾部正好对齐徽章右缘）。
  4. 滚动列表、item 复用场景下徽章不闪烁、不复用错位。

## 8. 涉及文件清单

- `app/src/main/java/eu/kanade/presentation/browse/components/BrowseSourceMatchedChapterBadge.kt`（本任务主改文件，当前有未验证改动）
- `app/src/main/java/eu/kanade/presentation/browse/components/BrowseSourceComfortableGrid.kt`（只读，使用处）
- `app/src/main/java/eu/kanade/presentation/browse/components/BrowseSourceCompactGrid.kt`（只读，使用处）
- `app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryViewModel.kt`（**用户并行工作，勿动**；原编译阻塞已解除，见 §6）
