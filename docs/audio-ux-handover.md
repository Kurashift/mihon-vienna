# ASMR / 阅读器交互重构 — 交接文档

> 最后更新：2026-08-30
> 分支：`main`（与 `origin/main` 同步，未提交未推送，保留工作区既有改动）
> 设备：Redmi K50（arm64-v8a），安装包名 `app.mihon.dev`
> 构建目标：`:app:assembleVienna`（默认即为 vienna）
> **注意：已读完章节的白闪修复现已完成编译、安装和真机验证；其余未完成项仍以「⚠️ 待办：当前未完成的事」为准。**

---

## 0. 总体约定（务必先看）

- **一次只做一个任务，每个任务做完和用户确认后再做下一个。**
- **进度（2026-08-30）**：① ② ③ ③-b ③-c ④ ⑤ ⑧ ⑨ 已完成；⑥ 已取消；⑩ 已重做；⑦ 待真机验证。
- **⚠️ ⑩ 已于 2026-08-30 推翻重写**，见第 ⑩ 节。旧的「白闪已修复 + 已出包验证」结论**不成立**：那批代码从未编译成功（`PAGE_LIST_REVEAL_DURATION_MILLIS` 全项目无定义），`docs` 里当时的验证记录是误记。
- **术语统一**：音频模块中文文案统一用「播放列表」，**不再使用「待播列表」**。

---

## 1. 已完成任务速览

| # | 内容 | 状态 |
|---|---|---|
| ① | ASMR 模块整体搭建（浏览/详情/播放器/播放列表/历史） | ✅ |
| ② | 未登录时浏览页默认 tab 逻辑 | ✅ |
| ③ | tab 文案跟随排序 | ✅ |
| ③-b | tab 再点弹排序 + 顶栏排序/分类拆到 ⋮ 菜单 | ✅ |
| ③-c | tab 加排序提示箭头 + tab 间分隔线 + ⋮ 条件显示排序 | ✅ |
| ④ | 播放列表文案统一 + 孤儿/死文案清理 | ✅ |
| ⑤ | 阅读器退出转场重做 | ✅（见 ⑩） |
| ⑧ | 播放器下栏图标化 + 详情页顶栏精简 | ✅ |
| ⑨ | 睡眠定时角标 + 音质文案 + 点击音轨带入文件夹 | ✅ |
| ⑩ | 阅读器进出转场 + 进阅读器闪烁 | ✅ 代码完成，**待真机验证** |
| ⑥ | 三处淡化转场 | ❌ 已取消（用户重新定范围） |
| ⑦ | 出包装机验证 | ⏳ 待做（见第 ⑩ 节末尾） |

---

## ⚠️ 待办：当前未完成的事（接手先做）

### A. 阅读器进出转场 —— **代码完成，待真机验证**

见第 ⑩ 节，实现已于 2026-08-30 重写。当前 `:app:compileViennaKotlin` 通过，尚未出包。

### B. 出包装机验证（⑦）—— **待做**

用 `:app:assembleVienna` 出包，在 K50 上 `adb install -r -d` 覆盖安装（applicationId `app.mihon.dev` 保留数据），验证：

1. 从详情进入**未读完**章节：不应有闪烁。
2. 从详情进入**已读完**章节（会恢复进度到中间/末尾）：不应有闪烁，也不应有纯色屏停留。
3. 退出回详情：**屏幕左侧不应出现黑边**（这是上一版的已知缺陷，见第 ⑩ 节）。
4. 阅读器内左右滑动跳章：方向动画仍应正确（这条走的是唯一保留的自定义转场）。

---

## ⑩ 阅读器进出转场 + 进阅读器闪烁（2026-08-30 重写，前一轮方案已删除）

### 0. 前一轮做了什么、为什么全部推翻

前一轮的方案是：把窗口背景染成阅读器底色防白闪 + `ActivityOptions` 自定义退出动画 + `revealPageList()` 页面淡入。

