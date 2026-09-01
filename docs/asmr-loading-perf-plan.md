# ASMR（Kikoeru）模块加载性能优化方案

> 创建：2026-08-30
> 状态：**步骤 0~5 已实施并编译通过；真机验证待做**
> 目标包：`app.mihon.dev`　目标设备：Redmi K50（MIUI / arm64-v8a）
> 背景：用户反馈「切换 tab 慢」「打开分类看标签要加载半天」，核心怀疑是每次都重新拉取。
>
> 2026-08-30 进度：
> - 步骤 0 完成。所有 Kikoeru 请求打 `kikoeru <耗时>ms <url>` 日志，用于建立基线。
> - 步骤 1 完成。Kikoeru client 挂 20 MiB 独立磁盘缓存 + `AudioCacheInterceptor` 补缓存头。
> - 步骤 2 完成。分类三字典落盘 + 并发 + SWR，详情页不再全屏 loading。
> - 步骤 3、4、5 完成。Browse 结果缓存、下一页预取、分页监听与过滤的重组开销。
> - `:app:compileDebugKotlin` 通过（warning 仅来自 `NotificationReceiver.kt`，与本次无关）。
> - **未做**：步骤 6（按 tab 懒加载）——步骤 2 的并发已覆盖其主要收益，评估后判定性价比不足。
> - **交互语义已定稿**：见第 4.4 节（30 分钟节流——命中且未过期不联网，过期才后台刷新）+ 第 4.5 节（播放器竖屏锁定）。

---

## 1. 结论先行

**怀疑成立：整个 ASMR 模块目前是零缓存。**

- HTTP 层：Kikoeru 专用 `OkHttpClient` 没有挂 `Cache`，磁盘缓存为 0。
- 进程层：只有 `AudioBrowseViewModel` 里一个 `initialized` 标志，能防「返回页面重拉」，防不了任何主动切换。
- 持久层：除收藏/历史/播放列表外，没有任何 API 数据落盘。

所以：**每次进分类页 = 3 个串行全量请求；每次切 tab / 改排序 = 1 个新请求；每个作品详情页 = 1 个新请求。**

好消息是：所有模型都是 `@Serializable`、所有列表接口都是幂等 GET，加缓存的改造成本很低，且**不需要引入 SQLDelight 新表**。

---

## 2. 现状：链路与热点

### 2.1 调用链

```
AudioBrowseScreen ──► AudioBrowseViewModel.refresh()/loadPage()
                        ├── api.fetchWorks()      GET  /api/works
                        ├── api.fetchPopular()    POST /api/recommender/popular
                        ├── api.fetchRecommended()POST /api/recommender/recommend-for-user
                        └── api.search()          GET  /api/search/{kw}   [FORCE_NETWORK]
                              │
AudioCategoryScreen ─► AudioCategoryViewModel.load()
                        ├── api.fetchCircles()    GET  /api/circles/
                        ├── api.fetchVas()        GET  /api/vas/
                        └── api.fetchTags()       GET  /api/tags/
                              │
AudioDetailScreen ──► AudioDetailViewModel.load(work)
                        └── api.fetchTracks()     GET  /api/tracks/{id}
                              │
                        KikoeruApi.executeWithRetry()  ← 3 次，退避 400/800ms
                              │
                        OkHttpClient（AppModule:136-146）← 无 .cache()
```

### 2.2 问题清单

