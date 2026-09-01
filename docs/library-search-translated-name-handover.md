# 本地库搜索·中文译名 修复交接文档

## 一句话结论

**书架顶部搜索框**和**浏览-本地源搜索**（含全局搜索里的本地源结果）现在都会同时匹配**章节的中文译名**（`Chapter.translatedName`，即「中文名/译名」列），从而能通过某个章节的中文名搜到它所属的漫画。

## 问题现象

- 用户：中文搜索「有时候不灵，搜不了译名」。
- 具体例子：某章节的中文译名含「有坂柳ntr」，但在书架搜索框里搜「有坂柳」搜不出来。
- 用户确认的两个前提：
  1. 搜索位置 = **本地库搜索栏（书架顶部搜索）**。
  2. 「中文译名」= **章节级的中文名/译名**（手动编辑或 CSV 导入的那一列），不是漫画标题的中文翻译。

## 根因

本地库搜索的匹配逻辑只查漫画字段（标题、作者、画师、简介、分类、来源、备注），
**从不查章节的 `translated_name`**。译名数据存在 `chapters` 表里，和搜索链路完全脱节，所以搜不到。

相关链路：

- `LibraryViewModel.kt`：搜索时 `queryNode.matches(item)` 过滤 `LibraryItem`（只含 `LibraryManga` + 计数，没有任何章节数据）。
- `QueryNodeExtensions.kt`：`GeneralQueryNode.matches` 只遍历 `MangaField`（title/author/artist/description/genre/source/notes）。

## 改动内容

### 1. 新增 SQL 查询（`data/.../chapters.sq`）

```
getTranslatedNamesByMangaIds:
SELECT manga_id, translated_name
FROM chapters
WHERE manga_id IN :mangaIds
AND translated_name IS NOT NULL
AND TRIM(translated_name) != '';
```

只取非空译名的稀疏结果集，不是全表扫。

### 2. domain 层新增模型 + 仓库接口

- 新增 `domain/.../chapter/model/ChapterTranslatedName.kt`（`mangaId` + `name`）。
- `ChapterRepository.kt` 新增两个方法：
  - `suspend fun getTranslatedNames(mangaIds): List<ChapterTranslatedName>`
  - `fun observeTranslatedNames(mangaIds): Flow<List<ChapterTranslatedName>>`

### 3. data 层实现（`ChapterRepositoryImpl.kt`）

两个方法对应实现；`observeTranslatedNames` 用 SQLDelight 的 `subscribeToList()`，
**chapters 表一旦变更（例如改译名、CSV 导入）就会自动重新发出**，无需重启，返回书架即可搜到新译名。

### 4. 新增 interactor + DI

- 新增 `GetChapterTranslatedNames.kt`：`observe(mangaIds): Flow<Map<Long, List<String>>>`（mangaId → 去重后的译名列表）。
- `DomainModule.kt` 注册 `addFactory { GetChapterTranslatedNames(get()) }`。

### 5. 搜索匹配接入（`QueryNodeExtensions.kt`）

- `QueryNode.matches(item, chapterTranslatedNames: Map<Long, List<String>> = emptyMap())` 增加可选参数（默认空，保持向后兼容）。
- `GeneralQueryNode.matches` 在原有漫画字段匹配之外，追加：
  `chapterTranslatedNames[manga.id].orEmpty().any { it.containsSearch(value) }`。
- 复用已有的 `containsSearch`（简繁、全半角、假名、新旧字形、拼音均已覆盖）。
- 字段限定查询（`title:`/`author:` 等）**不**匹配译名，语义不变。

### 6. ViewModel 接入（`LibraryViewModel.kt`）

- 注入 `GetChapterTranslatedNames`。
- 新增 `getChapterTranslatedNamesFlow()`：从 `getLibraryManga.subscribe()` 取漫画 id 集合，
  `distinctUntilChanged()` 后 `flatMapLatest` 到译名 Flow。
- 主搜索 `combine` 里并入该 Flow；注意 Kotlin `combine` 最多 5 个流，
  因此用 `combine(getFavoritesFlow(), getChapterTranslatedNamesFlow(), ::Pair)` 把两个流并成一个，避免超限。