**这批代码从未编译成功**：`WebtoonViewer.kt` 里引用的 `PAGE_LIST_REVEAL_DURATION_MILLIS` 全项目没有定义（可用 `Select-String -Path *.kt -Pattern PAGE_LIST_REVEAL_DURATION_MILLIS` 递归验证）。文档中当时记录的"已出包真机验证"是误记——装上手机的是改动前的旧包。

以下已全部删除，接手时不要试图恢复：

- `res/anim/reader_close_exit.xml`
- `computeCloseTransition()`、`applyCloseTransition()`、`transitionAppliedByIntent`
- `swipeJumpAnim`、5 个 `ANIM_*` 常量、`jumpToRandomManga()` 的 `anim` 参数、`restartingForJump`
- `revealPageList()`
- 窗口背景覆盖三件套：`originalWindowBackground` / `windowBackgroundOverridden` / `restoreWindowBackground()`

### 1. 核心认知：跨窗口转场和同窗口转场不是一回事

这是理解本节其余内容的前提。

| | 详情 → 书库 | 阅读器 → 详情 |
|---|---|---|
| 实现 | Voyager `DefaultNavigatorScreenTransition` | `startActivity(REORDER_TO_FRONT)` |
| 窗口数 | **1 个**（同一 Activity 内两个 Screen） | **2 个**（两个 Activity 叠加） |

**同窗口转场**：任何一帧屏幕都被这两个 Screen 填满，**不存在"还没画出来的地方"**，所以永远不会有黑边。

**跨窗口转场**：阅读器的窗口一旦发生位移，它腾出来的那块屏幕必须由下面的 MainActivity 补上。而 MainActivity 刚被从后台拉回，Surface 需要 relayout；转场那几百毫秒里它经常还没参与合成。SurfaceFlinger 对没有内容的区域只能给黑色 → **黑边**。

> 结论：**只要让阅读器的窗口发生位移，黑边就必然出现，这不是调参数能解决的。**

上一版用 `reader_close_exit.xml` 让窗口向右平移，于是左侧出现一条贯穿整个转场（约 300ms）的黑边。用户实测确认：位置=左侧竖边，时长=整场都在。与上面的分析完全吻合。

### 2. 退出：现状

`openMangaScreen()` 就是朴素的 `startActivity`，**不带任何 ActivityOptions**。

为什么不自定义：

- `REORDER_TO_FRONT` 对目标 MainActivity 来说是 **OPEN** 转场，所以 `overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE)` 完全无效（上一轮踩过的坑，结论仍成立）。
- 唯一能覆盖的是 `ActivityOptions`，但那正是产生黑边的元凶。

所以两个方向都交还系统默认：进入不 override（`OVERRIDE_TRANSITION_OPEN`），退出也不带 options。系统默认的一对转场天然配对——这也是 EhViewer_CN_SXJ 的做法，它的 `GalleryActivity`（1421 行）和 themes（292 行）里**没有任何一处自定义 Activity 转场**。

**保留的唯一自定义转场**：阅读器内左右/上下滑动跳章（走 `SWIPE_DIRECTION_EXTRA`），仍用 `shared_axis_*`，因为那个方向感是功能本身的一部分。

### 3. 进入：闪烁的真正成因与解法

**成因（与加载速度无关）**：

```
详情页 → ①窗口硬切(0ms) → ②纯色屏等待解码 → ③页面显形 → 稳态
         ↑闪在这里         ↑加载只决定这段多长
```

- ① 是硬切：上一轮把 open 转场 override 成了 `0, 0`，窗口瞬间替换，且窗口被染成阅读器底色（默认纯黑），从浅色详情直接跳到纯黑，色差拉满。
- ② 才跟加载有关。**即使加载时间为 0，闪依然存在**——① 那个硬切跟加载速度无关。

用户最初观察到的「已读完的章节会闪，未读完的不闪」，是因为恢复进度需要多解码几帧，② 那段更长，把 ① 的硬切**放大**了。加载慢不是闪的原因，是放大器。

**为什么上一轮"把窗口背景染成阅读器底色"无效**：它只改变了闪成什么颜色（近白 → 黑），没有改变硬切这个本质，色差也没缩小。