| # | 位置 | 问题 | 严重度 |
|---|---|---|---|
| P1 | `AppModule.kt:136-146` | Kikoeru client 无 `.cache()`。对照 `NetworkHelper.kt:27-32` 主 client 是有 5 MiB 缓存的，说明这是遗漏而非有意设计 | 高 |
| P2 | `AudioCategoryScreen.kt:80-103` | `init { load() }` 每次进页面都拉；三个 `suspend` **串行** await，延迟叠加 3× | 高 |
| P3 | `AudioCategoryScreen.kt:92-94` | **一次拉全量**：`/api/tags/` 在 asmr-200 上是两万条级 JSON，加 `/api/vas/`、`/api/circles/`，解析 + 排序都在关键路径上 | 高 |
| P4 | `AudioCategoryContent.kt:123-124` | 加载期间全屏 `LoadingScreen`，无旧数据兜底，体感「半天」 | 高 |
| P5 | `AudioBrowseScreen.kt:223-227` / `260-269` | `setTab()` / `setSort()` 都直接 `refresh()`，没有 per-tab / per-sort 结果缓存，来回切反复打网络 | 高 |
| P6 | `AudioBrowseScreen.kt:271-287` | `refresh()` 只置 `refreshing` 不清空 `works`，**切 tab 后列表继续显示上一个 tab 的数据**直到新数据回来。这是正确性问题，不只是慢 | 中 |
| P7 | `AudioCategoryContent.kt:177-179` | `items.filter {}` 没有 `remember`，每次重组（搜索框每敲一个字）都扫两万条 | 中 |
| P8 | `KikoeruApi.kt:192-212` | `executeWithRetry` 固定 3 次、退避 400/800ms。后端在 Cloudflare 后偶发 503，分类页三个串行请求最坏能卡好几秒才报错 | 中 |
| P9 | `AudioDetailScreen.kt:162` | `fetchTracks` 无缓存。从搜索/分类二次进入同一作品会重拉（返回时 Voyager 保留实例，不会重拉） | 中 |
| P10 | `AudioBrowseContent.kt:270-277` | `LaunchedEffect(gridState, state.works.size)` 依赖 `works.size`，每加载一页就重启一次 flow 收集 | 低 |
| P11 | `AudioBrowseScreen.kt:293` | `works.size >= totalCount` 判断终止，但 `works` 经过 `distinctBy` 去重，后端若返回重复项会导致提前停止分页 | 低 |

### 2.3 为什么「打开分类看标签」特别慢

四条叠加：

1. 用户只想看标签，却必须等 `circles` + `vas` 也拉完（P3）。
2. 三个请求是串行的，耗时 = 三者之和而非最大值（P2）。
3. 没有任何缓存，返回再进照样重来（P1）。
4. 期间是全屏 loading，没有旧列表可以垫（P4）。

---

## 3. 目标与非目标

### 目标

- 分类页：**第二次及以后进入 0 等待**；首次进入也只等 1 个请求而非 3 个串行。
- Browse 页：**切 tab / 改排序命中缓存时瞬时返回**，并修掉 P6 的内容错配。
- 翻页与二次进详情：感觉不到网络。
- 断网时，看过的内容仍可展示。

### 非目标（本次不做）

- **不做**离线浏览（不引入 SQLDelight 新表，分类数据用文件即可）。
- **不做**后台定时预同步 / WorkManager 预热。
- **不改**后端，不改 API 契约。
- **不做**列表虚拟化之外的渲染层重构。
- **不做**分页框架迁移（不引入 Paging3，现有手写分页 + `loadGeneration` 够用）。

---

## 4. 核心设计：三层缓存

| 层 | 载体 | 覆盖 | 生命周期 | 解决 |
|---|---|---|---|---|
| L1 HTTP 磁盘缓存 | OkHttp `Cache` + 改写响应头的 network interceptor | `fetchWorks` / `fetchTracks` / `search` 的 GET | 进程无关，按 `max-age=300s` | P1、P9 |
| L2 分类落盘缓存 | 新增 `AudioCategoryCache` 单例 + JSON 文件 | circles / vas / tags | 跨进程启动，TTL 7 天 | P2、P3、P4 |
| L3 会话内结果缓存 | `AudioBrowseViewModel` 内的 `Map` | 各 tab / sort / query 的首页与预取页 | 跟随进程 | P5、P6 |

分工原则：

- **L1 管「易变但可短时复用」的数据**（作品分页、曲目树）。
- **L2 管「大且几乎不变」的数据**（分类字典）。分类数据一旦落盘自管，就**不需要**再走 L1——`AudioCacheInterceptor` 显式排除了这三个路径，避免两份缓存打架。
- **L3 管「POST 接口」**——`fetchPopular` / `fetchRecommended` 是 POST，OkHttp 默认不缓存，只能靠内存缓存兜住切 tab。

