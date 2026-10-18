# 本地删除：进度、停止与回执

三个入口删除本地文件时没有可见进度，也没有中途退出的办法。删除结束后只留一句 toast，删了什么、停在哪一步、还剩哪些，都没有记录。

## 现状

- 入口：`MangaViewModel.deleteLocalChapters` / `deleteLocalManga`、`BrowseSourceViewModel.deleteSelectedLocalManga`、`ChapterFlagListScreen` 批量删标记篇目。
- 耗时：`LocalEntryDeletionService.deleteRecursively` 每个文件一次 SAF IPC。3000 篇目的合集按每次 5–20 ms 估约 15–60 秒。
- 详情页两个入口先 `dismissDialog()` 再删，期间界面没有任何反馈。
- `ChapterFlagListScreen` 用 `rememberCoroutineScope()` 执行删除，旋转屏幕即取消。
- 删完 DB 行与目录一起消失，不留下任何"删过什么"的记录。

## 交互模型

两个维度正交，不按「删的是篇目还是合集」分：

- **要不要等**：任务超过 500 ms → 进度窗。与条数无关，删一个几千文件的合集照样出现。
- **要不要核对**：有失败、被停止、删了 ≥2 条、目录不可用 → 结果窗。与耗时无关。

| 情形 | 进度窗 | 结果 |
| --- | --- | --- |
| 目标 0 条 | 无 | 无 |
| 1 条，成功，< 500 ms | 无 | toast 带名字 |
| 1 条，成功，≥ 500 ms | 出现 | toast 带名字 |
| 1 条，失败 | 可能无 | 结果窗「删除失败」 |
| 1 条，停止 | 出现 | 结果窗「已停止」 |
| ≥ 2 条，全部成功，< 500 ms | 无 | 结果窗「已删除 N 个」 |
| ≥ 2 条，全部成功，≥ 500 ms | 出现 | 结果窗「已删除 N 个」 |
| ≥ 2 条，部分失败 | ≥ 500 ms 出现 | 结果窗「已删除 N 个，M 个失败」 |
| ≥ 2 条，全部失败 | 可能无 | 结果窗「删除失败」 |

单条目停止要弹窗：停止是用户主动干预，此时他需要看到已删多少、还剩多少，并拿到清单。单条目失败同理，否则失败与成功只差一句 toast。

进度窗延迟 500 ms 出现，20 ms 完成的删除不闪窗。计时起点存在 service 里，旋转后不重新计。

## 边界

| 情形 | 行为 |
| --- | --- |
| 目标 0 条 | 不启动任务，不产生报告 |
| 停止时一条都没删 | 结果窗「已停止 / 已删除 0 个，N 个未处理」，导出按钮禁用 |
| 停止时刚好处理完 | 按完成处理，不显示「已停止」 |
| 本地目录不可用 | 结果窗「本地目录不可用」，导出按钮禁用 |
| 目标文件已被外部删掉 | 算已删除。现在算失败，改造时统一——否则「失败」里混着「本来就不在」 |
| 空目录，total = 0 | 不显示 0/0，直接按完成处理 |
| 已有任务在跑时再次发起 | 忽略新请求 |
| 结果窗还在时发起新删除 | 旧报告清空，进度窗接管 |
| 删除中旋转、退出页面、切后台 | 任务继续；进度窗与结果窗从 service 状态恢复 |
| 进程被杀 | 已删文件不回滚，残留 DB 行由下次扫描清掉，无报告 |
| 导出被取消 | 结果窗保持，可重试 |
| 导出写文件失败 | toast「导出失败」，结果窗保持 |
| 后台时完成 | 回到应用时出现结果窗 |

## 文案

`base` 英文、`zh-rCN` 简体、`zh-rTW` 繁体，三处同步。

| 用途 | 字符串 | base | zh-rCN | zh-rTW |
| --- | --- | --- | --- | --- |
| 进度标题 | `local_delete_progress`（改） | Deleting | 正在删除 | 正在刪除 |
| 统计阶段 | `local_delete_counting` | Counting files | 正在统计 | 正在統計 |
| 停止 | `action_stop` | Stop | 停止 | 停止 |
| 结果·篇目 | `local_delete_done_chapters` | Deleted %1$d chapter(s) | 已删除 %1$d 个篇目 | 已刪除 %1$d 個篇目 |
| 结果·合集 | `local_delete_done_collections` | Deleted %1$d collection(s) | 已删除 %1$d 个合集 | 已刪除 %1$d 個合集 |
| 部分失败 | `local_delete_partial`（改） | Deleted %1$d, %2$d failed | 已删除 %1$d 个，%2$d 个失败 | 已刪除 %1$d 個，%2$d 個失敗 |
| 停止标题 | `local_delete_stopped` | Stopped | 已停止 | 已停止 |
| 停止正文 | `local_delete_stopped_summary` | Deleted %1$d, %2$d skipped | 已删除 %1$d 个，%2$d 个未处理 | 已刪除 %1$d 個，%2$d 個未處理 |
| 单条 toast | `local_delete_one` | Deleted %1$s | 已删除 %1$s | 已刪除 %1$s |
| 导出 | `action_export_list` | Export list | 导出清单 | 匯出清單 |
| 关闭 | `action_done` | Done | 完成 | 完成 |
| 失败 | `local_delete_failed`（改） | Delete failed | 删除失败 | 刪除失敗 |
| 目录不可用 | `local_delete_unavailable`（改） | Local folder unavailable | 本地目录不可用 | 本地目錄不可用 |

