# 漫画详情页骰子 FAB 与本库定位按钮的位置对齐

> 状态：**已完成**。本文记录最终实现，以及上一会话（另一个模型）反复试错后被推翻的结论，避免以后再绕一遍。

## 一、目标（两条，缺一不可）

1. **本库（本地源）左下角「定位/骰子」按钮**：
   - 导航栏**可见**时：贴导航栏上方 16dp。
   - 导航栏**收起**时：按钮**原地不动**，不跟着缩、不往上跳。用户原话：「滑动时按钮像抢位置一样抖」「导航栏缩下去时按钮莫名其妙上跳」。

2. **漫画详情页左下角「续读/骰子」按钮（`RandomGestureFab`）**：
   - 与本库那个按钮在**导航栏可见时的绝对高度一致**，体感上「从本库点进详情页，按钮位置不动」。

## 二、几何关系（先算清楚，别再猜魔法数）

设备手势条（system bars bottom）记为 **G**。

| 位置 | 底部距屏幕底 | 说明 |
|---|---|---|
| 导航栏总高 | `80 + G` | `NavigationBar.kt` 里 Row 硬编码 `80.dp`，再叠 `windowInsetsPadding(bottom = G)` |
| 本库 FAB（静止、栏可见） | `80 + G + 16` | 栏高 + 16dp 间距 |
| 详情页 FAB（未抬升） | `G + 16` | 项目 `Scaffold` 的 FAB 槽：`max(bottomBarHeight, bottomInset) + fabHeight + 16`，详情页无 bottomBar，故 `G + 16` |

**差值恰好是常量 80dp**（`NavigationBar` 的 Row 高度），不是猜的魔法数。上一会话认定「不是一个 80dp 常量能对齐的」——这个判断是错的，后来反复试各种数值全部失败，是因为**补偿时机**不对，不是数值不对。

## 三、最终实现

### 1. `presentation/components/BottomNavHeights.kt`

只有两个对外声明，删掉了原先语义混乱的 `BottomNavBarHeight` / `LocalBottomNavVisible` / `LocalBottomNavMaxHeight` 三件套：

- `BottomNavFabLift: Dp` —— 可组合 getter，平板（导航栏是侧边 rail）返回 `0.dp`，手机返回导航栏 Row 的高度 `80.dp`。
- `LocalBottomNavFabPadding: Dp` —— 底部 FAB 需要的**额外**抬升量，默认 `0.dp`（宿主没有导航栏时用不到）。

### 2. `HomeScreen.kt`

- 顶部先读一次手势条高度：`val systemBarsBottom = WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()`。
  **要在 Scaffold content 的 `consumeWindowInsets` 之前读**，否则读到的是被消费后的值。
- Scaffold content 里，按当前 content padding 反推出导航栏此刻占了多少：

```kotlin
val bottomBarHeight = (contentPadding.calculateBottomPadding() - systemBarsBottom)
    .coerceAtLeast(0.dp)
CompositionLocalProvider(
    LocalBottomNavFabPadding provides (BottomNavFabLift - bottomBarHeight).coerceAtLeast(0.dp),
) { Box(...) }
```

- 三个浏览组件（`BrowseSourceList` / `CompactGrid` / `ComfortableGrid`）的定位按钮：

```kotlin
.align(Alignment.BottomStart)
.windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Bottom + WindowInsetsSides.Start))
.padding(start = 16.dp, bottom = 16.dp + LocalBottomNavFabPadding.current)
```

### 3. `MangaScreen.kt`（小屏 + 大屏两处）

```kotlin
RandomGestureFab(
    ...
    modifier = Modifier.offset(y = -BottomNavFabLift),
)
```

**必须用 `offset`，不能用 `padding`**：`padding` 会撑大 FAB 槽的测量高度，项目 `Scaffold` 会把 FAB 高度算进 content padding（见 `Scaffold.kt` 的 `fabOffsetFromBottom`），章节列表底部会凭空多出 80dp 空白。`offset` 只改放置、不改测量。