### 4.1 关键前提：必须改写响应头，否则 L1 不会生效

asmr-200 的 API 大概率不返回 `Cache-Control` / `Expires`。按 OkHttp 规则，**无缓存头的响应不会被缓存**；而且带 `Authorization` 的请求，只有响应显式声明 `public` 才允许缓存。

所以光加 `.cache()` 大概率是无效的，必须配一个 network interceptor 强制补头：

```kotlin
// 只对 api.asmr-200.com 生效，避免污染其它 client 行为
.addNetworkInterceptor { chain ->
    val response = chain.proceed(chain.request())
    if (!chain.request().url.host.equals("api.asmr-200.com", ignoreCase = true)) return@addNetworkInterceptor response
    response.newBuilder()
        .header("Cache-Control", "public, max-age=$AUDIO_MAX_AGE_SECONDS")
        .removeHeader("Pragma")
        .build()
}
```

已实现为 `AudioCacheInterceptor`，并额外排除 `/api/tags/`、`/api/vas/`、`/api/circles/`——这三路由 L2 独占。

> ⚠️ 这是步骤 1 的成败关键，务必先手动验证一次响应头再往下做（见第 8 节）。

### 4.2 缓存键

```kotlin
"$account|${tab.name}|${sort.name}|$keyword"
```

账号必须参与：`fetchRecommended` 是个性化的，登录前后内容不同。未登录时的推荐还依赖 `personalCircleKeyword()`（本地收藏/历史派生的口味关键词），所以该关键词也要进键——否则用户刚收藏某个 circle 后，推荐页会继续用没有口味信号时缓存的那份。

### 4.3 TTL 一览

| 数据 | TTL | 位置 |
|---|---|---|
| 分类字典 | 7 天 | `AudioCategoryCache.MAX_AGE` |
| 作品页 / 曲目树 | 300 s | `AudioCacheInterceptor.MAX_AGE_SECONDS` |
| 浏览结果首页（L3） | 30 分钟 | `AudioBrowseScreen.PAGE_CACHE_TTL` |
| 预取的后续页 | 30 分钟 | 同上（`PAGE_CACHE_TTL`） |

> 说明：初版把「预取页」定为 5 分钟、把「首页」做成"无条件后台刷新"；**2026-08-30 终稿**统一为 30 分钟节流——列表不是高频更新内容，30 分钟内重复进入直接命中内存缓存、不发请求，既省流量也不影响观感。

### 4.4 交互语义（2026-08-30 终稿，30 分钟节流 + 命中即不联网）

**原则：stale 永远优于 empty。** 缓存负责让内容**立刻**出现；但首页结果在 **30 分钟 TTL 内**命中则**不**发请求（节流），超过 TTL 才后台重新校验。

| 用户动作 | 界面 | 网络 |
|---|---|---|
| 切 tab / 改排序 / 搜索，**命中缓存且未过期（<30min）** | 缓存内容立即渲染 + 顶部刷新指示 | **不发请求**（`if (!stale) return@launchIO`） |
| 切 tab / 改排序 / 搜索，**命中缓存但已过期** | 缓存内容立即渲染 + 顶部刷新指示 | **后台重新校验**，完成后替换 |
| 切 tab / 改排序 / 搜索，**未命中** | 清空 → 转圈 | 联网 |
| 下拉刷新 | 保留内容 + 明显转圈 | 强制联网（`displayedFromCache = null`，豁免节流） |
| 上拉到底，预取页仍新鲜（<30min） | 直接追加 | 不联网 |
| 上拉到底，无缓存或已过期 | 底部进度条 | 联网 |

**为什么改成 30 分钟节流而非"总是重新校验"**：同一屏 30 张封面是几 MB，列表 JSON 仅几百 KB，纯流量差异不大；但 ASMR 列表更新频率低，30 分钟内重复切 tab 没必要每次都打后端。节流与"列表看起来是死的"无关——过期后下一次进入仍会后台刷新。

