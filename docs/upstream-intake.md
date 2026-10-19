# 上游吸收甄别表

> 由 `scripts/upstream_intake.py` 生成，不要手改。改了判定请改脚本里的 `VERDICTS` / `BATCHES` 再重跑。

## 怎么重跑

```
git remote set-branches upstream main
git fetch upstream main
python scripts/upstream_intake.py
```

上游前进之后重跑一次，表就刷新到最新；`VERDICTS` 里的判定按提交哈希保存，不会因为新提交而错位。

## 基线

| 项 | 值 |
| --- | --- |
| 分叉点 | `2506b0496` |
| 上游 | `deeb9b258` |
| 本仓库参照 | `df994a625` |
| 上游自分叉点之后的提交 | 285 |
| 上游改动文件 | 971 |
| 本仓库改动文件 | 590 |
| 双方都改过 | 221 |

## 分类

| 类型 | 条数 |
| --- | --- |
| 功能/修复 | 193 |
| 依赖升级 | 83 |
| 翻译 | 6 |
| 发版 | 3 |

「依赖升级」包含 `Bump …` 开头的提交，其中有几条其实是编译目标（`Bump compileSdk to 37.x`），
已经按批次归到 P3b，不看这一列的分类。

## 怎么读这张表

- **移植形态**：由「上游这条提交改动之前的文件版本」与本仓库当前版本的比对得出。
  - `直搬` —— 所有触及文件都一致，或本来就是上游新增文件。
  - `对 hunk` —— 有文件已经分叉，要逐块对。
  - `重写` —— 有你这边根本不存在的文件，只能照着实现一遍。
- **重叠**：这条提交触及的文件里，有多少是你也改过的。它只是冲突的弱信号，
  真正决定成本的是「移植形态」。两者常常不一致：一条提交可能重叠 0 但形态是「重写」。
- **批次**：已归入路线图的提交。空白表示还没分配。

`gradle/libs.versions.toml` 和 `CHANGELOG.md` 这两个文件你改过，所以凡触及它们的提交都会显示「对 hunk」，
这不是真的冲突：依赖升级改的是版本号那一行，CHANGELOG 是你自己的段落。

## 已甄别结论

下面每一条都打开过上游 diff 和本仓库对应文件逐一对照过。没列在这里的提交还没甄别。

| 提交 | 上游做什么 | 你现在的状况 | 结论 |
| --- | --- | --- | --- |
| `be44a81fb` | 一个压缩包读不出时，其余章节照常列出 | `LocalSource.buildChapterEntry` 已有同样的 try/catch，注释写的理由也一致 | 已有等价实现，跳过 |
| `1e142c425` | ComicInfo 的年份按 2 位补零、月份被钳到 31 导致日期解析失败 | 你的日期逻辑整体重写过，没有 `padStart` / `coerceIn` 这两个调用 | 不适用；它暴露的问题由 P2 覆盖 |
| `a7179805a` | 读写 ComicInfo 的 Year / Month / Day，作为章节日期 | `ComicInfo.kt` 与你逐字节一致（无这三个字段）；`setChapterDetailsFromComicInfoFile` 只读 title / number / translator | 可移植，只做读侧 |
| `40f552981` | 空标题不再把所有书架条目匹配成「重复」 | `mangas.sq` 的 `getDuplicateLibraryManga` 没有空标题 guard，bug 在 | 可移植（4 行，列名要换成 `title` / `favorite`） |
| `88d4071cc` | 启动 rescan 不被磁盘上的下载索引覆盖 | `DownloadCache.kt` 用 `File` 而非 `AtomicFile`，初始化与 renew 之间没有顺序保证 | 可移植 |
| `38e93086c` | SQLite 连接池按 Room 的做法配置：1 写 4 读、WAL、每个连接 3 秒 busy_timeout | 只有 `isForeignKeyConstraintsEnabled = true`，单连接、默认 journal、无 busy_timeout | 可移植（驱动 API 已具备，位置在 `AppModule.kt`） |
| `61cd786e7` | WAL 在所有设备上开启，低内存机降到 1 个读连接 | 同上 | 可移植 |
| `abb126ad2` | 三星 Android 9–11 上 `reportFullyDrawn()` 抛 SecurityException 导致启动崩溃 | `MainActivity` 已分叉，但这 10 行插得进去 | 可移植，可搭车任一批 |
| `d3f10772f` | 全局搜索把本地源也算进来 | `getEnabledSources` 只按语言过滤，本地源被排除 | 可移植但不建议：你已有独立的本地 tab，会变成两个入口 |
| `f9c5fa901` | app scope 里未捕获的异常只记日志、不崩 App | 你没有 `AppBindings.kt` / `core:metro`，这段在 Metro 的绑定容器里 | 需重写：在 `AppModule.kt` 给 `CoroutineScope` 加 handler |
| `093841105` | 只从签名密钥匹配的仓库安装与更新扩展 | `ExtensionLoader` 上游已领先 173 行 | 需重写 |
| `06d612811` | 放开 R8 shrink，只保 Injekt 的 TypeReference 与 Signature 属性 | 现在全保 `eu.kanade.**` / `tachiyomi.**` / `mihon.**` | 需真机跑完整流程再合；`dev.mihon.injekt.**` 要额外 keep |

## 逐条