## 四、为什么原来会抖，为什么现在是准的

**原实现**：`LocalBottomNavVisible` 是个布尔，`bottomNavVisible` 一翻转，按钮 padding 立刻从 `0` 跳到 `80.dp`，而此时 `AnimatedVisibility` 的 `shrinkVertically(tween(220))` 才刚起步。按钮瞬间上跳 80dp，导航栏还在慢慢收 → 「上跳 + 抢位置」。

**现实现**：抬升量直接由 `contentPadding` 推算。`contentPadding` 是 Scaffold 在**同一次 measure** 里通过 subcompose 下发的，和导航栏的动画值同帧同源，所以是精确互补：栏降到多少，按钮补多少。

### 关于「max - current 实时差值」和「onSizeChanged 追高」

上一会话把这两条列为「抖动根源，不要复活」——**结论对，理由错**，需要区分：

- `onSizeChanged` 追高：写 state → **下一帧**才生效，天生落后一帧，跟动画必抖。这条确实不能用。
- `max - current` 本身没问题，**只要 current 来自同帧的 `contentPadding`**。现在用的就是它。

## 五、顺带修掉：上滑时导航栏一片空白

同一轮反馈的 bug：「上滑的时候导航栏莫名其妙被划上去，然后一片空白」。

原因：`NavigationBar` 的布局是 `windowInsetsPadding(bottom = G)` + `height(80.dp)`，即**底部 G dp 是空的手势条留白**，条目在上面 80dp 里。而 `AnimatedVisibility` 的 `expandVertically` / `shrinkVertically` **默认以 Bottom 为锚点**，于是动画过程中先露出来的是底部那条空白 → 看起来就是「空白条先被划上来，图标最后才出现」。

修法：显式指定以栏自己的顶边为锚点。

```kotlin
enter = expandVertically(..., expandFrom = Alignment.Top) + fadeIn(...)
exit = shrinkVertically(..., shrinkTowards = Alignment.Top) + fadeOut(...)
```

## 六、附带修复：本库定位按钮「有时候会消失」

用户反馈：本库左下角按钮会消失，但印象里应该有骰子（随机漫画）兜底常驻。

**根因**：`BrowseSourceLastReadFab` 里有一行把兜底整个短路掉：

```kotlin
if (lastReadMangaId != null && manga == null) return   // 已删
```

- `lastReadMangaId` 来自 `BrowseSourceViewModel`，是**全库 `lastOpenedAt` 最大那条的 id**，只要在本地看过任何一本就非 null → 骰子分支永远进不去。
- `manga == null` 表示最后阅读那本不在 `mangaList.itemSnapshotList` 里。本地源 `PAGE_SIZE = 50`（`LocalSource.kt:2347`），3000+ 本只持有已加载的几页，所以以下情况都会命中：
  1. 最后阅读那本在未加载的分页里（最常见）；
  2. 阅读状态/标记筛选把它筛掉了；
  3. 刷新或目录变化导致 paging 失效、快照缩回第一页。

**修法**：删掉那行短路，让 `manga == null` 时统一落到骰子分支。仍然保留「没有随机回调就隐藏」的行为（在线源不受影响）。

> 注：这行短路是上一会话加骰子兜底时自己引入的——写了兜底分支又在它上面一行 `return` 掉。上游原本的行为是「找不到就整个隐藏」。

## 七、上一会话改动的整体审计（含用户已裁决项）

### 7.1 已修的技术缺陷：`RandomGestureFab` 里的死状态 `armed`

`armed` 只写不读（1 处声明 + 2 处写入，零读取点），却每次拖过触发阈值就触发一次**整块重组**。而该文件里其余每帧状态都刻意绕开重组（`liveOffset` 只在 placement 阶段读）。已删除声明与两处写入，并修正注释：原文称 `liveOffset`「不进 composition」是错的，它正是 `animateOffsetAsState` 的 target，每帧都进。