**后台刷新不得打断阅读**：若刷新返回时用户已经滚动进列表（`loadMore()` 追加过，`works` 已换成新实例），则**不替换 UI**，只更新缓存，等下次进入生效。判断方式是引用比较 `current.works !== displayedFromCache`，无需 UI 层配合。下拉刷新是用户主动行为，豁免此限制。

### 4.5 播放器竖屏锁定（2026-08-30 新增）

**决策：手机锁竖屏，平板不锁（保留横屏）。**

- 手机横屏时播放界面内容（封面/进度/控制）宽度仅约 300dp，而竖向布局需要约 440dp，**物理放不下**，横过来只会更挤。
- 平板（`smallestScreenWidthDp >= 600`）宽度足够，保留横屏能力。
- 实现：
  - `AudioPlayerScreen.kt` 用 `DisposableEffect` + `LocalConfiguration`：手机端设 `ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT`（尊重系统旋转锁），退出时还原。
  - `AndroidManifest.xml` 的 `MainActivity` 补 `android:configChanges`，包含 `orientation|screenSize|smallestScreenSize` 等，避免旋转/配置变化导致 Activity 重建（防止闪一下、播放状态丢失）。
- 参照物：主流音乐 App（如网易云）的播放页同样不分横屏。

---

## 5. 实施步骤

### 步骤 0 — 建立基线（30 min，先做）

不测量就无法证明优化有效。在 `KikoeruApi.executeWithRetry` 外层加耗时日志：

```kotlin
val start = SystemClock.elapsedRealtime()
// ...
logcat { "kikoeru ${SystemClock.elapsedRealtime() - start}ms ${request.url.encodedPath}" }
```

记录 4 个数：分类页首次进入、分类页二次进入、Browse 切 tab、详情页进入。**优化后复测同一组数**。

### 步骤 1 — 打开 HTTP 磁盘缓存（P1，~15 行）

文件：`app/src/main/java/eu/kanade/tachiyomi/di/AppModule.kt:136-146`

```kotlin
addSingletonFactory {
    val networkHelper = get<NetworkHelper>()
    OkHttpClient.Builder()
        .cookieJar(networkHelper.cookieJar)
        .connectTimeout(15.seconds)
        .readTimeout(30.seconds)
        .callTimeout(60.seconds)
        .cache(Cache(File(app.cacheDir, "audio_network_cache"), 20L * 1024 * 1024))
        .addNetworkInterceptor(AudioCacheInterceptor)   // 见 4.1
        .addInterceptor(UncaughtExceptionInterceptor())
        .addInterceptor(UserAgentInterceptor(networkHelper::defaultUserAgentProvider))
        .build()
}
```

- 20 MiB：tags 全量 JSON 约 1–3 MB，留足余量给作品分页。
- 独立目录，不与主 network cache 混用，方便单独统计/清理。
- 现有代码里的 `CacheControl.FORCE_NETWORK`（`search` / `fetchAccountWorks` / `fetchSubtitle`）保持不变——这些确实要最新，别顺手去掉。

**预期**：翻回看过的页、二次进详情页 → 秒开。

### 步骤 2 — 分类数据落盘（P2/P3/P4，核心，新增 1 个类）

新增 `app/src/main/java/eu/kanade/tachiyomi/data/audio/AudioCategoryCache.kt`：

```kotlin
class AudioCategoryCache(
    private val context: Context,
    private val json: Json,
) {
    @Serializable
    private data class Snapshot(
        val savedAt: Long = 0,
        val circles: List<CircleItem> = emptyList(),
        val vas: List<VaItem> = emptyList(),
        val tags: List<TagItem> = emptyList(),
    )

    @Synchronized fun read(): Snapshot?
    @Synchronized fun write(snapshot: Snapshot)
    @Synchronized fun clear()
}
```

要点：