删掉现有 `local_delete_failed` 与 `local_delete_unavailable` 后半句解释。文件被占用、目录不可用这些情况，用户点「导出清单」能看到具体哪几个没删掉，弹窗里不必再讲一遍。

结果弹窗只有标题、可选一行数量、两个按钮。点弹窗外空白等同「完成」。

## 状态与数据

```kotlin
enum class Unit { CHAPTER, COLLECTION }

data class LocalDeletionEntry(
    val mangaTitle: String,
    val name: String,          // 篇目文件名；目录带尾斜杠
    val relativePath: String,  // 相对本地库根
    val fileCount: Int,        // 目录内文件数，单文件为 1
    val sizeBytes: Long,
    val outcome: Outcome,      // DELETED / PARTIAL / FAILED
)

data class LocalDeletionReport(
    val id: Long,              // 自增，供 Compose 去重
    val unit: Unit,
    val entries: List<LocalDeletionEntry>,
    val cancelled: Boolean,
    val skippedCount: Int,
    val deletedMangaIds: Set<Long>,
)
```

`LocalEntryDeletionService` 新增：

```kotlin
val progress: StateFlow<Progress?>
val lastReport: StateFlow<LocalDeletionReport?>
fun startDeleteChapters(targets: List<ChapterTarget>)
fun startDeleteMangas(entries: List<MangaEntry>)
fun requestStop()
fun clearReport()
```

条目粒度取顶层条目（篇目），不取文件。未打包的篇目是几百张图的目录，按文件记会产出几百行噪音；按篇目聚合成一行 `第05话/ (142 个文件, 38.2 MB)` 才能用。`sizeBytes` 在枚举阶段累加，不额外 stat。

`PARTIAL` 是必要状态：`deleteRecursively` 删到一半失败时，目录里一部分文件已经没了，标 `FAILED` 会让用户以为东西还在。

同时只跑一个任务。第二个 `start*` 直接返回，不排队。

## 不中断

删除执行移进 service 自建的 `CoroutineScope(SupervisorJob() + Dispatchers.IO)`，与 `MangaMarkStore`、`LocalChapterCoverManager` 同一写法。调用方不再 `await`，只发起；结果从 `lastReport` 观察。

旋转屏幕、退出页面、切后台都不影响删除。进程被杀仍会中断：已删文件不回滚，残留 DB 行由下次扫描清掉。

`lastReport` 非空即展示，关闭对话框才清空，所以旋转屏幕后结果窗会自然恢复，不需要额外的已读标记。

## 导出

CSV，UTF-8 + BOM，表头固定中文，与 `ChapterTitleTranslationCodec` 的 CSV 表头一致。

```csv
作品,章节,路径,大小,结果,时间
[社团] 作品名,第01话.cbz,作品名/第01话.cbz,11.9 MB,已删除,2026-10-18 00:08:53
[社团] 作品名,第05话/ (142 个文件),作品名/第05话/,38.2 MB,部分删除,2026-10-18 00:08:54
```

- 未处理条目只计数，不进清单：文件还在盘上，不需要去别处找。
- 文件名 `deleted-20261018-0008.csv`，ASCII，避开部分云盘的中文文件名乱码。
- 用 `ActivityResultContracts.CreateDocument("text/csv")`，项目已有 4 处先例。
- 报告只在内存，关闭弹窗即丢。导出成功或点「完成」都会清空。

## 改动面

- `data/local/LocalEntryDeletionService.kt`：进度、停止、报告、枚举累加、批量合集删除
- `data/local/LocalDeletionReport.kt`（新）：数据类与 CSV 编码
- `presentation/components/LocalDeletionHost.kt`（新）：进度窗、结果窗、单条 toast、导出 launcher
- `ui/main/MainActivity.kt`：根 `Box` 挂 host
- `MangaViewModel` / `BrowseSourceViewModel` / `ChapterFlagListScreen`：改为发起任务，删掉各自的 `deleteCompleted` 事件
- `MangaScreen` / `BrowseSourceScreen`：删掉结果 toast 与 snackbar，改由 host 统一；详情页按 `deletedMangaIds` 退出，来源页按报告刷新列表
- `i18n/**/strings.xml` ×3

顺带修 `BrowseSourceViewModel.kt:2242`：现在无条件把 `selected` 从 `favoriteIds` 移除，失败的作品也被移出「已在库」集合。

## 验证

`:app:compileViennaKotlin` + `:app:testDebugUnitTest`；设备装 `vienna` 后由用户操作：单条删一篇目看 toast 名字、多选删几部看进度与停止、停止后导出清单核对行数、删除过程中旋转屏幕确认不中断。
