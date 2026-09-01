# 滚动篇目标题（marquee）问题 — 交接文档

> 状态：**已被 `docs/search-matched-chapter-marquee-handover.md` 取代，本文件保留作历史记录。**
> 原 §6 待办里的「确认目标界面」已定案为 A：搜索网格封面右上角的 `MatchedChapterBadge`
> （不是 `marqueeTitle()`）。修复已落地到代码，仅剩设备验证。
> **后续一切进展以 `docs/search-matched-chapter-marquee-handover.md` 为准，不要再按本文 §6 动手。**

## 1. 用户诉求（原话转述）

搜索之后的结果里，**每个图片上面有一个滚动的篇目标题**，当前有两个问题：

1. **每次都是一大片空白**（标题旁/后有一大段空档）。
2. **滚到后面没内容了还在滚**（文字已滚出可视区，仍继续滚动）。

期望行为：

- **只有显示不全（溢出）的标题才滚动**，能完整显示的就静止。
- **不要“无缝循环”滚动**。
- 自然缓慢滚到结尾 → **停顿几秒**让读者看清结尾 → **瞬移回开头**再滚。

## 2. 结论摘要

- “一大片空白 + 滚到没内容还在滚 + 循环”是 Compose `basicMarquee` 的典型表现：
  默认 `MarqueeSpacing` = 容器宽度的 1/3（空白大）、文字会整个滚出左边缘（滚空），且无限循环。
- 搜索结果的“图片上的篇目标题”**本应**是漫画源搜索网格封面右上角的
  `MatchedChapterBadge`，它**没有**用 `basicMarquee`，而是自己写了一套 keyframes 跑马灯，
  逻辑上已经满足“溢出才滚 / 滚到结尾 / 停顿 / 瞬移回开头”。见 §4。
- 与诉求**不相符**的点只有一个：结尾停顿目前是 **800ms**，用户要的是“停顿几秒”。
  （开头停顿 1000ms 也偏短，可一并考虑。）

> ⚠️ 未决点：用户描述的“空白 + 循环”更符合用了 `basicMarquee` 的 `marqueeTitle`（Marquee.kt），
> 而它目前只用于音频/顶栏，不在搜索网格上。因此存在两种可能：
> 1) 用户设备跑的是**旧 APK**（之前的修复没装到设备上，代码其实已经是新的）；
> 2) 用户指的其实是另一处滚动标题。**下一步必须先确认目标界面**（见 §6）。

## 3. 相关文件（全部已确认内容）

| 文件 | 角色 | 状态 |
|---|---|---|
| `app/src/main/java/eu/kanade/presentation/browse/components/BrowseSourceMatchedChapterBadge.kt` | 搜索网格封面右上角“匹配篇目”徽标，含自研 keyframes 跑马灯 | 已提交（Initial commit），工作区无改动 |
| `app/src/main/java/eu/kanade/presentation/browse/components/BrowseSourceCompactGrid.kt` | 紧凑网格，`coverBadgeEnd` 里调用 `MatchedChapterBadge`（约 L276–278） | 已提交 |
| `app/src/main/java/eu/kanade/presentation/browse/components/BrowseSourceComfortableGrid.kt` | 舒适网格，同上（约 L276–278） | 已提交 |
| `app/src/main/java/eu/kanade/presentation/browse/components/BrowseSourceList.kt` | 列表视图，matchedChapter 作普通副标题（L264–266），**不滚动** | 已提交 |
| `app/src/main/java/eu/kanade/presentation/util/Marquee.kt` | `marqueeTitle()`：包装 `basicMarquee`（Immediately/48dp gap/45dp/s/repeatDelay 1000ms） | **未跟踪（新文件，属于并行音频工作）** |
| `app/src/main/java/eu/kanade/presentation/audio/AudioPlayerContent.kt`、`AudioReaderFloatingBar.kt`、`AudioQuickPlaySheet.kt`、`components/AppBar.kt`、`reader/appbars/ReaderTopBar.kt` | 使用 `marqueeTitle()` 的标题（音频/顶栏，非搜索网格） | 音频相关多为**未提交改动** |