- 存 `context.filesDir/audio/categories.json`（不是 cacheDir，避免被系统清理）。
- `CircleItem` / `VaItem` / `TagItem` 已全部是 `@Serializable`，直接复用 Injekt 的 `Json`（`AppModule:106-111`），零额外适配。
- **写盘用「临时文件 + rename」保证原子性**，避免写一半被杀导致缓存损坏；读取失败（`runCatching`）一律当作无缓存，回退网络。
- TTL `7.days`；过期仍**先返回旧数据**，只是触发后台刷新（`max-stale` 语义），不阻塞 UI。
- 注册进 `AppModule`：`addSingletonFactory { AudioCategoryCache(get(), get()) }`。

改 `AudioCategoryViewModel`：

```kotlin
fun load(force: Boolean = false) {
    viewModelScope.launchIO {
        cache.read()?.let { emit(it, fromCache = true) }          // 1) 立即渲染
        if (!force && cache.read()?.isFresh() == true) return@launchIO
        _state.update { it.copy(refreshing = true, error = false) }
        try {
            coroutineScope {                                       // 2) 三个并发
                val circles = async { api.fetchCircles() }
                val vas     = async { api.fetchVas() }
                val tags    = async { api.fetchTags() }
                val fresh = Snapshot(now, circles.await(), vas.await(), tags.await())
                cache.write(fresh)
                emit(fresh, fromCache = false)
            }
        } catch (e: Exception) {
            _state.update { it.copy(refreshing = false, error = cache.read() == null) }
        }
    }
}
```

状态机从 `loading: Boolean` 拆成 `hasData` + `refreshing`：

```kotlin
data class AudioCategoryState(
    val refreshing: Boolean = false,   // 顶部细进度条
    val error: Boolean = false,        // 仅在无缓存时才全屏报错
    val loaded: Boolean = false,       // 是否已有可渲染数据
    val circles: List<CircleItem> = emptyList(),
    val vas: List<VaItem> = emptyList(),
    val tags: List<TagItem> = emptyList(),
)
```

`AudioCategoryContent` 对应改：`loaded` 为 true 时直接渲染列表 + 顶栏细进度条，不再全屏 `LoadingScreen`。

**预期**：首次进入只等一个请求的时间（三者并发取最大值），之后 0 等待。

### 步骤 3 — Browse 结果缓存 + 修内容错配（P5/P6）

在 `AudioBrowseViewModel` 加：

```kotlin
private data class Snapshot(
    val works: List<Work>,
    val totalCount: Int,
    val savedAt: Long,
)
private val pageCache = ConcurrentHashMap<String, Snapshot>()   // key: 见 4.2
private val TTL = 5.minutes
```

- `setTab()` / `setSort()` / `search()` / `exitSearch()` 统一走 `switchTo()`：命中缓存 → **立即渲染 + 无条件后台重新校验**；未命中 → **先清空 `works`**（修 P6）再请求。语义见 4.4。
- `refresh()`（下拉刷新）→ 强制打网络并写回缓存，且豁免"不得打断阅读"限制。
- `login()` / `logout()` 时清空整个 `pageCache`（推荐结果是账号相关的，见 `AudioBrowseScreen.kt:255-258` 的注释）。
- 缓存上限 24 条，超出时按 `savedAt` 淘汰最旧的。

顺带修 **P10**：`AudioBrowseContent.kt:270` 去掉 `state.works.size` 依赖，改成

```kotlin
LaunchedEffect(gridState) {
    snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
        .distinctUntilChanged()
        .filter { it != null && it >= state.works.size - LOAD_MORE_THRESHOLD }
        .collect { onLoadMore() }
}
```

**预期**：tab 来回切、排序来回改 → 首屏瞬时（缓存立即渲染），后台各发一个请求。

### 步骤 4 — 首屏预取 + 详情页曲目（P9）

- 步骤 3 落地后，在第 1 页返回时顺手预取第 2 页写入 `pageCache`（key 带 page），`loadMore()` 命中即瞬时。预取页追加前需过 `isFresh`，避免用半小时前预取的旧页。
- 详情页 `fetchTracks` 已被步骤 1 的 L1 覆盖（`GET`、无 `FORCE_NETWORK`），**本步可只验证不写代码**。若实测未命中（后端返回不可缓存头已被 interceptor 覆盖，理论上会命中），再在 `AudioDetailViewModel` 加一个 `workId → List<TrackNode>` 的内存 LRU。

