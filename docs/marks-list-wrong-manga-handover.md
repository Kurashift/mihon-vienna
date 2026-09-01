# 标记清单点击已删除篇目进入错误漫画 — 交接文档

> 最后更新：2026-08-30
> 分支：`main`（与 `origin/main` 同步，未提交未推送，保留工作区既有改动）
> 构建目标：`:app:assembleVienna`（默认即为 vienna，保留 applicationId `app.mihon.dev` 原位覆盖）
> 影响范围：`标记清单`（个人清单 → 标记清单，`ChapterFlagListScreen`）
> **状态：代码与决策均已定稿，本任务自身只差「编译 → 出包 → 真机验证」。**
> **当前按用户决定整体搁置**（他仍在并行编辑其他任务的文件）。
> 出包阻塞全部来自用户的并行改动，本任务一律不代改：阻塞清单见 **4.6**，恢复步骤见 **4.7**。
> ⚠️ 编译错误是**分批暴露**的，修完眼前这批还会有下一批，见 **4.5**。

---

## 0. 结论速览（接手先看这一节）

**现象**：在「个人清单 → 标记清单」里点击某个篇目条目，该篇目的本地文件已在手机文件管理器中删除，结果进入了另一个漫画 / 另一个篇目。

**根因**：标记清单的点击跳转只校验「漫画是否还在库中」，不校验「被标记的篇目是否还存在、是否仍属于该漫画」，于是启动阅读器；阅读器在按 `chapterId` 找不到目标篇目时会**静默回退到列表第一篇**，落在错误内容上。

**修复**：见第 2 节。改动只涉及一个文件：

- `app/src/main/java/eu/kanade/presentation/more/settings/screen/advanced/ChapterFlagListScreen.kt`

**下一手要做**：见第 4 节「验证与遗留」，代码完成后需要出包真机验证。

---

## 1. 问题与完整链路

### 1.1 用户报告

> 个人清单中，比如在标记清单点击该篇目（该篇目事实上我已在手机的文件管理中实际删除），进入的却是另一个漫画。

### 1.2 涉及的文件与入口

- UI 入口：`eu.kanade.presentation.more.settings.screen.advanced.ChapterFlagListScreen.kt`
  - 一级：`ChapterFlagListScreen`（每行一部漫画）
  - 二级：`ChapterFlagDetailScreen`（某部漫画下所有被标记的篇目）
  - 二级点击篇目 → `openChapterReader(context, scope, mangaId, chapterId)` → `ReaderActivity`
  - 一级封面/二级右上角「打开漫画」→ `openManga(...)` → `MangaScreen(mangaId)`
- 阅读器章节目录回退：`eu.kanade.tachiyomi.ui.reader.ReaderViewModel.kt`
  - `chapterList` lazy 块与 `init()` 里都有 `find { it.id == chapterId } ?: firstOrNull()` 的兜底
- 本地库同步（删除文件的来源）：`eu.kanade.domain.chapter.interactor.SyncChaptersWithSource.kt`
  - 文件消失后，`removeChaptersWithIds` 删除对应 `chapters` 行
- 标记存储：`eu.kanade.tachiyomi.data.manga.MangaMarkStore.kt`
  - `MangaMark(mangaId, mangaTitle, chapterId, chapterName, markedAt)` 以 JSON 偏好存储，**不会随 DB 清理**

### 1.3 发生链路

1. 用户在文件管理器删除篇目文件。
2. 本地库同步时 `SyncChaptersWithSource` 删除对应 `chapters` 行。
   - 注意：**漫画 `mangas` 行不会被删除**（本地库删除文件不会自动删漫画行；`DELETE FROM mangas` 仅有 `deleteNonLibraryManga` 这个手动清理入口）。
3. 标记名单是 JSON 快照，**不会被同步清理**，所以这条失效标记仍留在「标记清单」。
4. 旧版 `openChapterReader` 只查 `getMangaById(mangaId)` 是否成功——漫画行还在，判定通过。
5. 启动 `ReaderActivity` 后，`ReaderViewModel` 按 `chapterId` 找不到该篇目 → 回退 `chapterList.firstOrNull()`：
   - 大多数情况会打开**同一部漫画的第一篇**（用户感知为「不是我要的那个」）；
   - 当标记里的 `mangaId`/`chapterId` 因移动、恢复、删除后 ID 复用等成为**陈旧 ID**时，会落到**另一部漫画**（即用户上报的最终现象）。
   - `mangas` / `chapters` 两张表的 `_id` 均为 `INTEGER PRIMARY KEY`（无 AUTOINCREMENT），删掉最高 id 后新插入会复用，因此陈旧 ID 指向新漫画是真实可能发生的。

### 1.4 三处叠加

