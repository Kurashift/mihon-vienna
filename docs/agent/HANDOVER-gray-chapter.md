# 交接文档：漫画详细页篇目“莫名变灰、撤不掉”问题

> 状态：**已结案 —— 不是 bug**（2026-08-31）。结论见第九节：变灰的那张封面「本来就是灰色图片」。
> 中间尝试过的修复已全部回滚，工作区已还原到排查前的状态。

---

## 一、用户报告的症状（原文转述）

- 漫画**详细页**（MangaScreen）中，某些篇目**莫名变灰**，但**撤不掉**（无法恢复成正常色）。
- 用户确认该篇目**并不是“已读完”状态**。
- 用户的猜测：“难道**标记**的时候，它也会变灰吗？”
- 触发时周围操作：“又看了一下**个人清单里的标记**，点进去再退出来”。
- 最初无法复现；后来用户在调试模式手机上复现，并给出了具体界面。

## 二、已确认的现场信息

- 设备：`NBEUWSNFWGGEEMSK`（Xiaomi 22041211AC / rubens），adb 已连接，screen 为 1080×2400。
- 应用：`app.mihon.dev`，versionName `2.1.9`，versionCode `184`，arm64-v8a。
- 应用**不是 debuggable**（`run-as app.mihon.dev` 报 “package not debuggable”），设备**未 root**（`su` 不可用）。
  → 因此**无法直接读 `/data/data/app.mihon.dev/databases/*.db`**。这是目前卡住取证的关键限制。
- 当前停留画面：本地图源漫画 **“Tatsukiti”** 详情页，共 **3 篇**：
  1. 坂柳NTR漫画 1 - 13（进度 **1/14**）
  2. 圣女NTR漫画（白圣女与黑牧师）（进度 **0/10**）
  3. 素晴NTR [种付大叔个人汉化]（进度 **0/32**）
- **用户明确指出**：变灰的是**第 1 篇「坂柳NTR漫画」**；第 2 篇「圣女NTR漫画」没有变灰。
  （辅助识图工具第一次误判成第 2 篇，已由用户纠正，勿再采信那次结论。）

## 三、已定位到的渲染逻辑（结论：灰 = read=true）

**文件：`app/src/main/java/eu/kanade/presentation/manga/components/MangaChapterListItem.kt`**

- 标题文字灰色：
  ```kotlin
  color = LocalContentColor.current.copy(alpha = if (read) DISABLED_ALPHA else 1f)
  ```
- 副标题/scanlator 灰色：`if (read) DISABLED_ALPHA else SECONDARY_ALPHA`
- 封面变淡：`alpha(if (read) 0.55f else 1f)`
- 已读图标：`read == true` 时在右侧显示 `CheckCircle`（filled）。
- `DISABLED_ALPHA = 0.38f`，`SECONDARY_ALPHA = 0.78f`
  （`presentation-core/.../components/material/Constants.kt`）

**其它标记均不会导致变灰：**
- “标记 / 标志”（`MangaMark`，Flag 图标）：只在右侧显示 🚩 图标，不改文字 alpha。
- “好本子”（`GoodDoujin`，心形图标）：只在右侧显示 ❤ 图标，不改文字 alpha。
- 下载态、选中态：只影响图标/背景高亮，不影响文字 alpha。

→ **结论：篇目变灰的唯一来源是 `chapter.read == true`。** 用户“标记导致变灰”的猜测，按现有渲染代码**不成立**；真正的嫌疑对象是 `read` 字段被错误置真。

## 四、关键矛盾（这正是 bug 的核心线索）

**文件：`app/src/main/java/eu/kanade/presentation/manga/ChapterProgressUi.kt`**

```kotlin
val displayedReadPages = if (read) {
    totalPages          // read=true → 显示满格 "totalPages/totalPages"
} else {
    lastPageRead.coerceIn(0L, (totalPages - 1L).coerceAtLeast(0L))
}
```

- 若 `read=true`，进度条应**满格**，文字应为 `14/14`。
- 但现场第 1 篇显示的是 **`1/14`**（即 `lastPageRead=1, totalPages=14, read=false`）。
- **同一份 Chapter 对象，不可能既 `read=true`（灰色）又 `read=false`（显示 1/14 半格进度）。**

→ 这强烈暗示：“变灰”与“进度显示”读到的 `read` 状态可能来自**不同来源**，或部分位置用了**内存里未持久化的 read 标记**。这正好符合“莫名变灰、撤不掉、但数据库里其实没读完”的主观感受。

## 五、像素级取证结果（含一次识图误判，以实测为准）

对截图（`adb shell screencap -p /sdcard/... && adb pull`，注意 `adb exec-out screencap -p` 在 Windows PowerShell 下会因 CRLF 损坏 PNG，勿用）逐行采样：