### 步骤 5 — 顺手清理（P7 / P8 / P11）

- `AudioCategoryContent.kt:177`：`val filtered = remember(items, query) { ... }`。
- `AudioCategoryContent` 的 `LazyColumn` 保留 `key`，加 `Modifier.animateItemPlacement()` 可选。
- `KikoeruApi`：分类这类非关键请求，重试降到 2 次、退避 300ms（`MAX_ATTEMPTS` 做成参数，作品/曲目保持 3 次）。
- `AudioBrowseScreen.kt:293` 的终止条件改为基于「本次请求返回条数」而非去重后的 `works.size`，避免 P11。

### 步骤 6 — 分类页按 tab 懒加载（可选，依赖步骤 2）

`AudioCategoryViewModel` 改成按 `AudioCategoryType` 分别加载 + 分别缓存：点哪个 tab 拉哪个。落盘缓存存在的前提下，首次进分类页只等 1 个请求（且是用户当前想看的那一个）。

> 若步骤 2 的并发已让首次进入足够快，本步可不做——**成本收益比一般，建议放到最后评估**。

---

## 6. 改动文件清单

| 文件 | 步骤 | 改动 |
|---|---|---|
| `di/AppModule.kt` | 1、2 | 加 `.cache()` + interceptor；注册 `AudioCategoryCache` |
| `network/AudioCacheInterceptor.kt` | 1 | **新增**，改写响应头 |
| `data/audio/AudioCategoryCache.kt` | 2 | **新增**，分类数据落盘 |
| `ui/audio/AudioCategoryScreen.kt` | 2、5 | 并发 + SWR + 状态机 |
| `presentation/audio/AudioCategoryContent.kt` | 2、5 | 非阻塞渲染 + `remember` 过滤 |
| `ui/audio/AudioBrowseScreen.kt` | 3、4、5 | 结果缓存、预取、分页终止条件 |
| `presentation/audio/AudioBrowseContent.kt` | 3 | 修 P6/P10 |
| `data/audio/KikoeruApi.kt` | 0、5 | 耗时日志；分类请求重试降级 |

---

## 7. 风险与边界

| 风险 | 说明 | 处置 |
|---|---|---|
| **加了 cache 但不生效** | 后端无缓存头 / 带 `Authorization` 的响应默认不可缓存 | 拦截器强制 `public, max-age=...`；**步骤 1 必须先验证**再往下 |
| **登录态数据串号** | 担心 A 用户的推荐结果被 B 看到 | OkHttp 缓存键包含请求头，无 `Authorization` 的请求不会命中带 `Authorization` 的条目；且 `login`/`logout` 时清空 L3 |
| **缓存文件损坏** | 写盘被杀 | 临时文件 + rename；读取全部 `runCatching` 兜底 |
| **磁盘占用** | tags 全量 JSON | 20 MiB 上限 + 独立目录，可在「设置 → 存储」清缓存时一并清理 |
| **数据陈旧** | 缓存了旧标签 / 旧列表 | 分类 TTL 7 天且 SWR（后台刷新后自动更新）；Browse 首页/预取页 TTL 30 分钟（过期后台刷新）；下拉刷新强制走网络 |
| **L1 与 L2 双份** | 分类数据既进 OkHttp 又进 JSON 文件 | 分类三接口走 L2 即可；L1 的 interceptor 可以**排除**这三个路径，避免重复存储 |
| **与并行任务的编译干扰** | 用户可能同时在该仓库跑其它 Gradle 任务 | 验证时只编译 `:app` 的 `compileDevKotlin`，按既有约定不要删 `build/` |

---

## 8. 验证方法

### 8.1 步骤 1 的前置验证（必须先过）

装包后开 verbose logging（或直接 `adb shell` 抓），确认：

- 首次请求 `/api/tags/` 后，`/data/data/app.mihon.dev/cache/audio_network_cache/` 下出现 journal 文件且体积增长；
- 二次进分类页时，日志里该请求的耗时降到 <50ms 且无网络活动。