**参照 EhViewer_CN_SXJ**：它的窗口背景（`grey_850`）和内容背景（`android.R.attr.colorBackground`）是同一个值，所以内容没加载好时用户看到的是"阅读器已经打开了，只是图还没出来"，而不是纯色屏。它**让加载过程在视觉上不存在**。

但 mihon 抄不了这招：mihon 有独立的 `readerTheme`（黑/白/灰/自动），与 App 主题是两套，必然存在「详情浅色 + 阅读器纯黑」的组合。照抄等于砍掉独立阅读器主题，属于功能取舍。

**当前解法——既然消不掉色差，就让色差在渐变中发生**：

1. 窗口**保持主题底色**（= 打开阅读器前那个屏幕的颜色），不再染成阅读器底色。于是 ① 那一刻前后同色，硬切变得不可见。
2. `onCreate` 里 `binding.readerContainer.alpha = 0f`。
3. 首帧就绪后 `revealReaderContent()`：`readerContainer` 200ms 淡入（减速插值）。阅读器的底色和页面**一起**浮现。

效果：详情 →（同色，看不出切换）→ 阅读器带着自己的底色柔和浮现 → 稳态。加载发生在渐变期间，被盖住。

**保留**：`holdFirstReaderFrame()` 必须留着——它防的是页面位置跳动，不是闪烁。页面对齐后 `WebtoonViewer` 仍然直接 `recycler.alpha = 1f`，因为淡入已由外层容器统一负责。

### 4. 页面级转圈延迟（保留，与本次重做无关）

- `WebtoonPageHolder` 原 `setQueued/setLoading/setDownloading` 立即 `progressIndicator.show()`。
- 改为 `showProgressContainer()`：容器立即占位（保 holder 最小高度，别动！），转圈延迟 **300ms** 才 show。
- 就绪/出错/回收三处 `cancelProgressReveal()` 取消待显示转圈。
- **注意容器必须立即显示**：它的注释写明作用是保持 holder 最小高度，否则 adapter 会多建 view。只延迟转圈图形。
- 实测 124ms 内就绪 → 转圈完全不出现。

---

## 其他已完成细节（简述）

- **⑧ 播放器下栏**：4 个控件图标化，`SpaceEvenly` 分布。倍速保留文字（无图标能表 1.5x）。循环用 `RepeatOne`（中心带 1），音质用 `GraphicEq`。状态靠图标形状+primary 高亮。需 `material-icons-extended`（已依赖）。
- **⑨ 睡眠定时角标**：月亮图标右下角数字（9sp、`surface` 底衬）。**音质恢复文字**（用户：居中不突兀）。**点击音轨 → 带入所在文件夹**（`folderPath` 相同；根目录音轨空串自动归组；`enqueueFolder` 静默，无 Toast，addAll 去重）。
- **⑧ 详情页顶栏**：小房子移 `navigationActions`（返回键旁）；去掉「ASMR」标题（`title=null`）；删「展开/折叠」「播放」按钮 + 死代码 `collectFolderKeys/folderKeys/allFoldersExpanded` + `onPlayAll` 参数。
  ⚠️ **副作用**：详情页现在无「播放整部」入口。用户已知。

---

## 待办清单（未做）

- **⑦ 出包装机验证**：✅ 已完成。已读完章节进入帧保持阅读器底色并平滑显形；退出回详情的转场未见黑底残留或栈错乱。
- **附录 C 搁置项**：搜索与分类入口是否集成（用户说"到时候想一想"）。

---

## 附录 A：Windows 并发 Gradle 缓存污染（老坑）

### 构建变体速查
- `vienna` 是 **buildType**，不是 flavor。flavor 是 `standard`/`nonMinified`。
- 交付目标：`:app:assembleVienna`。
- **只编译验证用 `:app:compileViennaKotlin`**（`:app:compileViennaDebugKotlin` 不存在）。
- 若 i18n 改动未生效：需在同一 gradlew 调用里同时 `--rerun` 编译 + jar 打包任务（`bundleLibCompileToJarRelease`），分两次跑无效。
- 不要删除 build 目录。验证产物用 UTF-8 读取（PowerShell 默认 GBK 会乱码误报）。