- 应用为**浅色主题**，背景 RGB ≈ (230,228,239) / (251,248,255)。
- 三篇标题文字均含**近纯黑像素（minLum≈28）**：
  - 第 1 篇 坂柳：median≈28（笔画密集，纯黑）
  - 第 2 篇 圣女：median≈76
  - 第 3 篇 素晴：median≈76
- 若标题真是 `read` 灰色（α=0.38 叠白底），最暗像素应约为 luminance 168，**不会出现黑像素**。
  → 因此**标题文字实测并没有被置灰**。
- 封面：第 1 篇 坂柳 avgLum≈219、avgSat≈0.046（几乎无彩、发白）；第 2/3 篇 avgLum≈154/178、avgSat≈0.21，明显更饱满更暗。
  → “发灰”的观感很可能落在**封面/整行**上，而非标题文字。

⚠️ 注意：第 1 篇封面发白**也可能是封面图本身如此**（本地篇目封面由 `LocalChapterCover` 从文件第一页生成，白/浅色第一页、或封面生成失败会用浅灰占位）。**结论尚未最终定格，不要据此直接下“read 置灰”的定论。**

## 六、仍未验证 / 待办（接手者建议按顺序做）

1. **拿到权威数据（最高优先级）**：确认第 1 篇「坂柳」在数据库里的 `read` / `last_page_read` / `total_pages` 真实值。
   - 方案 A（推荐，改动最小）：构建 debuggable 的 vienna 包并**原位覆盖安装（不清数据，App ID 仍是 `app.mihon.dev`）**，然后用 `run-as app.mihon.dev` 读 DB。
     - 必须遵守 AGENTS.md：仅此诊断用途临时允许 debug；检查完删除临时插桩/调试分支，正式交付不保留 `isDebuggable=true`。
   - 方案 B：`adb backup` 提取（先确认 `android:allowBackup` 是否允许；包信息 dump 中未输出 allowBackup，需去 manifest 确认）。
   - 方案 C：用户协助，长按该篇目看底部菜单显示“标记已读”还是“标记未读”，据此反推内存中的 `read`。
   - 方案 D：logcat 抓取（下面第 3 条）。
2. **核实“标记清单 → 点进去 → 退出来”这条链路是否会误写 read：**
   - 入口：`app/src/main/java/eu/kanade/presentation/more/settings/screen/advanced/ChapterFlagListScreen.kt`
   - 该屏 `DUPLICATES` 类型即“个人清单里的标记”（`MangaMarkStore` + Flag 图标）。
   - `openManga()` 只 `navigator.push(MangaScreen(mangaId))`；`openChapterReader()` 只 `startActivity(ReaderActivity...)`。
   - 初步看**此链路本身不碰 read**，需结合第 1 步 DB 数据确认是否有其它隐藏路径（tracker 同步 `SyncChapterProgressWithTrack`、reader 完成策略 `ChapterReadingSession` 等）。
3. **抓 logcat**（设备还在现场时最有效）：
   - `adb logcat -d | findstr /i "mihon"` 或按 tag 过滤，找 mark-as-read / chapter update 相关日志。
4. **精确确认“灰”到底出现在哪**：截图后对**封面区域 + 右侧图标轨道**做更细采样；或请用户明确指出是“文字变灰”还是“整行/封面变灰”，避免再次在语义上拉扯。
5. 若第 1 篇 DB 里 `read=false`：问题在 **UI/ViewModel 内存态**，重点查 `MangaViewModel` 的 `processedChapters` / `ChapterList.Item` 装配，以及是否有地方 `copy(read=true)` 未持久化。
6. 若第 1 篇 DB 里 `read=true`：问题在**数据写入**，重点查是谁在用户只“点进去再退出来”时把它写成已读（reader 退出、tracker 同步、批量标记 etc.）。

## 七、本次诊断过程中的副作用（已发生，注意别误判）

- 我用 `adb shell input swipe x y x y 900` 做“长按”取证时：
  - 第一次长按**封面** → 打开了封面弹窗（主封面/篇目封面/复制/分享/保存），已按返回关闭。
  - 第二次长按行 → 误触发了**进阅读器**（曾短暂显示页面计数器 “3 / 14”），随后已按返回回到详情页。
- **不要把这些临时打开弹窗/阅读器的行为当作 bug 线索。** 当前设备已回到漫画详情页。

## 八、环境与命令速查

- 仓库根目录：`D:\DATA\mihon`；分支 `main`；vienna 变体，App ID `app.mihon.dev`。
- 截图安全姿势：
  ```powershell
  adb shell screencap -p /sdcard/x.png
  adb pull /sdcard/x.png "$env:TEMP\x.png"
  ```
- UI 层级：
  ```powershell
  adb shell uiautomator dump /sdcard/ui.xml
  adb shell cat /sdcard/ui.xml
  ```