### Marquee.kt（basicMarquee 封装）要点
```kotlin
MARQUEE_GAP = 48.dp
MARQUEE_SPEED_PER_SECOND = 45.dp
basicMarquee(
    animationMode = MarqueeAnimationMode.Immediately,
    repeatDelayMillis = repeatDelayMillis,   // 默认 1000
    spacing = MarqueeSpacing { _, _ -> gapPx },  // 固定 48dp
    velocity = MARQUEE_SPEED_PER_SECOND,
)
```
注释里已明确：它就是为了替代 basicMarquee 默认 1/3 宽空白（“dragged a long empty stretch”），
但仍会整段文字滚出可视区（basicMarquee 特性），即“滚空”。

### BrowseSourceMatchedChapterBadge.kt 要点
- 用 `rememberTextMeasurer` 以无约束 `Constraints()` 量出 `textWidth`，用 `onSizeChanged`
  取 `boxWidth`；仅当 `textWidth > boxWidth` 才跑动画，否则静态 `TextOverflow.Ellipsis`。
- `scrollDistance = textWidth - boxWidth`（文本尾部对齐到框右缘）。
- keyframes：
  ```
  0f                at 0
  0f                at 1000   (MARQUEE_INITIAL_PAUSE_MILLIS)
  -scrollDistance   at 1000 + scrollMillis
  -scrollDistance   at cycleMillis  (1000 + scrollMillis + 800)
  repeatMode = RepeatMode.Restart
  ```
  → 开头静止 1s → 滚到结尾 → 静止 0.8s → 瞬移回开头。**逻辑本身是对的**。
- 常量：`MARQUEE_INITIAL_PAUSE_MILLIS=1000`、`MARQUEE_END_PAUSE_MILLIS=800`、
  `MARQUEE_SPEED_DP_PER_SECOND=40dp`、`MIN_MARQUEE_SCROLL_MILLIS=800`。

## 4. 静态核对结论

- badge 的滚动方向/端点/停顿/瞬移**数学上无误**，符合用户诉求中“滚到结尾→停顿→瞬移”的描述。
- 唯一明确缺口：**结尾停顿 800ms，不是“几秒”**。

## 5. 环境与约束（务必遵守）

- 分支：`main`（旧 `main-backup` 已废弃）。
- 工作区有**大量并行未提交改动**（音频/ASMR、搜索日中/中文规范、reader 等），
  **不得回退、不得误改、不得提交**。
- 已连接设备：`NBEUWSNFWGGEEMSK`（`adb devices` 可见）。
- 默认构建目标 `:app:assembleVienna`（保留 `app.mihon.dev`），主设备优先 `arm64-v8a`。
- **仅在用户明确要求时**才构建/安装 APK；完成设备验证后再交付。
- 不要 `clear app data` / 重建库 / 重导文件当作修复捷径。

## 6. 待办 / 下一步

1. **与用户确认目标界面**（最关键，避免改错文件）：
   - A. 漫画源搜索结果的网格封面右上角“匹配篇目”徽标（`MatchedChapterBadge`）——可能性高；
   - B. 音频播放器顶栏 / 底部悬浮条 / 快捷播放弹层的曲目标题（`marqueeTitle`）；
   - C. 其他界面。
2. 若目标为 **A（badge）**：
   - 把 `MARQUEE_END_PAUSE_MILLIS` 从 `800` 提到约 `2500`（“停顿几秒”），
     可一并把 `MARQUEE_INITIAL_PAUSE_MILLIS` 提到约 `1500`。
   - 若用户仍在设备上看到“滚空 + 循环”，优先怀疑**旧 APK 未更新**——征得同意后重新构建安装验证。
3. 若目标为 **B（marqueeTitle）**：
   - 将 `Marquee.kt` 的 `basicMarquee` 方案替换为 badge 同款的
     “溢出检测 + keyframes 滚到结尾→停顿→瞬移”，消除空白与滚空；需自测宽度测量的可靠性。
   - 注意 audio 相关文件多为并行未提交改动，改动前先读 diff 避免冲突。
4. 考虑将两处跑马灯**统一成一个可复用组件**（消除两套实现），但需评估对外观尺寸的影响。
5. 改完后按 AGENTS.md 在设备上验证实际页面，再交付（不自动提交）。

## 7. 涉及字符串 / 资源

- 匹配篇目副标题文案：`MR.strings.browse_source_matched_chapter`（列表视图用）。
- 无新增字符串预期；若做统一组件无需新增资源。