### 7.2 用户已裁决：在线源随机入口不恢复

上一会话删除了 `BrowseSourceToolbar` 的 `onOpenRandomManga` / `onOpenRandomGoodDoujin` 两个菜单项，而新增的骰子兜底 `onRandomManga` 仅对本地源生效（`if (viewModel.source is LocalSource)`）。**净效果：在线源的随机入口彻底消失**，旧的没了、新的不覆盖。

用户裁决：**不恢复**，在线源就是没有随机入口。据此已删除变成死代码的 `BrowseSourceViewModel.pickRandomMangaId()`（原在线源走它从已加载列表里挑）。

> 注意：在线源现在走「找不到最后阅读条目就隐藏按钮」的旧路径，因为 `onRandomManga`/`onRandomGoodDoujin` 传入的是 `null`。这是预期行为，不是 bug。

### 7.3 用户已裁决：详情页随机只用 FAB 手势

上一会话同样删除了 `MangaToolbar` 的 `onClickRandom` / `onClickRandomGoodDoujin` 菜单项。这两个回调仅对本地漫画非空（`successState.manga.isLocal()`）。

已知可达性缺口：FAB 的可见性是 `chapters.isNotEmpty() && !isAnySelected`，所以**本地漫画在无章节、或处于多选模式时，随机功能无法触达**。

用户裁决：**保持只有手势，接受该缺口**。未做改动。

### 7.4 遗留死代码（未处理，需知悉）

`BrowseSourceViewModel.rememberReturnAnchor()` 的两处调用被上一会话一并删除，现为死函数。但 `LibraryReturnAnchorStore` 全仓库**没有任何读取方**（只有 DI 注册 + ViewModel 转发），这个「返回定位」功能的读写链本来就是断的，本次删除不改变实际行为。**若后续要启用该功能，需同时补上读取方。**

### 7.5 无需清理的项

- 多语言字符串 `action_open_random_manga` / `action_open_random_good_doujin` / `good_doujin_list_empty` / `good_doujin_list_no_others` 仍被引用（`RandomGestureFab`、`BrowseSourceLastReadFab`、`LibraryToolbar`、浏览页与详情页的 snackbar），**不要删**。
- `MangaToolbar` 删除两个参数后无残留未使用导入，已确认干净。

## 八、验收清单（用户实测标准）

1. 本库下滑：定位按钮**纹丝不动**，无抖动、无上跳、无下缩。
2. 本库上滑：导航栏回来，按钮仍**贴导航栏上方 16dp**，且导航栏**整条带图标一起升起**，不出现空白条。
3. 从本库点进详情页：左下续读按钮和本库定位按钮**在屏幕同一高度、不跳**。
4. 详情页章节列表底部**没有**多出空白。
5. 随机手势（上滑=好本子随机、右滑=普通随机、长按=方向提示）不受影响。
6. 在线源的浏览页（没有导航栏）按钮位置不变，仍贴底部 16dp。
7. 平板（导航栏是侧边 rail）不受影响，按钮不额外抬升。
8. 本库左下角按钮**始终存在**：能定位时是定位图标，定位不到时变骰子（随机漫画），不会整个消失。在线源行为不变（没有随机回调时隐藏）。

## 九、环境提示

- 工作区 `d:/DATA/mihon`，`main` 分支，`vienna` 是唯一交付 buildType。
- 编译验证：`gradlew :app:compileViennaKotlin`；出包 `gradlew :app:assembleVienna`。
- **注意并发**：本仓库用户可能同时在跑别的任务（尤其 `MangaInfoHeader.kt` 标题选区、i18n 等）。全量编译失败时先用 `git status` 看报错文件是不是正被并行修改，不要误判为自己的回归。
- Compose 版本下 `PaddingValues.calculateBottomPadding()` 是**接口成员**，不要 import；`WindowInsets.systemBars` 是扩展属性，**需要** `import androidx.compose.foundation.layout.systemBars`。