| # | 环节 | 问题 |
|---|---|---|
| 1 | 标记存储 | 失效标记不清除（`MangaMarkStore` JSON 快照） |
| 2 | 跳转前校验 | `openChapterReader` 不校验篇目存在与归属 |
| 3 | 阅读器兜底 | 目标篇目缺失时静默回退 `firstOrNull()` |

三处叠加，第 2 处是**本次最小完整修复点**。

---

## 2. 已做的修复

### 2.1 文件

`app/src/main/java/eu/kanade/presentation/more/settings/screen/advanced/ChapterFlagListScreen.kt`

### 2.2 改动一：新增 import

```kotlin
import tachiyomi.domain.chapter.repository.ChapterRepository
```

（`ChapterRepository` 已注册在 DI：`DomainModule.kt` 第 158 行 `addSingletonFactory<ChapterRepository> { ChapterRepositoryImpl(get()) }`，无需改动 DI。）

### 2.3 改动二：`openChapterReader` 增加篇目存在 + 归属校验

原逻辑：

```kotlin
scope.launch {
    val repository = Injekt.get<MangaRepository>()
    val exists = runCatching { withIOContext { repository.getMangaById(mangaId) } }.isSuccess
    if (exists) {
        context.startActivity(ReaderActivity.newIntent(context, mangaId, chapterId))
    } else {
        context.toast(MR.strings.marks_list_manga_missing)
    }
}
```

新逻辑：

```kotlin
scope.launch {
    val mangaRepository = Injekt.get<MangaRepository>()
    val chapterRepository = Injekt.get<ChapterRepository>()
    val mangaExists = runCatching { withIOContext { mangaRepository.getMangaById(mangaId) } }.isSuccess
    val chapter = runCatching { withIOContext { chapterRepository.getChapterById(chapterId) } }.getOrNull()
    when {
        !mangaExists -> context.toast(MR.strings.marks_list_manga_missing)
        // The reader silently falls back to the first chapter when the requested one is gone,
        // and a stale mark whose ids no longer resolve to the same manga would land on the
        // wrong comic. Only launch the reader for a chapter that still exists and belongs to
        // the manga the mark was recorded against.
        chapter == null || chapter.mangaId != mangaId -> context.toast(MR.strings.chapter_not_found)
        else -> context.startActivity(ReaderActivity.newIntent(context, mangaId, chapterId))
    }
}
```

### 2.4 语义

- 漫画不在库中 → `marks_list_manga_missing`（「该漫画已不在库中」）
- 篇目不存在或不属于该漫画（陈旧 ID）→ `chapter_not_found`（「未找到篇目」）
- 两项都通过才启动阅读器

复用已有字符串 `chapter_not_found`，**未新增 i18n 资源**。

---

## 3. 影响面审计

### 3.1 直接改动面

- 只改了 `ChapterFlagListScreen.openChapterReader`。
- `openManga`（打开漫画页）保持不变：仍只校验漫画存在。对「漫画行还在、只是没有章节」的场景，会进入一个空章节列表的漫画页——这与本次上报的「点篇目」路径不同，**本次未处理**，见第 4 节遗留项。

### 3.2 共享调用方

- `openChapterReader` 仅被 `ChapterFlagDetailScreen` 的篇目行点击调用，无其他调用方。
- 阅读器回退逻辑（`ReaderViewModel`）**未改**，其它进入阅读器的路径（续读、章节目录点击、随机、深链）行为不变——这是有意的，避免影响既有阅读器语义。

### 3.3 需要注意的相邻功能

- 标记清单同时服务两个列表：`DUPLICATES`（`MangaMarkStore`，JSON 偏好）与 `GOOD_DOUJINS`（`GoodDoujinStore`，数据库表）。两者共用同一套 `ChapterFlagListScreen`，因此本次修复**对两个列表同时生效**。
- `GoodDoujinStore` 在启动时已有 `removeNonLocal()` 清理；`MangaMarkStore` 没有。**本次未给 `MangaMarkStore` 加清理**，避免在进入页面时触发扫描/写偏好（违反「进入页面不扫描、先显示旧数据」约定）。

---

## 4. 验证与遗留（接手先做）

### 4.1 待验证项（真机）

按约定，修复不以此为完成标准——需出包真机验证：

1. 给一部本地漫画的某篇目打标记；在文件管理器删除该篇目文件（或整个文件夹）。
2. 触发一次本地库同步/手动刷新，确认该篇目已从章节目录中消失。
3. 打开「个人清单 → 标记清单」→ 该漫画 → 点击那条已删除的篇目：
   - 预期：提示「未找到篇目」，**不进入阅读器**，更不进入其它漫画。