若目录为空 → 响应头没被改写成功，先修 interceptor。

### 8.2 端到端用例

| 用例 | 操作 | 期望 |
|---|---|---|
| A | 冷启动 → 进分类页（首次） | 列表出现时间 ≈ 单个请求耗时（原为 3 个之和）；无全屏 loading 卡死感 |
| B | 返回 → 再进分类页 | **瞬时**，顶栏细进度条一闪或不可见 |
| C | 分类页搜索框逐字输入 | 无卡顿（P7 已修） |
| D | Browse 切 tab：推荐→热门→最新→推荐（30 分钟内重复切） | 每次切换**立即**出内容；切换瞬间**不显示上一个 tab 的数据**（P6 已修）；30 分钟内命中不联网，超过 TTL 才后台刷新并静默替换 |
| E | 改排序 | 同上：立即出内容；30 分钟内命中不联网，过期后台刷新；下拉刷新强制走网络 |
| F | 列表上拉 | 第 2 页无感（步骤 4 预取）；停留超过 30 分钟再上拉则重新请求 |
| G | 进详情 → 返回 → 再进同一作品 | 秒开 |
| H | 开飞行模式 → 进分类页 / 看过的列表 | 内容仍可展示 |
| I | 登录后 → 切各 tab | 推荐结果刷新为账号相关；无串号 |
| J | 复测步骤 0 的 4 个基线数 | 全部显著下降 |
| K | 切 tab 后立刻向下滚动，等待后台刷新返回 | 列表**不跳回顶部**，滚动位置保持；新内容下次进入才生效 |
| L | 顶部时等待后台刷新返回 | 列表静默替换，无跳动 |

### 8.3 编译验证

```powershell
Set-Location -LiteralPath 'd:\DATA\mihon'; .\gradlew :app:compileDebugKotlin --console=plain
```

> 仓库**没有 `dev` 变体**，可用的是 `compileDebugKotlin`（`compileDevKotlin` 会报 task not found）。

按既有约定：若报 `Unresolved reference` 集中在 `domain` / `core-metadata` / `i18n` 等**未改动**模块，多为并发 Gradle 造成的缓存污染，**不是本次改动的回归**，不要删 `build/`。

---

## 9. 待确认

1. ~~**后端响应头**：asmr-200 的 `/api/tags/` 等是否已带 `Cache-Control`？~~ → 拦截器无条件补头，无需依赖后端行为。真机验证时确认目录是否有内容即可。
2. ~~**分类页是否接受「先旧后新」**~~ → **2026-08-30 决策：接受，且进一步要求无条件重新校验。** 理由见 4.4：缓存只负责让内容立刻出现，不负责决定要不要更新。
3. ~~**步骤 6 是否要做**（按 tab 懒加载）~~ → **不做**。步骤 2 的并发已把首次进入从「三者之和」降到「三者最大值」，边际收益不足以支撑按 tab 分裂状态。
4. **缓存上限与 TTL**：20 MiB / 7 天 / 30 分钟 是目前取值（30 分钟为 2026-08-30 终稿，原 5 分钟 + "首页无条件刷新"已废弃），真机一轮后可按实测调整。
5. **播放器竖屏锁定**：仅手机端（`smallestScreenWidthDp < 600`）锁竖屏；平板保留横屏。`AndroidManifest` 已加 `configChanges` 防重建。真机验证需确认旋转/配置变化不闪、不丢播放状态；全局其它界面（`configChanges` 改动是 MainActivity 级）也需回归一次。

---

## 10. 建议执行顺序

```
步骤 0（基线）→ 步骤 1（HTTP 缓存，性价比最高）→ 步骤 2（分类落盘，直击主诉）
→ 步骤 3 + 5（Browse 缓存与清理）→ 步骤 4（预取）→ 步骤 6（不做）
```

步骤 0~5 已全部实施完毕并通过编译。**当前卡在真机验证**：`AudioCacheInterceptor` 是否真的让响应可缓存，只有装包看 `cache/audio_network_cache/` 才能确认（见 8.1）。这一步没过之前，L1 的收益无法计入。