| # | 提交 | 日期 | 类型 | 主题 | 文件 | 重叠 | 移植形态 | 批次 | 判定 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | `cd1f42694` | 2026-08-12 | 功能/修复 | Fix wrong MangaBaka scores for step sizes > 1 (#3740) | 2 | 1 | 对 hunk |  |  |
| 2 | `437dd8722` | 2026-08-12 | 功能/修复 | [skip ci] Fix PR reference in Changelog (#3741) | 1 | 1 | 对 hunk |  |  |
| 3 | `a0a3adf86` | 2026-08-12 | 依赖升级 | Update dependency io.kotest:kotest-assertions-core to v6.2.4 (#3744) | 1 | 1 | 对 hunk |  |  |
| 4 | `db7f1663b` | 2026-08-12 | 依赖升级 | Update dependency org.conscrypt:conscrypt-android to v2.6.2 (#3745) | 1 | 1 | 对 hunk |  |  |
| 5 | `d1f9a1fda` | 2026-08-13 | 依赖升级 | Update dependency androidx.baselineprofile to v1.5.0-rc01 (#3749) | 1 | 1 | 对 hunk |  |  |
| 6 | `6197768a0` | 2026-08-13 | 依赖升级 | Update dependency androidx.paging:paging-runtime to v3.5.1 (#3750) | 1 | 1 | 对 hunk |  |  |
| 7 | `581a89b7b` | 2026-08-13 | 依赖升级 | Update dependency androidx.appcompat:appcompat to v1.8.0 (#3751) | 1 | 1 | 对 hunk |  |  |
| 8 | `5c3ae37d5` | 2026-08-14 | 功能/修复 | Add high quality WebGPU renderer (#3388) | 16 | 10 | 对 hunk | P9 |  |
| 9 | `ecbec8f50` | 2026-08-14 | 依赖升级 | Update dependency androidx.webkit:webkit to v1.17.0 (#3756) | 1 | 1 | 对 hunk |  |  |
| 10 | `77e88a215` | 2026-08-14 | 依赖升级 | Update dependency de.stefan-oltmann:kim to v0.35.0 (#3757) | 1 | 1 | 对 hunk |  |  |
| 11 | `b393088cf` | 2026-08-15 | 功能/修复 | Rename preview build type to nightly and label FOSS builds (#3760) | 6 | 4 | 对 hunk |  |  |
| 12 | `55675e7f8` | 2026-08-14 | 依赖升级 | Update dependency de.stefan-oltmann:kim to v0.36.0 (#3758) | 1 | 1 | 对 hunk |  |  |
| 13 | `b1a9a0592` | 2026-08-15 | 功能/修复 | Make UpdatesViewModel collect updates only while subscribed (#3761) | 2 | 2 | 对 hunk |  |  |
| 14 | `d00efa4bf` | 2026-08-15 | 功能/修复 | Make LibraryViewModel collect library only while subscribed (#3762) | 2 | 2 | 对 hunk |  |  |
| 15 | `b1efee47f` | 2026-08-15 | 功能/修复 | Replace StateViewModel with explicit backing fields (#3763) | 21 | 6 | 重写 |  |  |
| 16 | `ed30766a9` | 2026-08-15 | 功能/修复 | Match WebGPU reader background and transition colors to the old reader (#3765) | 3 | 2 | 重写 |  |  |
| 17 | `abdecb3cb` | 2026-08-15 | 功能/修复 | Show updates and upcoming filter icon as active for categories (#3772) | 3 | 3 | 对 hunk |  |  |
| 18 | `a81b7bce9` | 2026-08-15 | 功能/修复 | Add `id:` prefix search for rest of third-party trackers (#3776) | 14 | 1 | 对 hunk |  |  |
| 19 | `4d5b0ea90` | 2026-08-16 | 依赖升级 | Update dependency ca.mpreg:imagedecoder to v9 (#3777) | 1 | 1 | 对 hunk |  |  |
| 20 | `7e78849ed` | 2026-08-16 | 依赖升级 | Update dependency de.stefan-oltmann:kim to v0.37.0 (#3774) | 1 | 1 | 对 hunk |  |  |
| 21 | `00fb597ae` | 2026-08-16 | 功能/修复 | Drop legacy decoder (#3786) | 15 | 11 | 重写 |  |  |
| 22 | `5fa5f7e15` | 2026-08-16 | 功能/修复 | Load extensions off the main thread (#3788) | 3 | 2 | 对 hunk |  |  |
| 23 | `1e5b1dc5e` | 2026-08-16 | 依赖升级 | Update okhttp to v5.5.0 (#3789) | 1 | 1 | 对 hunk |  |  |
| 24 | `b2015d1ef` | 2026-08-17 | 功能/修复 | Migrate to Metro DI (#3608) | 314 | 100 | 重写 | P9 |  |
| 25 | `2b532ea0c` | 2026-08-17 | 翻译 | Translations update from Hosted Weblate (#3701) | 56 | 25 | 对 hunk |  |  |
| 26 | `3e98c15b7` | 2026-08-17 | 功能/修复 | Remove duplicate in strings.xml | 1 | 1 | 对 hunk |  |  |
| 27 | `8586d81e6` | 2026-08-17 | 依赖升级 | Update spotless to v8.10.0 (#3798) | 1 | 1 | 对 hunk |  |  |
| 28 | `c613b5250` | 2026-08-17 | 功能/修复 | Add score info to MangaUpdates search results (#3795) | 3 | 1 | 对 hunk |  |  |
| 29 | `f75f2598a` | 2026-08-17 | 功能/修复 | Fix fit height to in new renderer and bump webgpuviewer (#3801) | 2 | 1 | 重写 |  |  |
| 30 | `3cc0d2c32` | 2026-08-17 | 功能/修复 | Trim MAL & MB `id:` query before lookup (#3793) | 3 | 1 | 对 hunk |  |  |
| 31 | `9fe2c2e10` | 2026-08-20 | 依赖升级 | Update Gradle to v9.7.1 (#3814) | 1 | 0 | 直搬 |  |  |
| 32 | `dee3daf14` | 2026-08-20 | 依赖升级 | Update dependency com.google.firebase.crashlytics to v3.0.8 (#3819) | 1 | 1 | 对 hunk |  |  |
| 33 | `598f634af` | 2026-08-20 | 依赖升级 | Update dependency com.google.firebase:firebase-bom to v34.18.0 (#3820) | 1 | 1 | 对 hunk |  |  |
| 34 | `4b5a0539e` | 2026-08-20 | 依赖升级 | Update dependency com.mikepenz:aboutlibraries-compose-m3 to v15.1.0 (#3822) | 1 | 1 | 对 hunk |  |  |
| 35 | `01bfaa99a` | 2026-08-20 | 功能/修复 | Add support for using the user's chosen Kitsu rating scales (#3818) | 8 | 2 | 重写 |  |  |
| 36 | `0974e787a` | 2026-08-20 | 功能/修复 | Use Shikimori's update endpoint (#3810) | 3 | 0 | 重写 |  |  |
| 37 | `497e26625` | 2026-08-20 | 功能/修复 | Fix page loading for webgpuviewer and bump version (#3823) | 4 | 1 | 重写 |  |  |
| 38 | `31d3c2371` | 2026-08-22 | 功能/修复 | Migrate to Kitsu's GraphQL API (#3792) | 20 | 1 | 对 hunk |  |  |
| 39 | `645824618` | 2026-08-21 | 依赖升级 | Update dependency org.conscrypt:conscrypt-android to v2.6.3 (#3832) | 1 | 1 | 对 hunk |  |  |
| 40 | `9ec479266` | 2026-08-21 | 依赖升级 | Update aboutLibraries to v15.1.1 (#3831) | 1 | 1 | 对 hunk |  |  |
| 41 | `a2c24ff04` | 2026-08-22 | 功能/修复 | Use upstream scaffold (#3834) | 6 | 3 | 对 hunk |  |  |
| 42 | `46c8f07c1` | 2026-08-23 | 功能/修复 | Limit vertical divider height in global search toolbar (#3839) | 1 | 1 | 对 hunk |  |  |
| 43 | `270b78ba9` | 2026-08-23 | 功能/修复 | Wait for extensions to load before the list is propagated (#3841) | 5 | 2 | 对 hunk |  |  |
| 44 | `e3813028b` | 2026-08-23 | 依赖升级 | Bump webgpuviewer, fix animated images background color (#3843) | 5 | 3 | 重写 |  |  |
| 45 | `ac249e366` | 2026-08-23 | 功能/修复 | Exit early in CloudflareInterceptor when cloudflare challenge is interactive (#3842) | 1 | 0 | 直搬 | P6 |  |
| 46 | `0854bde12` | 2026-08-26 | 依赖升级 | Bump webgpuviewer, imagedecoder, fixes (#3852) | 3 | 2 | 重写 |  |  |
| 47 | `3f56685e0` | 2026-08-26 | 依赖升级 | Update android.gradle to v9.3.2 (#3853) | 1 | 1 | 对 hunk |  |  |
| 48 | `deb7b3311` | 2026-08-26 | 依赖升级 | Update dependency org.jsoup:jsoup to v1.23.2 (#3854) | 1 | 1 | 对 hunk |  |  |
| 49 | `7847cad0f` | 2026-08-28 | 依赖升级 | Update coil to v3.6.0 (#3860) | 1 | 1 | 对 hunk |  |  |
| 50 | `2776e3edd` | 2026-08-28 | 依赖升级 | Update dependency androidx.baselineprofile to v1.5.0-rc02 (#3856) | 1 | 1 | 对 hunk |  |  |
| 51 | `6cfce8d42` | 2026-08-28 | 依赖升级 | Update actions/setup-java action to v6 (#3861) | 2 | 2 | 重写 |  |  |
| 52 | `1512ca63d` | 2026-08-28 | 依赖升级 | Update dependency com.materialkolor:material-kolor to v5.0.1 (#3864) | 1 | 1 | 对 hunk |  |  |
| 53 | `161617b95` | 2026-08-28 | 依赖升级 | Update dependency de.stefan-oltmann:kim to v0.38.1 (#3862) | 1 | 1 | 对 hunk |  |  |
| 54 | `7b41ebf8f` | 2026-08-28 | 依赖升级 | Update spotless to v8.10.1 (#3865) | 1 | 1 | 对 hunk |  |  |
| 55 | `ff3dedb6d` | 2026-08-28 | 依赖升级 | Update dependency androidx.glance:glance-appwidget to v1.2.0 (#3867) | 1 | 1 | 对 hunk |  |  |
| 56 | `c1d1f6b16` | 2026-08-28 | 依赖升级 | Update aboutLibraries to v15.2.0 (#3866) | 1 | 1 | 对 hunk |  |  |
| 57 | `5fadea61e` | 2026-08-28 | 功能/修复 | Make the source manager surface suspend (#3869) | 50 | 20 | 对 hunk | P9 |  |
| 58 | `bb24e904a` | 2026-08-28 | 依赖升级 | Bump webgpuviewer, fix navigation directions and long strip bounds (#3870) | 5 | 3 | 重写 |  |  |
| 59 | `b69594011` | 2026-08-29 | 功能/修复 | Switch to Material Symbols and use Valkyrie for ImageVector generation (#3873) | 224 | 48 | 重写 | P9 |  |
| 60 | `7f342ca07` | 2026-08-29 | 功能/修复 | Use built-in DelegateLastClassLoader for extensions (#3874) | 3 | 0 | 对 hunk |  |  |
| 61 | `34a33ed4f` | 2026-08-30 | 功能/修复 | Use filled icon for library continue reading button | 2 | 1 | 重写 |  |  |
| 62 | `baad5c59b` | 2026-08-30 | 功能/修复 | Clean up some missed unused imports (#3876) | 24 | 13 | 对 hunk |  |  |
| 63 | `056d9baee` | 2026-08-31 | 功能/修复 | Migrate composeGrid to new group ID (#3877) | 2 | 1 | 对 hunk |  |  |
| 64 | `21af65b10` | 2026-09-04 | 功能/修复 | Fix unhelpful generic 401 error on AniList expiry (#3888) | 5 | 1 | 对 hunk |  |  |
| 65 | `f3125ab89` | 2026-09-04 | 依赖升级 | Bump webgpuviewer (#3886) | 7 | 4 | 重写 |  |  |
| 66 | `3acd7eaca` | 2026-09-04 | 依赖升级 | Update coil to v3.6.2 (#3889) | 1 | 1 | 对 hunk |  |  |
| 67 | `5e99306e5` | 2026-09-06 | 功能/修复 | Minor tracking refactors (#3900) | 38 | 1 | 对 hunk |  |  |
| 68 | `a82baac3e` | 2026-09-06 | 功能/修复 | Fixed crash caused by incorrect handling of kitsu score type from login (#3887) | 1 | 0 | 对 hunk |  |  |
| 69 | `5b69dbad8` | 2026-09-06 | 功能/修复 | Fix default category and manga sometimes not having their category restored (#3891) | 2 | 2 | 对 hunk |  |  |
| 70 | `f7cd820f2` | 2026-09-06 | 依赖升级 | Update dependency org.conscrypt:conscrypt-android to v2.7.0 (#3897) | 1 | 1 | 对 hunk |  |  |
| 71 | `3a64c8d65` | 2026-09-06 | 依赖升级 | Update dependency com.squareup.okio:okio to v3.18.2 (#3894) | 1 | 1 | 对 hunk |  |  |
| 72 | `aebf11a74` | 2026-09-08 | 功能/修复 | Make tracker utils package-internal (#3908) | 11 | 0 | 对 hunk |  |  |
| 73 | `2810dd14c` | 2026-09-09 | 依赖升级 | Update dependency com.mohamedrejeb.richeditor:richeditor-compose to v1.2.0 (#3896) | 1 | 1 | 对 hunk |  |  |
| 74 | `6c0fa3a04` | 2026-09-09 | 依赖升级 | Update spotless to v8.10.2 (#3895) | 1 | 1 | 对 hunk |  |  |
| 75 | `3f845c12c` | 2026-09-09 | 依赖升级 | Update android.gradle to v9.4.0 (#3893) | 1 | 1 | 对 hunk |  |  |
| 76 | `44a9d96e9` | 2026-09-09 | 依赖升级 | Update dependency de.stefan-oltmann:kim to v0.39.1 (#3890) | 1 | 1 | 对 hunk |  |  |
| 77 | `d0ee51404` | 2026-09-09 | 功能/修复 | Update GitHub Actions (#3892) | 2 | 2 | 重写 |  |  |
| 78 | `e32231eea` | 2026-09-09 | 依赖升级 | Update dependency com.google.firebase:firebase-bom to v34.19.0 (#3915) | 1 | 1 | 对 hunk |  |  |
| 79 | `35bb84705` | 2026-09-10 | 功能/修复 | Fix GPU texture leak when a decoded page is no longer usable (#3872) | 1 | 0 | 重写 |  |  |
| 80 | `0ce6f22cf` | 2026-09-09 | 依赖升级 | Update kotlin monorepo to v2.4.20 (#3917) | 1 | 1 | 对 hunk |  |  |
| 81 | `a5260487d` | 2026-09-09 | 依赖升级 | Update metro to v1.4.3 (#3918) | 1 | 1 | 对 hunk |  |  |
| 82 | `5f87f7e05` | 2026-09-10 | 依赖升级 | Update dependency androidx.sqlite:sqlite-bundled to v2.7.1 (#3916) | 1 | 1 | 对 hunk |  |  |
| 83 | `1e054ea14` | 2026-09-10 | 功能/修复 | Fix inverted MAL OAuth expiry check logic (#3927) | 1 | 0 | 对 hunk |  |  |
| 84 | `d17ad1e00` | 2026-09-11 | 依赖升级 | Bump webgpuviewer (#3933) | 9 | 5 | 重写 |  |  |
| 85 | `9f480564c` | 2026-09-12 | 依赖升级 | Update dependency androidx.baselineprofile to v1.5.0 (#3934) | 1 | 1 | 对 hunk |  |  |
| 86 | `5de262675` | 2026-09-12 | 依赖升级 | Update dependency io.kotest:kotest-assertions-core to v6.2.5 (#3935) | 1 | 1 | 对 hunk |  |  |
| 87 | `918cfb3dd` | 2026-09-12 | 依赖升级 | Update dependency androidx.benchmark:benchmark-macro-junit4 to v1.5.0 (#3936) | 1 | 1 | 对 hunk |  |  |
| 88 | `2292554d9` | 2026-09-12 | 依赖升级 | Update dependency de.stefan-oltmann:kim to v0.40.0 (#3937) | 1 | 1 | 对 hunk |  |  |
| 89 | `802958b1e` | 2026-09-12 | 依赖升级 | Bump compileSdk to 37.1 | 4 | 0 | 直搬 | P3b |  |
| 90 | `737fdbfbf` | 2026-09-12 | 功能/修复 | Switch to upstream FlexibleAdapter | 1 | 1 | 对 hunk |  |  |
| 91 | `f7da5c856` | 2026-09-12 | 功能/修复 | Switch to upstream sqldelight-androidx-driver | 1 | 1 | 对 hunk | P4b |  |
| 92 | `d435dd921` | 2026-09-12 | 功能/修复 | Switch to upstream Injekt | 2 | 2 | 对 hunk |  |  |
| 93 | `6c6eff3ab` | 2026-09-12 | 依赖升级 | Update markdown to v0.45.0 (#3806) | 2 | 2 | 对 hunk |  |  |
| 94 | `855918bbf` | 2026-09-12 | 依赖升级 | Update dependency androidx.compose:compose-bom-alpha to v2026.09.00 (#3752) | 6 | 4 | 对 hunk |  |  |
| 95 | `6ef6e8181` | 2026-09-12 | 依赖升级 | Update dependency com.google.android.material:material to v1.14.0 (#3226) | 3 | 1 | 对 hunk |  |  |
| 96 | `60e74b8d8` | 2026-09-12 | 功能/修复 | Don't try to create slider state when slider can't or won't be rendered (#3941) | 1 | 1 | 对 hunk |  |  |
| 97 | `2d1a8bbef` | 2026-09-12 | 功能/修复 | Tighten AniList rate limit (#3942) | 2 | 1 | 对 hunk |  |  |
| 98 | `7418f4e7b` | 2026-09-13 | 依赖升级 | Bump webgpuviewer (#3949) | 6 | 3 | 重写 |  |  |
| 99 | `cbafe9bf3` | 2026-09-14 | 翻译 | Translations update from Hosted Weblate (#3938) | 64 | 23 | 对 hunk |  |  |
| 100 | `0fbaa1ece` | 2026-09-14 | 功能/修复 | Replace extension NSFW flag with content warning app-wide (#3951) | 14 | 3 | 对 hunk |  |  |
| 101 | `a5cd541bd` | 2026-09-14 | 功能/修复 | webgpuviewer: Fix menu hiding when navigating in long strip (#3956) | 3 | 1 | 重写 |  |  |
| 102 | `0e0bc6972` | 2026-09-14 | 功能/修复 | Allow user to load installed extensions regardless of content warning (#3952) | 4 | 1 | 对 hunk |  |  |
| 103 | `e6d8efe6f` | 2026-09-14 | 功能/修复 | Expose installed extensions that failed to load (#3953) | 19 | 7 | 对 hunk |  |  |
| 104 | `fdc2d40a1` | 2026-09-14 | 功能/修复 | Reload extensions when relevant settings are updated (#3954) | 8 | 5 | 对 hunk |  |  |
| 105 | `b2cda03e3` | 2026-09-14 | 功能/修复 | Use modern OpenDocument for backup file selection (#3948) | 1 | 1 | 对 hunk |  |  |
| 106 | `1c43addec` | 2026-09-15 | 功能/修复 | Resolve Injekt dependencies directly from the Metro graph (#3965) | 7 | 1 | 重写 |  |  |
| 107 | `a7179805a` | 2026-09-16 | 功能/修复 | Use & write Year, Month, Day info in ComicInfo (#3967) | 5 | 3 | 对 hunk | P2 | 可移植，只做读侧 |
| 108 | `1e142c425` | 2026-09-16 | 功能/修复 | Fix padded year & 31-clamped month in local source (#3970) | 1 | 1 | 对 hunk | P2 | 不适用（你的日期逻辑已重写） |
| 109 | `51efde975` | 2026-09-16 | 翻译 | Translations update from Hosted Weblate (#3950) | 68 | 25 | 对 hunk |  |  |
| 110 | `5848ffb4c` | 2026-09-17 | 依赖升级 | Update metro to v1.4.4 (#3973) | 1 | 1 | 对 hunk |  |  |
| 111 | `385d076d4` | 2026-09-19 | 翻译 | Translations update from Hosted Weblate (#3972) | 1 | 1 | 对 hunk |  |  |
| 112 | `504ec2afa` | 2026-09-19 | 依赖升级 | Update android.gradle to v9.4.1 (#3982) | 1 | 1 | 对 hunk |  |  |
| 113 | `01e3f8c60` | 2026-09-19 | 功能/修复 | Add refresh button to tracker for new profile info (#3828) | 18 | 2 | 对 hunk |  |  |
| 114 | `71cd94a31` | 2026-09-19 | 依赖升级 | Update dependency ubuntu to v26 (#3986) | 3 | 3 | 重写 |  |  |
| 115 | `424bbc53b` | 2026-09-19 | 依赖升级 | Update coil to v3.6.3 (#3979) | 1 | 1 | 对 hunk |  |  |
| 116 | `debab6c26` | 2026-09-20 | 功能/修复 | Enable Gradle Tooling parallelism explicitly | 2 | 0 | 直搬 | P3a |  |
| 117 | `c69b14c97` | 2026-09-20 | 依赖升级 | Bump compileSdk to 37.2 | 1 | 0 | 对 hunk | P3b |  |
| 118 | `12fbb13df` | 2026-09-20 | 功能/修复 | Enable Gradle configuration cache | 7 | 3 | 重写 | P3a |  |
| 119 | `427e10584` | 2026-09-20 | 功能/修复 | Enable Gradle isolated projects | 4 | 1 | 重写 | P3a |  |
| 120 | `2b9cb4ca5` | 2026-09-20 | 功能/修复 | Refactor GenerateLocalesConfigTask to run when string files updates | 2 | 0 | 直搬 | P3b |  |
| 121 | `2c979d2d3` | 2026-09-20 | 功能/修复 | Enable `android.nonTransitiveRClass` | 7 | 2 | 对 hunk | P3b |  |
| 122 | `0de91a9c5` | 2026-09-20 | 功能/修复 | Remove redundant dimens.xm in :app | 1 | 0 | 直搬 | P3b |  |
| 123 | `61680580d` | 2026-09-20 | 功能/修复 | Simplify build flags setup with distribution profile | 3 | 2 | 重写 |  |  |
| 124 | `93a163f96` | 2026-09-20 | 功能/修复 | Only upload Crashlytics mapping file when building for release | 2 | 1 | 对 hunk |  |  |
| 125 | `73fef961e` | 2026-09-20 | 依赖升级 | Update dependency me.zhanghai.android.libarchive:library to v1.1.7 (#3983) | 1 | 1 | 对 hunk |  |  |
| 126 | `57666e920` | 2026-09-20 | 依赖升级 | Update sqldelight to v2.4.0 (#3985) | 4 | 4 | 对 hunk |  |  |
| 127 | `449c4e08e` | 2026-09-20 | 翻译 | Translations update from Hosted Weblate (#3987) | 2 | 1 | 对 hunk |  |  |
| 128 | `f52d890e7` | 2026-09-22 | 功能/修复 | Fix library search when clicking source name in manga info screen (#4002) | 3 | 3 | 对 hunk |  |  |
| 129 | `532575e29` | 2026-09-26 | 功能/修复 | Fixed entries being incorrectly marked as duplicates when tracked with some trackers (#4008) | 2 | 2 | 对 hunk |  |  |
| 130 | `c67a33f3d` | 2026-09-27 | 功能/修复 | Remove unused sync scaffold | 18 | 16 | 重写 | P9 |  |
| 131 | `a4abb6292` | 2026-09-27 | 功能/修复 | Split the universal update queries into case-specific ones | 14 | 11 | 对 hunk | P9 |  |
| 132 | `306befb21` | 2026-09-27 | 功能/修复 | Restrict raw database access to the data module | 29 | 10 | 重写 | P9 |  |
| 133 | `f8fff318b` | 2026-09-27 | 功能/修复 | Build manga and chapter updates with a lambda and track assigned fields | 21 | 17 | 重写 | P9 |  |
| 134 | `986f46c09` | 2026-09-27 | 功能/修复 | Merge manga favorite related fields into favorite_at | 18 | 13 | 重写 | P9 |  |
| 135 | `4df0e6b16` | 2026-09-27 | 功能/修复 | Keep category orders unique while reordering | 2 | 0 | 对 hunk |  |  |
| 136 | `6ee529c5a` | 2026-09-27 | 功能/修复 | Apply chapter sync in one transaction | 6 | 4 | 重写 |  |  |
| 137 | `0044cc73e` | 2026-09-27 | 功能/修复 | Fix stored stub sources not loading and being rewritten on every reload | 2 | 1 | 对 hunk |  |  |
| 138 | `fc5592ff7` | 2026-09-27 | 功能/修复 | Don't bring back extension stores removed during a refresh | 2 | 0 | 对 hunk |  |  |
| 139 | `8bfa495f3` | 2026-09-27 | 功能/修复 | Assign category positions inside the database | 8 | 1 | 对 hunk |  |  |
| 140 | `553762fae` | 2026-09-27 | 功能/修复 | Fix reading history being double counted | 3 | 1 | 重写 |  |  |
| 141 | `2b4b8472b` | 2026-09-27 | 功能/修复 | Open a requested chapter in the reader even if its scanlator is excluded by filter | 1 | 1 | 对 hunk |  |  |
| 142 | `9a77baedc` | 2026-09-27 | 功能/修复 | Update an existing track in place instead of replacing it | 12 | 2 | 重写 |  |  |
| 143 | `4b48a84ec` | 2026-09-27 | 功能/修复 | Keep a stored track's remote entry when restoring | 1 | 0 | 重写 | P7 |  |
| 144 | `b74e010bf` | 2026-09-27 | 功能/修复 | Order updates by fetch date in the queries themselves | 1 | 0 | 对 hunk |  |  |
| 145 | `ed17ece4e` | 2026-09-27 | 功能/修复 | Return an entry's categories in display order | 1 | 0 | 对 hunk |  |  |
| 146 | `093841105` | 2026-09-27 | 功能/修复 | Install and update extensions only from a store whose signing key matches | 5 | 1 | 对 hunk |  | 需重写（ExtensionLoader 已领先 173 行） |
| 147 | `2d86a70ce` | 2026-09-27 | 功能/修复 | Offer updates for extensions that aren't loaded | 10 | 3 | 重写 |  |  |
| 148 | `4f33486aa` | 2026-09-27 | 功能/修复 | Clean up extension update checking | 4 | 2 | 对 hunk |  |  |
| 149 | `09edf7dc8` | 2026-09-28 | 功能/修复 | Keep an installed extension's store current as stores change | 1 | 1 | 对 hunk |  |  |
| 150 | `087e38ad4` | 2026-09-28 | 依赖升级 | Update dependency androidx.compose:compose-bom-alpha to v2026.09.01 (#4009) | 1 | 1 | 对 hunk |  |  |
| 151 | `ba4c04fe7` | 2026-09-28 | 依赖升级 | Update dependency androidx.core:core to v1.19.1 (#4010) | 1 | 1 | 对 hunk |  |  |
| 152 | `3542143b4` | 2026-09-28 | 依赖升级 | Update dependency androidx.webkit:webkit to v1.17.1 (#4011) | 1 | 1 | 对 hunk |  |  |
| 153 | `f83539809` | 2026-09-28 | 依赖升级 | Update metro to v1.4.5 (#4012) | 1 | 1 | 对 hunk |  |  |
| 154 | `9b4c3115d` | 2026-09-28 | 依赖升级 | Update spotless to v8.10.3 (#4013) | 1 | 1 | 对 hunk |  |  |
| 155 | `7f4c55176` | 2026-09-28 | 依赖升级 | Update dependency androidx.annotation:annotation to v1.11.0 (#4014) | 1 | 1 | 对 hunk |  |  |
| 156 | `9b5e0ae73` | 2026-09-28 | 功能/修复 | Show an extension's store and install failures in the extension list (#3955) | 15 | 3 | 对 hunk |  |  |
| 157 | `7451c228e` | 2026-09-28 | 依赖升级 | Update Gradle to v9.8.0 (#4016) | 1 | 0 | 对 hunk |  |  |
| 158 | `59fdf7e7f` | 2026-09-28 | 依赖升级 | Update dependency androidx.work:work-runtime to v2.12.0 (#4015) | 1 | 1 | 对 hunk |  |  |
| 159 | `ec614bb4c` | 2026-09-28 | 功能/修复 | Use Apollo GraphQL for Trackers with GraphQL APIs (#3827) | 69 | 2 | 重写 | P9 |  |
| 160 | `93cc07511` | 2026-09-28 | 功能/修复 | Tweak database table and view schema (#3805) | 29 | 11 | 重写 | P9 |  |
| 161 | `2d1d2e4ca` | 2026-09-28 | 功能/修复 | Rework database queries for the new schema (#4022) | 28 | 10 | 重写 | P9 |  |
| 162 | `65d7110e1` | 2026-09-28 | 功能/修复 | Remove libvips based imagedecoder and add progressive rendering (#4023) | 9 | 3 | 重写 |  |  |
| 163 | `866d045c7` | 2026-09-28 | 依赖升级 | Update gradle/actions action to v6.4.0 (#4024) | 2 | 2 | 重写 |  |  |
| 164 | `972c9d43d` | 2026-10-01 | 依赖升级 | Bump versionCode to 33 | 1 | 1 | 对 hunk |  |  |
| 165 | `98ba69305` | 2026-10-01 | 依赖升级 | Bump webgpuviewer (#4029) | 4 | 1 | 重写 |  |  |
| 166 | `f2464a1ba` | 2026-10-01 | 功能/修复 | Add an option to disable the MD5 suffix in chapter names (#3966) | 7 | 5 | 对 hunk | P4b |  |
| 167 | `6dfa10ffa` | 2026-10-01 | 功能/修复 | Ensure tachiyomix public API is not removed by R8 | 2 | 0 | 直搬 |  |  |
| 168 | `01d302feb` | 2026-10-01 | 功能/修复 | Let DownloadJob own the downloader lifecycle | 3 | 0 | 对 hunk | P4b |  |
| 169 | `38e93086c` | 2026-10-01 | 功能/修复 | Configure database connections like Room | 1 | 0 | 重写 | P1 | 可移植（搬进 AppModule） |
| 170 | `9409740d7` | 2026-10-01 | 功能/修复 | Let SliderPreference collect its own preference | 4 | 1 | 对 hunk |  |  |
| 171 | `9abc83076` | 2026-10-02 | 功能/修复 | Rename `Preference.enabled` to `visible` to correctly reflect usage | 10 | 5 | 对 hunk |  |  |
| 172 | `db45dda52` | 2026-10-02 | 功能/修复 | Make CoroutineWorker supertypes have Worker suffix | 20 | 7 | 重写 |  |  |
| 173 | `a4780e4ed` | 2026-10-03 | 功能/修复 | Use Dispatchers.IO.limitedParallelism in SearchViewModel to prevent thread leak (#4036) | 1 | 0 | 对 hunk |  |  |
| 174 | `4c88f0264` | 2026-10-03 | 功能/修复 | Fix race condition when updating search items in SearchViewModel (#4037) | 1 | 0 | 对 hunk |  |  |
| 175 | `a7a8cc834` | 2026-10-04 | 依赖升级 | Update dependency com.mohamedrejeb.richeditor:richeditor-compose to v1.2.1 (#4030) | 1 | 1 | 对 hunk |  |  |
| 176 | `f35bc341a` | 2026-10-04 | 依赖升级 | Update dependency com.cheonjaeung.compose.grid:grid to v2.9.0 (#4041) | 1 | 1 | 对 hunk |  |  |
| 177 | `d1c5c12bd` | 2026-10-04 | 功能/修复 | Fix webtoon reader leaving a gap after zooming in a resized window | 1 | 1 | 对 hunk |  |  |
| 178 | `f82a7a68f` | 2026-10-04 | 功能/修复 | Build download error notifications fresh each time | 1 | 0 | 对 hunk | P4b |  |
| 179 | `7aacaa349` | 2026-10-04 | 功能/修复 | Keep the paused download notification after the worker stops | 2 | 1 | 对 hunk |  |  |
| 180 | `f027de8c2` | 2026-10-03 | 功能/修复 | Look up library item sources and download counts once per library update | 1 | 1 | 对 hunk |  |  |
| 181 | `a5d584eeb` | 2026-10-04 | 功能/修复 | Sanitize the scanlator in chapter download folder names | 1 | 0 | 对 hunk | P4a |  |
| 182 | `6490c41e6` | 2026-10-04 | 功能/修复 | Wait for a canceled downloader job to finish before starting another | 1 | 0 | 对 hunk | P4b |  |
| 183 | `61cd786e7` | 2026-10-04 | 功能/修复 | Use WAL on every device | 1 | 0 | 重写 | P1 | 可移植 |
| 184 | `730e43fd1` | 2026-10-04 | 功能/修复 | Download a source's next chapter while the current one finishes | 2 | 0 | 对 hunk | P4b |  |
| 185 | `1f85a5a8d` | 2026-10-04 | 功能/修复 | Share one cover load between rows that show it at the same time | 4 | 2 | 对 hunk | P5a |  |
| 186 | `fb8e640d6` | 2026-10-04 | 功能/修复 | Look up queued and downloaded chapters once per chapter list | 4 | 2 | 对 hunk | P4b |  |
| 187 | `9a6dea804` | 2026-10-04 | 功能/修复 | Stop polling queued downloads for their page list | 1 | 0 | 对 hunk | P4b |  |
| 188 | `7ff97761b` | 2026-10-04 | 功能/修复 | Make MangaViewModel collect the entry only while subscribed | 1 | 1 | 对 hunk |  |  |
| 189 | `940272f75` | 2026-10-05 | 功能/修复 | Load tracks in the library only for tracker filters and sorting | 1 | 1 | 对 hunk |  |  |
| 190 | `5df78e305` | 2026-10-05 | 功能/修复 | Read the library's last read time without joining history to chapters | 3 | 2 | 重写 |  |  |
| 191 | `179bfaca7` | 2026-10-05 | 功能/修复 | Load all tracks in one query for statistics | 4 | 0 | 对 hunk |  |  |
| 192 | `6a329386c` | 2026-10-05 | 功能/修复 | Build a chapter's folder name variants from parts computed once | 1 | 0 | 对 hunk | P4a |  |
| 193 | `06d612811` | 2026-10-07 | 功能/修复 | Let R8 shrink and optimize app code | 1 | 0 | 对 hunk | P3c | 需真机验证后再合 |
| 194 | `1e471a414` | 2026-10-07 | 功能/修复 | Stop updates showing a finished chapter as still downloading | 1 | 1 | 对 hunk |  |  |
| 195 | `5910a51eb` | 2026-10-07 | 功能/修复 | Keep the names of uninstalled sources across backup restores | 2 | 1 | 对 hunk |  |  |
| 196 | `76b146f3d` | 2026-10-07 | 功能/修复 | Show the updates widget's empty and locked text | 2 | 0 | 直搬 |  |  |
| 197 | `6d4ed6cb2` | 2026-10-07 | 功能/修复 | Use the backport class loader for extensions before Android 17 | 1 | 0 | 重写 |  |  |
| 198 | `a6ee4bc59` | 2026-10-07 | 功能/修复 | Fix library range selection crash across categories | 1 | 1 | 对 hunk |  |  |
| 199 | `fd4f73c90` | 2026-10-07 | 功能/修复 | Show a screen while the app migrates after an update | 7 | 2 | 重写 |  |  |
| 200 | `ab7ee00c4` | 2026-10-07 | 功能/修复 | Fix tap navigation staying off after a cancelled button press (#3655) | 1 | 1 | 对 hunk |  |  |
| 201 | `dd46413e1` | 2026-10-09 | 功能/修复 | List MangaBaka first among trackers | 4 | 3 | 重写 |  |  |
| 202 | `a80027ff3` | 2026-10-09 | 功能/修复 | Update icons and move remaining to Material Symbols (#4057) | 151 | 29 | 重写 | P9 |  |
| 203 | `d1e71ab58` | 2026-10-09 | 依赖升级 | Bump webgpuviewer (#4051) | 2 | 1 | 重写 |  |  |
| 204 | `e998904fb` | 2026-10-10 | 功能/修复 | Update GitHub Actions (#4059) | 2 | 2 | 重写 |  |  |
| 205 | `8d3915356` | 2026-10-10 | 依赖升级 | Update androidx.paging to v3.5.2 (#4058) | 1 | 1 | 对 hunk |  |  |
| 206 | `7f2b4962b` | 2026-10-10 | 依赖升级 | Update dependency com.google.firebase:firebase-bom to v35 (#4066) | 1 | 1 | 对 hunk |  |  |
| 207 | `24bd3c95a` | 2026-10-10 | 依赖升级 | Update Gradle to v9.8.1 (#4060) | 1 | 0 | 对 hunk |  |  |
| 208 | `2f5b9c164` | 2026-10-10 | 依赖升级 | Update kotlin monorepo to v2.4.21 (#4061) | 1 | 1 | 对 hunk |  |  |
| 209 | `5b3505bf5` | 2026-10-10 | 依赖升级 | Update sqldelight to v2.4.1 (#4064) | 1 | 1 | 对 hunk |  |  |
| 210 | `157679bba` | 2026-10-10 | 依赖升级 | Update spotless to v8.10.4 (#4063) | 1 | 1 | 对 hunk |  |  |
| 211 | `0c1be369b` | 2026-10-10 | 功能/修复 | Ask to add an entry to the library before editing its cover (#2265) | 4 | 4 | 对 hunk |  |  |
| 212 | `36ec043a5` | 2026-10-10 | 功能/修复 | Delete downloads of duplicates read along with a chapter (#3030) | 1 | 1 | 对 hunk |  |  |
| 213 | `8c0419657` | 2026-10-10 | 功能/修复 | Always update library entries that were never initialized (#3179) | 2 | 1 | 重写 |  |  |
| 214 | `40f552981` | 2026-10-10 | 功能/修复 | Don't match every library entry as a duplicate of a blank title (#3308) | 1 | 0 | 重写 | P1 | 可移植（4 行，列名要换） |
| 215 | `785f4fd1e` | 2026-10-10 | 功能/修复 | Keep downloads in excluded categories when marking them read (#3558) | 1 | 1 | 对 hunk |  |  |
| 216 | `d3f10772f` | 2026-10-10 | 功能/修复 | Include the local source in global search (#3784) | 1 | 0 | 对 hunk |  | 可移植但不建议（与本地 tab 重复） |
| 217 | `287cbbb98` | 2026-10-10 | 功能/修复 | Keep the last update time on an empty Updates screen (#3790) | 2 | 2 | 对 hunk |  |  |
| 218 | `f28b2d887` | 2026-10-10 | 功能/修复 | Cancel queued downloads of chapters the source removed (#3845) | 1 | 1 | 对 hunk |  |  |
| 219 | `88d4071cc` | 2026-10-10 | 功能/修复 | Don't let the stored download index overwrite a startup rescan (#3968) | 1 | 0 | 对 hunk | P1 | 可移植 |
| 220 | `be44a81fb` | 2026-10-10 | 功能/修复 | Keep listing local chapters when one archive can't be read (#3997) | 1 | 1 | 对 hunk |  | 已有等价实现，跳过 |
| 221 | `adc9ee74e` | 2026-10-10 | 功能/修复 | Respect hidden missing chapter indicators in chapter headers (#4039) | 1 | 1 | 对 hunk |  |  |
| 222 | `e9234f205` | 2026-10-10 | 功能/修复 | Match setting group headings in settings search (#4044) | 3 | 2 | 对 hunk |  |  |
| 223 | `8972882a3` | 2026-10-10 | 功能/修复 | Use plain icon buttons for extension actions | 9 | 2 | 对 hunk |  |  |
| 224 | `44c1e1915` | 2026-10-10 | 功能/修复 | Store every date in the database as an Instant | 68 | 32 | 重写 | P9 |  |
| 225 | `292381662` | 2026-10-10 | 功能/修复 | Follow weekday and monthly release patterns for next update and back it up | 6 | 2 | 重写 |  |  |
| 226 | `6c1b0702b` | 2026-10-10 | 功能/修复 | Use the beta Compose BOM | 2 | 2 | 对 hunk |  |  |
| 227 | `e42e01049` | 2026-10-04 | 功能/修复 | Recognize volumes and use neighbouring chapters for chapter numbers | 3 | 1 | 对 hunk |  |  |
| 228 | `e21414b22` | 2026-10-10 | 功能/修复 | Drop the legacy chapter model and narrow last page read to an Int | 23 | 16 | 重写 | P9 |  |
| 229 | `f921ec07a` | 2026-09-22 | 功能/修复 | Use an app scoped CoroutineScope | 22 | 8 | 重写 |  |  |
| 230 | `611578313` | 2026-10-10 | 功能/修复 | Index a finished download found on disk instead of downloading it again | 1 | 0 | 对 hunk | P4b |  |
| 231 | `a82d27c3a` | 2026-10-10 | 功能/修复 | Keep the chapter selection when new chapters download after a refresh | 1 | 1 | 对 hunk |  |  |
| 232 | `f9c5fa901` | 2026-10-10 | 功能/修复 | Log app scope failures instead of crashing outside debug builds | 4 | 0 | 重写 |  | 需重写（Injekt 版） |
| 233 | `c46317f09` | 2026-10-10 | 功能/修复 | Move blocking work off the main thread and fix threading races (#4068) | 131 | 46 | 重写 | P9 |  |
| 234 | `c0c090999` | 2026-10-10 | 功能/修复 | Fix Kitsu showing novels in search results (#4069) | 2 | 1 | 对 hunk |  |  |
| 235 | `3e5d593e2` | 2026-10-11 | 功能/修复 | Implement parallel page loading and preloading in reader (#2986) | 6 | 5 | 对 hunk | P5b |  |
| 236 | `b15af71b9` | 2026-10-11 | 功能/修复 | Load installed extension and source icons through Coil | 7 | 2 | 对 hunk |  |  |
| 237 | `260e31cd2` | 2026-10-11 | 功能/修复 | Improve performance across screens (#4071) | 33 | 17 | 重写 | P9 |  |
| 238 | `8066b8dfc` | 2026-10-11 | 功能/修复 | Read extension stores once at startup | 3 | 0 | 对 hunk |  |  |
| 239 | `ba16158b1` | 2026-10-11 | 功能/修复 | Show download progress and open the manga screen with less work | 5 | 5 | 对 hunk |  |  |
| 240 | `f02bf5ba7` | 2026-10-11 | 功能/修复 | Queue many chapters without probing each one on disk | 3 | 1 | 对 hunk |  |  |
| 241 | `b454c1b46` | 2026-10-11 | 功能/修复 | Keep the migration screen up for at least a second | 1 | 1 | 对 hunk |  |  |
| 242 | `fc20410ce` | 2026-10-11 | 功能/修复 | Allow skipping an app update version | 9 | 6 | 重写 | P8 |  |
| 243 | `ba4481525` | 2026-10-11 | 功能/修复 | Count early, late and backdated releases when predicting next update | 6 | 2 | 对 hunk |  |  |
| 244 | `e562a9b4f` | 2026-10-11 | 功能/修复 | Show a wavy progress ring while a chapter downloads | 1 | 1 | 对 hunk |  |  |
| 245 | `d58130a5f` | 2026-10-11 | 功能/修复 | Check for app updates every 6 hours while the app is open | 1 | 1 | 对 hunk | P8 |  |
| 246 | `bce1f7195` | 2026-10-11 | 功能/修复 | Update changelog | 1 | 1 | 对 hunk |  |  |
| 247 | `dd4e9f00f` | 2026-10-11 | 功能/修复 | Show a confirmation dialog when unfavoriting manga from the manga screen (#4073) | 3 | 3 | 对 hunk |  |  |
| 248 | `35bd0a4b3` | 2026-10-11 | 功能/修复 | Fixed long strip side padding being counted in zoom bounds  (#4072) | 4 | 4 | 对 hunk | P5a |  |
| 249 | `039138ce9` | 2026-10-11 | 功能/修复 | Add Comick Tracker (#4056) | 14 | 2 | 对 hunk |  |  |
| 250 | `b5856135c` | 2026-10-12 | 功能/修复 | Fix webtoon scroll stutter between pages | 6 | 2 | 重写 | P5a |  |
| 251 | `8912ef5b5` | 2026-10-12 | 功能/修复 | Regenerate baseline profiles | 2 | 0 | 直搬 |  |  |
| 252 | `143a060ac` | 2026-10-12 | 发版 | Release v0.21.0 | 4 | 2 | 对 hunk |  |  |
| 253 | `8457fb91e` | 2026-10-12 | 功能/修复 | Fix chapter refresh failing in locales with non-ASCII digits | 2 | 1 | 对 hunk |  |  |
| 254 | `abb126ad2` | 2026-10-12 | 功能/修复 | Fix startup crash on some Samsung Android 9 to 11 devices | 2 | 2 | 对 hunk |  | 可移植（10 行，可搭车任一批） |
| 255 | `c279291e2` | 2026-10-12 | 功能/修复 | Fix manga screen FAB going under the taskbar on tablets | 2 | 2 | 对 hunk |  |  |
| 256 | `07b9fb695` | 2026-10-12 | 功能/修复 | Fix extension installer crash when cancelling off the main thread | 2 | 1 | 对 hunk |  |  |
| 257 | `6dffca159` | 2026-10-12 | 功能/修复 | Keep a missing date added as is when merging it into favorite_at | 2 | 2 | 对 hunk |  |  |
| 258 | `649fc83ff` | 2026-10-12 | 功能/修复 | Keep the app alive when a WebView renderer dies | 4 | 2 | 对 hunk | P6 |  |
| 259 | `ae749bb4f` | 2026-10-12 | 功能/修复 | Only cancel the install of the extension being cancelled | 2 | 1 | 对 hunk |  |  |
| 260 | `a999e49f2` | 2026-10-12 | 功能/修复 | Index duplicate entries so moving their chapters doesn't take hours | 2 | 2 | 对 hunk |  |  |
| 261 | `9dbb7148c` | 2026-10-12 | 功能/修复 | Show failed migrations instead of waiting on them forever | 6 | 3 | 重写 | P7 |  |
| 262 | `010ac71a8` | 2026-10-12 | 功能/修复 | Revert "Always update uninitialized library entries (#3179)" | 3 | 2 | 重写 |  |  |
| 263 | `51b61a36e` | 2026-10-12 | 功能/修复 | Keep the pager on its page when chapters are added before layout | 2 | 2 | 对 hunk | P5a |  |
| 264 | `66e736222` | 2026-10-12 | 发版 | Release v0.21.1 | 4 | 2 | 对 hunk |  |  |
| 265 | `1ca9b0efb` | 2026-10-12 | 功能/修复 | Don't send full Doubles to Comick for progress (#4090) | 2 | 1 | 重写 |  |  |
| 266 | `ea2b73ad7` | 2026-10-12 | 功能/修复 | Guard the database update against duplicates and damaged indexes | 2 | 2 | 对 hunk |  |  |
| 267 | `43de88c71` | 2026-10-12 | 功能/修复 | Update navigationevent to stop back gestures crashing closed screens | 3 | 3 | 对 hunk |  |  |
| 268 | `7ae858e60` | 2026-10-12 | 功能/修复 | Solve Cloudflare challenges of non-GET requests on the site's root | 2 | 1 | 对 hunk | P6 |  |
| 269 | `ee5939454` | 2026-10-12 | 功能/修复 | Fix tracker start date being shifted by the UTC offset (#4049) | 2 | 1 | 对 hunk |  |  |
| 270 | `3156b4a1c` | 2026-10-12 | 功能/修复 | Insert split pages only into the chapter they belong to | 2 | 2 | 对 hunk |  |  |
| 271 | `c9e1b6e96` | 2026-10-12 | 功能/修复 | Show invalid filters from extensions instead of crashing | 3 | 3 | 对 hunk |  |  |
| 272 | `2df48e19a` | 2026-10-12 | 功能/修复 | Fail the install when nothing can confirm it instead of crashing | 2 | 1 | 对 hunk |  |  |
| 273 | `c371d6331` | 2026-10-12 | 功能/修复 | Ignore long strip taps confirmed after the reader is gone | 2 | 2 | 对 hunk | P5a |  |
| 274 | `f0f1e4de5` | 2026-10-12 | 功能/修复 | Fix some downloads not being recognized | 3 | 1 | 对 hunk |  |  |
| 275 | `5a5ff22c3` | 2026-10-12 | 功能/修复 | Fix concurrent Cloudflare challenges each opening a WebView | 3 | 1 | 对 hunk | P6 |  |
| 276 | `771ba33a3` | 2026-10-12 | 功能/修复 | Stream entries when creating and restoring backups | 25 | 12 | 重写 | P7 |  |
| 277 | `3100dcedd` | 2026-10-13 | 发版 | Release v0.21.2 | 4 | 2 | 对 hunk |  |  |
| 278 | `446f6df82` | 2026-10-13 | 依赖升级 | Update androidx.paging to v3.5.3 (#4099) | 1 | 1 | 对 hunk |  |  |
| 279 | `7618df898` | 2026-10-17 | 翻译 | Translations update from Hosted Weblate (#4095) | 45 | 24 | 对 hunk |  |  |
| 280 | `970b12f1b` | 2026-10-17 | 功能/修复 | Add Time Format setting (#4118) | 14 | 7 | 对 hunk | P8 |  |
| 281 | `3058849b5` | 2026-10-17 | 依赖升级 | Update dependency io.kotest:kotest-assertions-core to v6.2.6 (#4128) | 1 | 1 | 对 hunk |  |  |
| 282 | `83e8f917d` | 2026-10-17 | 依赖升级 | Update dependency com.google.firebase:firebase-bom to v35.0.2 (#4127) | 1 | 1 | 对 hunk |  |  |
| 283 | `5f63c983d` | 2026-10-13 | 功能/修复 | Fix WebView renderer being killed by sources setting an Origin header | 5 | 0 | 对 hunk | P6 |  |
| 284 | `a01867dc3` | 2026-10-13 | 功能/修复 | Only send source headers to the source in WebView | 1 | 0 | 对 hunk | P6 |  |
| 285 | `deeb9b258` | 2026-10-14 | 功能/修复 | Fix downloads not being found for sources sharing a stored source's name | 1 | 0 | 对 hunk |  |  |

## 批次汇总

| 批次 | 内容 | 提交 | 触及文件 | 与你重叠 |
| --- | --- | --- | --- | --- |
| P1 | 数据正确性 | 4 | 3 | 0 |
| P2 | ComicInfo 日期（只读） | 2 | 5 | 3 |
| P3a | 编译提速 | 3 | 7 | 3 |
| P3b | 编译目标与 R 类 | 5 | 14 | 2 |
| P3c | R8 放开 shrink | 1 | 1 | 0 |
| P4a | 下载文件夹命名 | 2 | 1 | 0 |
| P4b | 下载调度与队列 | 9 | 16 | 8 |
| P5a | 阅读器边界 | 5 | 14 | 8 |
| P5b | 并行页加载 | 1 | 6 | 5 |
| P6 | 网络与 WebView | 6 | 8 | 2 |
| P7 | 备份与迁移 | 3 | 31 | 14 |
| P8 | 设置与更新 | 3 | 22 | 12 |
| P9 | 结构性改造 | 17 | 782 | 175 |

## 冲突热点

双方都改过、且本仓库改动量最大的文件。改这些地方之前先想清楚要不要动。

已排除 `CHANGELOG.md`、`i18n/`、`fastlane/`、`docs/`、`gradle/libs.versions.toml`：
这几处每个 fork 都会改，放进来会盖住真正的热点。

| 文件 | 上游改动行 | 本仓库改动行 |
| --- | --- | --- |
| `app/src/main/java/eu/kanade/tachiyomi/ui/browse/source/browse/BrowseSourceViewModel.kt` | 170 | 2643 |
| `source-local/src/main/kotlin/tachiyomi/source/local/LocalSource.kt` | 57 | 2641 |
| `app/src/main/java/eu/kanade/presentation/manga/MangaScreen.kt` | 106 | 1631 |
| `app/src/main/java/eu/kanade/tachiyomi/ui/browse/source/browse/BrowseSourceScreen.kt` | 67 | 1539 |
| `app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaViewModel.kt` | 788 | 661 |
| `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt` | 487 | 808 |
| `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderActivity.kt` | 89 | 865 |
| `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/webtoon/WebtoonViewer.kt` | 22 | 848 |
| `app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryViewModel.kt` | 494 | 232 |
| `app/src/main/java/eu/kanade/presentation/manga/components/MangaBottomActionMenu.kt` | 52 | 605 |
| `data/src/main/sqldelight/tachiyomi/migrations/17.sqm` | 617 | 6 |
| `app/src/main/java/eu/kanade/presentation/manga/components/MangaInfoHeader.kt` | 96 | 522 |
| `app/src/main/java/eu/kanade/presentation/components/AppBar.kt` | 105 | 443 |
| `app/src/main/java/eu/kanade/tachiyomi/data/backup/restore/restorers/MangaRestorer.kt` | 441 | 86 |
| `app/src/main/java/eu/kanade/tachiyomi/ui/home/HomeScreen.kt` | 248 | 257 |
| `app/src/main/java/eu/kanade/tachiyomi/ui/manga/track/TrackInfoDialog.kt` | 503 | 2 |
| `app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsDataScreen.kt` | 88 | 387 |
| `app/src/main/java/eu/kanade/tachiyomi/ui/main/MainActivity.kt` | 142 | 288 |
| `app/src/main/java/eu/kanade/presentation/manga/components/MangaChapterListItem.kt` | 34 | 395 |
| `app/src/main/java/eu/kanade/tachiyomi/ui/history/HistoryViewModel.kt` | 85 | 343 |
| `app/src/main/java/eu/kanade/tachiyomi/ui/updates/UpdatesViewModel.kt` | 409 | 18 |
| `app/src/main/java/eu/kanade/tachiyomi/extension/ExtensionManager.kt` | 372 | 21 |
| `data/src/main/java/tachiyomi/data/chapter/ChapterRepositoryImpl.kt` | 218 | 174 |
| `app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreen.kt` | 65 | 323 |
| `presentation-core/src/main/java/tachiyomi/presentation/core/components/material/Scaffold.kt` | 354 | 31 |