### 7. 浏览-本地源搜索接入（`LocalSource.kt`）

原先本地源搜索只做目录名/文件名的 `contains(ignoreCase = true)`。之前判断「`source-local` 拿不到 ChapterRepository」**是错的**：
`source-local/build.gradle.kts` 本来就依赖 `projects.domain`，`ChapterRepository` 接口可直接注入，无需新增模块依赖或回调接口。

- 新增惰性注入 `private val chapterRepository: ChapterRepository by injectLazy()`。惰性是为了让 `LocalSource` 的构造可以早于数据库模块注册。
- 新增 `getTranslatedNamesIndex()`：`getTranslatedNamesBySourceId(LocalSource.ID)`，返回 `Map<mangaUrl, List<译名>>`。
  数据库在这里只是增强而非事实来源：查询失败时 `catch` 后退回空映射，本地源搜索降级为原来的文件名匹配，不会抛错。
- 匹配逻辑抽成顶层纯函数 `localSearchMatch(query, title, chapterNames, translatedNames): LocalSearchMatch?`
  （与文件里既有的 `shouldReuseListingAfterUnexpectedEmptyScan` 等纯函数风格一致，便于单测）：
  依次匹配 **章节文件名 → 章节译名 → 漫画标题**，任一命中即保留该漫画；返回值的 `matchedChapter`
  是 UI 要展示的「命中章节」，**文件名优先**（文件名是权威数据），只有译名命中时才展示译名。
- **行为变更**：本地源搜索的标题/文件名匹配从 `contains(ignoreCase = true)` 升级为
  `SearchTextNormalizer.containsSearch`（简繁、全半角、假名、新旧字形、拼音），与书架搜索对齐。
  否则会出现「译名搜得到、标题搜不到」的割裂。`containsSearch` 对纯 ASCII 有快速路径，英文/数字查询无额外开销。

**url 一致性（关键点，编译通过不代表这点成立）**：数据库侧用 `mangas.url` 关联，本地源侧用 listing 的
`entry.url`，两者同源——都是本地根目录下的**单层目录名**。佐证：`LocalSource.kt` 里
`chapter.url.split('/', limit = 2)` 的第一段就是 manga url。所以 JOIN 结果能被本地源正确消费。

**覆盖范围**：`getSearchManga` 与 `getSearchMangaUrls` 共用同一层逻辑，因此以下入口全部生效：
浏览-本地源搜索框、全局搜索里的本地源结果、详情页标题长按的「在本地中搜索」。
`getPopularManga` / `getLatestUpdates` 的 `query` 为空，走不到译名分支，也不查数据库。

**译名同步**：本地源搜索是单次查询（非 Flow），无需像书架那样订阅变更——
每次新 query 重新查一次稀疏结果集即可，改完译名回到本地源搜索立刻生效。
配套的 `cachedDerivedListing` memo 以 query 为 key，翻页不会重复查库。

## 验证情况

- ✅ `:data:compileDebugKotlin`、`:source-local:compileDebugKotlin` 编译通过。
- ✅ 新增 `LocalSourceSearchMatchTest`（`source-local/src/test/kotlin/tachiyomi/source/local/LocalSourceSearchMatchTest.kt`）7 个用例全部通过：
  1. 标题命中保留结果且不占用「命中章节」
  2. 章节文件名命中并被记为命中章节
  3. 译名命中（`有坂柳` 命中译名 `有坂柳ntr`）
  4. 译名简繁命中（`进击` 命中译名 `進擊的巨人`）
  5. 文件名优先于译名（同一 query 两者都命中时展示文件名）
  6. 无关译名不误命中
  7. 无译名索引时退化为原行为
- ✅ `source-local` 既有 20 个用例无回归（`LocalPagingTest`、`LocalSourceChapterChangesTest`、
  `LocalListingRecoveryTest`、`LocalSourceFileSystemTest`、`LocalChapterCoverManagerTest`）。
- ✅ 既有 `QueryNodeExtensionsTest`（`app/src/test/java/mihon/feature/library/QueryNodeExtensionsTest.kt`）5 个用例：
  标题命中不受影响 / 译名命中 / 译名简繁命中 / 无关译名不误命中 / 无译名索引时标题命中仍正常。