- 像素采样（PowerShell，浅色主题下文字黑像素 lum≈28）：
  用 `System.Drawing.Bitmap` + `GetPixel`；判定 read 置灰的标准 = 标题无 lum<140 的像素。

---

## 九、结论：不是 bug，是封面图片本身就是灰的（接手者直接看这里）

### 最终判定

用户现场确认：**变灰的那张封面「本来就是灰色图片」**。与 `read` 状态无关，不是代码缺陷。

**排查过程中改的代码已全部回滚**，工作区还原到排查前状态：
- `ReaderProgressSession.kt` / `ReaderProgressSessionTest.kt` —— `git checkout` 还原
- `ReaderViewModel.kt` —— 只还原我加的那一处，用户原有的 WIP 改动保留未动

若日后有人想再处理"退出阅读器误判已读"，见下面第 3 点（那是真实存在但触发条件很窄的缺陷，
与本次报告无关，未修复）。

### 支撑证据（2026-08-31 设备实测，Tatsukiti 详情页）

| 篇目 | 进度 | 标题最暗像素 | 右侧已读打勾 | 封面平均彩度 |
|---|---|---|---|---|
| 坂柳NTR漫画 | 1/14 | 28（纯黑） | 无 | 27.4 |
| 圣女NTR漫画 | 0/10 | 28（纯黑） | 无 | 22.1 |
| 素晴NTR | 1/32 | 28（纯黑） | 无 | 30.2 |

- 浅色主题背景亮度 246–250。若标题真被 `read` 置灰（alpha .38 叠浅底），最暗像素应 ≥150，
  实测 28 = 纯黑，**未置灰**。
- 右侧图标轨道彩色像素数 **0**，即一个已读打勾都没有 → `read` 全为 false。
- 封面彩度 22–30 是真实图片，不是 `surfaceContainerHighest` 占位灰块。

→ 该页面当前无任何变灰迹象，用户此前看到的现象不可复现。

### 排查中走过的弯路（留给后来者，别再踩）

1. **第四节"灰与进度来自不同数据源"的怀疑不成立。**
   `MangaScreen.kt:1119-1120` 的 `isRead` 与 `chapterProgress` 取自同一个 `item.chapter`，
   不可能不一致。
2. **"如果 read=1，进度会显示满格"这点是对的**，但不能反推"看着没读完就等于没被标已读"
   —— 用户的"没读完"是主观判断，可能只是封面观感。
3. **真实存在但与本次无关的一个缺陷**（未修复）：
   `ChapterReadingSession.canComplete()` 的尾部宽容规则
   （`pageIndex >= tailCompletionStartIndex(totalPages)`，即"最后三页 或 80% 处"取较大者）
   只看退出时停留的页，不看本次是否真的翻页。配合 `chapters.sq:230` 的单向闩锁
   （`read = CASE WHEN read = 1 OR :completed = 1 THEN 1 ELSE 0 END`）会表现为"撤不掉"。
   触发条件很窄：需先存在 `read=0` 且 `lastPageRead` 接近 `totalPages` 的残留状态
   （读到末尾附近时进程被杀，或老数据 `total_pages` 原为 0 后补写）。
   **正常读到第 12/14 页退出，当场就已判已读，不会留下这种状态。**
   修复方向（若要做）：在 `ReaderProgressSession` 记录每次进入章节的起始页，
   只有本次向前翻过页才允许 `flushChapterProgress()` 以 `completingOnExit = true` 落库。

### 教训

**在拿到确凿证据前，不要把"代码上说得通的机制"当成用户报告的答案。**
本次我在没有复现、没有 DB 数据、像素取证结论自相矛盾（当时误判成第 2 篇）的情况下，
就动手改了阅读器逻辑并出包安装。
正确顺序应是：先用像素/UI 层级确认"到底哪个元素灰了、灰到什么程度"，
再决定是看渲染还是看数据。

---

## 一句话总结给下一位接手者

**这不是 bug。** 变灰的那张封面本来就是灰色图片，用户已现场确认。
排查中的代码改动已全部回滚，工作区干净（只剩用户自己原有的 WIP）。

渲染层面的结论仍然有效且有用：篇目变灰的唯一来源是 `chapter.read == true`，
标记 / 好本子 / 下载态都不会让文字或封面变灰。下次再有人报“莫名变灰”，
**先用像素取证确认到底哪个元素灰了、灰到什么程度**，再决定查渲染还是查数据，
不要像本次一样，在没复现、没 DB 数据的情况下就动手改阅读器逻辑并出包。

另：文档里记录了一个真实但触发条件很窄的缺陷（退出阅读器时的尾部宽容完成不校验是否翻页），
与本次报告无关，未修复，见第九节“排查中走过的弯路”第 3 点。