4. 正常情况（篇目仍在）：点击标记条目应照常进入对应篇目阅读器。
5. 标记一条后，用「打开漫画」入口（一级封面 / 二级右上角）跳转：应仍能进入漫画详情页（本路径未改动，回归确认）。
6. 「好本子清单」同样操作一遍：行为应与标记清单一致。

### 4.2 已决策：不做自动清理（2026-08-30 用户拍板）

**结论：保留「先显示旧数据 → 点击时提示「未找到篇目」→ 用户手动删除该条标记」的行为。**

- 方案 A（`SyncChaptersWithSource` 删除 `chapters` 行时同步调用 `MangaMarkStore.clearMangas(...)` / `remove(...)`）：**搁置，不实施**。
- 方案 B（清单打开时做轻量存在性过滤，置灰/折叠）：**搁置，不实施**。

理由（用户明确要求以稳定为先，不追求自动清理）：

- **不写用户数据**：清理标记是对 `MangaMarkStore` 的写操作，一旦与同步逻辑的时序判断有偏差，可能误删用户仍然有效的标记且不可恢复。留给用户手动删除，最坏情况只是一次多余点击。
- **不触发清理 / 扫描**：方案 B 会在进入清单时引入存在性核对，违反「普通进入页面不得等待文件系统重新扫描」的约定。
- **行为可预期**：失效标记退化为一条「点了会提示」的记录，无副作用、无后台任务、无额外状态。

**`openManga` 同样保持现状**：只校验漫画是否存在，不预检章节与磁盘目录。

- 「漫画行还在但章节为空」时会进入空章节列表的漫画详情页。这是可预期的降级行为，**不会跳转到错误漫画**——本次上报的现象只发生在「点篇目」路径（`openChapterReader`），已由 2.3 修复覆盖。
- 追加磁盘直读校验会引入文件系统访问、UTF-8 严格解码以及 3 个已知损坏文件的排除处理，与「稳定优先」相悖，故不做。

> 后续如要重新考虑清理，只应在用户再次明确提出时，从方案 A 入手，并且必须保证清理只发生在「确认 DB 篇目行已删除」的同一事务边界内。

### 4.3 构建与安装提醒

- 未出包、未安装。
- 正式交付前不要保留 `isDebuggable = true`；本修复未触碰 manifest/构建脚本。
- 只构建 vienna：`:app:assembleVienna`，目标设备 arm64-v8a，`adb install -r -d` 原位覆盖。
- 注意：`vienna` 是 **buildType** 而不是 productFlavor。变体任务名是 `:app:compileViennaKotlin` / `:app:assembleVienna`，
  **不存在** `compileViennaDebugKotlin` 这类 flavor+buildType 组合名（这也是 `vienna` 的 applicationId 为 `app.mihon.dev` 的原因）。

### 4.4 编译验证记录（2026-08-30）

运行 `:app:compileViennaKotlin` 的结果：

- **本次改动无编译错误。** 编译器仅报出 1 个错误，位于无关文件：

  ```
  app/src/main/java/eu/kanade/presentation/browse/components/BrowseSourceMatchedChapterBadge.kt:134:65
  Argument type mismatch: actual type is 'Alignment', but 'Alignment.Horizontal' was expected.
  ```

- 该文件属于用户并行的「搜索匹配章节跑马灯」任务（见 `docs/search-matched-chapter-marquee-handover.md`），
  在 `git status` 中为已修改状态，**与本次标记清单修复无关**。
- 出错行：

  ```kotlin
  .wrapContentWidth(unbounded = true, align = Alignment.CenterStart)
  ```

  `wrapContentWidth` 的 `align` 形参类型是 `Alignment.Horizontal`，而 `Alignment.CenterStart` 是二维 `Alignment`。
  改为 `Alignment.CenterHorizontally` 即可通过（此处语义不变：水平居中、垂直不参与该修饰符）。

- 其余 116 个任务全部 `UP-TO-DATE`，依赖模块（`domain` / `data` / `i18n` / `core-metadata` 等）的
  `compileReleaseKotlin` 与 `bundleLibCompileToJarRelease` 均正常，**未出现缓存污染导致的假报错**。

### 4.5 编译阻塞是「分批暴露」的（重要，接手必读）

**全项目编译一次不会列出所有错误。** Kotlin 在分析阶段遇到某些错误后会中止后续文件的检查，
因此错误是**一批一批**冒出来的：修掉当前这批，编译才会继续往前走并暴露下一批。

实测证据（同一条命令 `:app:compileViennaKotlin`）：

| 轮次 | 执行的任务数 | 报出的错误 |
|---|---|---|
| 第 1 轮 | 2 / 118 | `BrowseSourceMatchedChapterBadge.kt:134`（1 个） |
| 第 2 轮 | 10 / 118 | `AppBar.kt:60` + `AppBar.kt:183`（2 个） |