- ⚠️ `:app:compileDebugKotlin` **未能复验**：报错 `AppBar.kt:181 Unresolved reference 'UNDER_TITLE_MAX_WIDTH_FRACTION'`，
  该常量全仓库只有使用处没有定义处，属用户并行编辑中的改动，与本次改动无关（本次未触碰 presentation 层）。

## 尚未验证 / 待办

1. **`:app:compileDebugKotlin` 复验**：等 `AppBar.kt` 的 `UNDER_TITLE_MAX_WIDTH_FRACTION` 定义补齐后重跑。
   本次改动的模块（data / source-local）已独立编译并通过测试，app 侧只是下游消费方（接口为纯新增），预计无影响。
2. **设备端实机验证**（AGENTS.md 要求）：在已连接设备上装包后，确认：
   - 书架：冷启动进入书架、切换筛选时不触发全库/文件系统扫描；给某章节填译名 → 返回书架能命中；CSV 导入译名后同样能命中。
   - 本地源：在本地源搜索框搜「有坂柳」能命中对应漫画，卡片上展示的命中章节正确（文件名优先、译名兜底）。
   - 全局搜索里的本地源结果同样能命中译名。
   - 3000+ 部漫画规模下，两个搜索框输入都保持即时、不卡顿。
3. **数据规模性能实测**：
   - 书架：`observeTranslatedNames` 随 chapters 表每次变更重新查询，大库高频写入（批量同步章节）时是否造成可感知开销。
   - 本地源：每次新 query 多一次稀疏 DB 查询（只取带译名的章节）。目前**未加缓存**——
     译名改动要立刻生效，且 `cachedDerivedListing` memo 已挡住翻页重复查询；若实测偏慢再考虑短 TTL 缓存。
4. **边界确认**：书架译名索引只在「收藏（favorite）漫画」范围内生效——书架搜索本来就只搜收藏列表，符合预期。
   本地源侧的覆盖范围是「有数据库记录的本地漫画」（含未收藏但点开过的），比书架更宽，符合本地源语义。

## 未做 / 明确不做的

- 未改动**漫画详情页章节列表**：该列表根本没有文本搜索框，只有未读/书签/下载三个布尔过滤，无从匹配译名。
- 未引入内置 AI 翻译（AGENTS.md 明确禁止）。
- 未提交/未推送（AGENTS.md：除非用户要求）。

## 关键文件清单

**书架搜索（第 1 批，已完成）**

- `data/src/main/sqldelight/tachiyomi/data/chapters.sq`
- `domain/src/main/java/tachiyomi/domain/chapter/model/ChapterTranslatedName.kt`（新增）
- `domain/src/main/java/tachiyomi/domain/chapter/repository/ChapterRepository.kt`
- `domain/src/main/java/tachiyomi/domain/chapter/interactor/GetChapterTranslatedNames.kt`（新增）
- `data/src/main/java/tachiyomi/data/chapter/ChapterRepositoryImpl.kt`
- `app/src/main/java/eu/kanade/domain/DomainModule.kt`
- `app/src/main/java/mihon/feature/library/QueryNodeExtensions.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryViewModel.kt`
- `app/src/test/java/mihon/feature/library/QueryNodeExtensionsTest.kt`（新增）

**本地源搜索（第 2 批，本次新增）**

- `data/src/main/sqldelight/tachiyomi/data/chapters.sq`（新增 `getTranslatedNamesBySourceId`，按 `mangas.url` 关联）
- `domain/src/main/java/tachiyomi/domain/chapter/repository/ChapterRepository.kt`（新增 `getTranslatedNamesBySourceId`）
- `data/src/main/java/tachiyomi/data/chapter/ChapterRepositoryImpl.kt`（对应实现）
- `source-local/src/main/kotlin/tachiyomi/source/local/LocalSource.kt`
  （注入 `ChapterRepository`、`getTranslatedNamesIndex()`、`localSearchMatch()` 纯函数）
- `source-local/src/test/kotlin/tachiyomi/source/local/LocalSourceSearchMatchTest.kt`（新增）