**所以第 2 轮的错误不是新引入的**——它在第 1 轮编译时就已存在于工作区，只是被第 1 轮的错误挡住没机会报。

推论：出包前大概率还有第 3 批、第 4 批。**不要假设「修完眼前这批就能出包」。**

### 4.6 阻塞清单（按发现顺序，全部属用户并行任务，本任务不代改）

#### ① 已解除：`BrowseSourceMatchedChapterBadge.kt:134`

- 归 `docs/search-matched-chapter-marquee-handover.md`。
- 原为 `wrapContentWidth(unbounded = true, align = Alignment.CenterStart)`：
  `align` 形参类型是 `Alignment.Horizontal`（一维），`Alignment.CenterStart` 是二维 `Alignment`。
- **用户已于 2026-08-30 自行修复**为 `align = Alignment.Start`。
- 注：`unbounded = true` 时子元素按完整自然宽度布局、容器无多余空间，`align` 实际不产生任何位移，
  故该修复为纯形式修改，无视觉影响。

#### ② 待用户处理：`AppBar.kt`（`docs/manga-detail-title-handover.md` 的并行未完成改动）

**错误 A —— 多余且错误的 import（第 60 行）**

```kotlin
import androidx.compose.ui.unit.toDp   // 包路径不对；且全文检索确认文件内未使用 toDp
```

`toDp` 不在 `androidx.compose.ui.unit` 包（它是 `Density` / `LocalDensity` 的扩展）。
全文件仅此一处 import、无任何调用点 → **直接删除即可，零影响**。

**错误 B —— 常量只有使用处、没有定义处（第 183 行）**

```kotlin
.fillMaxWidth(UNDER_TITLE_MAX_WIDTH_FRACTION)
```

全仓库检索：仅此一个使用点，**无任何定义**。

- 该常量预期值来自 `docs/manga-detail-title-handover.md`：
  - 第 26 行记录当前宽度上限为 **0.6f**；
  - 第 70 行「拟定方案」写着要**调大**它（新值未定）。
- 因此取值属于**视觉决策，必须由用户定**：填 0.6f 仅恢复现状，与「调大」的意图相悖。
- 此错误**并非新发现**：`docs/library-search-translated-name-handover.md:116` 早有记录
  （当时报的是 `:181`，行号因后续编辑位移至 `:183`），只是同样被前一个阻塞挡着。

同批 `AppBar.kt` 改动还包含：`Column` → `Box`、新增 `navigationUnderTitle` 覆盖层、
`basicMarquee` → `marqueeTitle()`。整体属 manga-detail-title 任务，本任务不介入。

### 4.7 当前暂停点（2026-08-30 用户决定：全部搁置）

**用户明确要求：他还在并行编辑其他文件，本任务先整体搁置，等他全部处理完毕再继续。**

因此 **4.6 ② 的 `AppBar.kt` 两处（含那行无害的 `toDp` import）一律不动**——
虽然删除多余 import 本身零风险，但该文件正被用户并行编辑，避免两边保存互相覆盖。

**标记清单任务自身的状态不变：代码定稿、决策定稿，只差「编译 → 出包 → 真机验证」。**

恢复时按顺序执行：

1. **确认全项目可编译**：跑 `:app:compileViennaKotlin` 直到 `BUILD SUCCESSFUL`。
   - 注意 4.5：可能要反复几轮，每轮修掉一批错误再重跑。
2. **出包**：`:app:assembleVienna`（arm64-v8a APK；applicationId `app.mihon.dev`，`adb install -r -d` 原位覆盖）。
3. **真机验证**：严格按 4.1 的 6 项走，重点是第 3 项（点已删除篇目 → 只提示「未找到篇目」、不进阅读器）
   与第 6 项（好本子清单同行为，两列表共用同一套 `ChapterFlagListScreen`）。

**接手时不要重开的话题**（已有明确结论，避免重复讨论）：

- 失效标记自动清理 → 4.2 已拍板不做。
- `openManga` 预检磁盘目录 → 4.2 已拍板不做。
- 给 `MangaMarkStore` 加进入页面时的清理/扫描 → 违反「先显示旧数据」，不做。

---

## 5. 本次改动清单

| 文件 | 改动 | 目的 |
|---|---|---|
| `app/src/main/java/eu/kanade/presentation/more/settings/screen/advanced/ChapterFlagListScreen.kt` | 新增 `ChapterRepository` import | 读取篇目 |
| 同上 | `openChapterReader` 增加篇目存在 + `chapter.mangaId == mangaId` 归属校验 | 阻止跳转到错误篇目/漫画 |

工作区中其它 `git status` 中显示的改动（audio、reader、migration 等）均为用户既有的并行工作，**本次未触碰、未回退**。
