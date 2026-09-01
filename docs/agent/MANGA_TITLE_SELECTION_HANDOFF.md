# 交接文档：漫画详情页大标题选字与震动

> 编写时间：2026-08-31
> 最后代码状态：工作区未提交，`app.module` 无提交。
> 本文档用于记录已完成修复、未解决问题、调查结论和候选方案，供后续接手者快速理解，避免重复踩坑。

---

## 1. 一句话总结

漫画详情页大标题的**长按震动已修复并真机验证通过**；但 **Compose `SelectionContainer` 拖动选区手柄的手感问题未解决，属于库内行为，上层难以定向修复**。经讨论，用户决定暂不继续修，先交接。

---

## 2. 当前代码状态

- 工作区存在**大量未提交改动**（85+ 个文件，包括音频、阅读器、本地源、Compose 迁移等），本文只记录 `MangaInfoHeader.kt` 相关的改动。
- 标题选择当前使用 **Compose `SelectionContainer`**（不是原生 `AndroidView`）。
- 已保留的最终改动：
  - `app/src/main/java/eu/kanade/presentation/manga/components/MangaInfoHeader.kt`
  - 新增长按震动：在 `SelectionContainer` 的 `Modifier` 上添加 `pointerInput`，通过 `awaitEachGesture` 检测系统长按超时，在长按识别点发一次 `HapticFeedbackType.LongPress`，不消费事件。
  - 保留了以下 imports：
    - `android.view.ViewConfiguration`
    - `androidx.compose.foundation.gestures.awaitEachGesture`
    - `androidx.compose.foundation.gestures.awaitFirstDown`
    - `androidx.compose.foundation.gestures.waitForUpOrCancellation`
    - `androidx.compose.ui.hapticfeedback.HapticFeedbackType`
    - `androidx.compose.ui.input.pointer.pointerInput`
    - `androidx.compose.ui.platform.LocalHapticFeedback`
    - `kotlinx.coroutines.withTimeoutOrNull`
  - 已删除/清理：
    - 原 `proxyHaptic`（自定义 `HapticFeedback` 对象）
    - `HAPTIC_THROTTLE_MS` 常量
    - `HapticFeedback` 接口 import
    - 上一版 `LaunchedEffect(selectionState.selectedTexts.isEmpty())` 补发方案
    - `wrapContentWidth` 实验（已回退，标题 `SelectionContainer` 仍为 `fillMaxWidth()`）

---

## 3. 已完成：长按震动修复

### 3.1 用户反馈的初始问题

漫画详情页大标题能长按选中并弹出复制工具栏，但长按后**偶尔没有震动反馈**。

### 3.2 根因（已通过 Compose 源码反编译 + 真机日志确认）

- Compose foundation 1.12 的 `SelectionManager.updateSelection()` 中，震动请求（`TextHandleMove`）在 `setSubselections` / `onSelectionChange` 写入 `SelectionState` **之前**发出。
- 因此长按选中第一下时，`selectionState.selectedTexts` 仍为空；原代码 `proxyHaptic` 用 `selectedTexts.isEmpty()` 拦截，会把长按第一下吞掉，造成"偶尔不震"。
- 真机 logcat 还显示：Compose 长按识别时发的是 **TextHandleMove（很轻）**，而上一版等 `selectedTexts` 变非空再补发 `LongPress` 时已到**松手之后**，体感同样几乎无感。

### 3.3 最终修复方案

在 `SelectionContainer` 外层加 `pointerInput`，在**系统长按超时那一刻**自己发 `LongPress`，不消费事件，选字仍交由 Compose 完成。

核心代码（位于 `MangaInfoHeader.kt` 的 `MangaContentInfo` 内）：

```kotlin
val hapticFeedback = LocalHapticFeedback.current
val longPressTimeoutMillis = ViewConfiguration.getLongPressTimeout().toLong()

SelectionContainer(
    modifier = Modifier
        .fillMaxWidth()
        .onGloballyPositioned { onTitleRectChange(it.boundsInWindow()) }
        .pointerInput(hapticFeedback, longPressTimeoutMillis) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                val upBeforeTimeout = withTimeoutOrNull(longPressTimeoutMillis) {
                    waitForUpOrCancellation()
                    true
                }
                if (upBeforeTimeout == null) {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    waitForUpOrCancellation()
                }
            }
        }
        ...
)
```

### 3.4 验证结果

- 真机：Redmi 22041211AC / Android 14 / SDK 34
- 编译：`:app:assembleVienna` BUILD SUCCESSFUL
- 安装：`app-arm64-v8a-vienna.apk` 覆盖安装成功
- 用户实测：**长按震动稳定，选字/复制栏正常，满意**
- logcat 证据：每次长按识别点均出现 `HapticFeedbackUtil: 0,1,true`（MIUI LongPress effect id 0），马达实际执行 `playLengthMs 70`

---

## 4. 未解决：拖动选区手柄手感差

### 4.1 现象（用户反馈）

单行标题（或短标题）下：

- 长按选中后，拖动系统自带的小圆点（selection handles），**稍微往下移一点就不再正常向前选**，或者直接全选；
- 往上拖一点会反选；
- 斜向拖动会**干扰水平选取**，体验远不如原生 Android `TextView`。

### 4.2 根因（初步确认）

- Compose `SelectionContainer` 的 handle 拖动使用 `SelectionAdjustment.CharacterWithWordAccelerate`。
- `TextLayoutResult` 把手指的二维坐标直接映射成文本 offset：当手指移出当前文字行的高范围（哪怕只下移 1px），会映射为该行行首/行尾。
- 单行标题因此表现为"下移一点 = 全选 / 上移一点 = 反选"；多行/双行也需要按行做特殊处理。
- 这是 Compose foundation 库内实现，**公开 API 没有直接配置项**。

### 4.3 已尝试且已回退的实验

| 实验                                                                | 结果                        |
| ----------------------------------------------------------------- | ------------------------- |
| 把 `SelectionContainer` 的 `fillMaxWidth()` 改为 `wrapContentWidth()` | 未改善，已回退为 `fillMaxWidth()` |

---

## 5. 曾调查的迁移原因（避免回退时踩旧坑）

从当前工作区代码注释还原，标题从原生 `AndroidView` + `TextView` 迁移到 Compose `SelectionContainer` 的动机：

1. **短标题点击区域**
   - 原生 `TextView` 宽度撑满整行时，右侧空白也会算进点击区，点空白会误触发源搜索。
   - Compose 用 `wrapContentWidth` 精确控制点击区。
2. **取消选区行为**
   - 原生 `TextView` 的选区生命周期与页面滚动、返回键、点外部取消联动不可靠。
   - Compose 通过外部持有 `SelectionState` + `onTitleRectChange` + `BackHandler` 实现精确取消。
3. **ActionMode 菜单闪烁/重复**
   - 原生 `TextView` 自定义 `customSelectionActionModeCallback` 时，菜单容易闪成两项。
   - Compose 用 `filterTextContextMenuComponents` + `appendTextContextMenuComponents` 固定成三项。
4. **视觉一致性**
   - 迁移时保持无波纹等原生外观。

---

## 6. 候选方案（后续可做）

### 方案 A：回退标题为原生 `AndroidView` + `TextView`（手感最好，但局部混用）

- 优点：获得 Android 标准文本选择体验（斜拖水平选取、多行正常换行）。
- 代价：`MangaInfoHeader` 标题局部出现 `AndroidView`，需要解决旧坑：
  - 短标题空白误触发搜索 → `wrap_content` 宽度；
  - 菜单闪烁 → 恢复 `MangaTitleSelectionCallback` 一次 populate；
  - 点标题外/返回取消选区 → 通过 `ActionMode.Callback.onDestroyActionMode` 回传状态到 `MangaScreen`；
  - 长按震动 → 原生 TextView 自带，若不稳再按现有 `pointerInput` 方案补。
- 风险：涉及 `MangaScreen` 的 `SelectionState` 逻辑调整；回退方向与当前大规模 Compose 迁移相反。

### 方案 B：继续在 Compose 内深挖（维护性差）

- 尝试通过 `SelectionContainer` 的 `onSelectionManagerCreated` 重载拿到内部 `SelectionManager`，再用反射/受控方式替换拖动逻辑。
- 风险：黑科技、Compose 版本升级易碎、维护成本高、不一定成功。

### 方案 C：接受现状（已知限制）

- 保留当前已修好的震动；拖动手感差作为已知问题记录。
- 适合"先不做大改，等后续更合适时机再处理"。

---

## 7. 影响面 / 涉及文件

- `app/src/main/java/eu/kanade/presentation/manga/components/MangaInfoHeader.kt`
  - 标题选区、长按震动、菜单、`SelectionContainer` 都在此。
- `app/src/main/java/eu/kanade/presentation/manga/MangaScreen.kt`
  - `titleSelection` / `rememberSelectionState()` / `BackHandler` / `onTitleRectChange` 逻辑，与标题选区联动。
- 若方案 A，还需重新检查：
  - `MangaScreen.kt` 中 `hasTitleSelection`、`titleSelection.clear()` 的桥接；
  - `MangaInfoHeader.kt` 中 `MangaTitleSelectionCallback`（旧版在 git diff 中被删除，可参考）。

---

## 8. 验证记录

| 项目      | 结果                                                                 |
| ------- | ------------------------------------------------------------------ |
| 真机型号    | Redmi 22041211AC (rubens)                                          |
| Android | 14 (SDK 34)                                                        |
| 构建命令    | `.\gradlew.bat :app:assembleVienna --console=plain`                |
| 构建结果    | BUILD SUCCESSFUL                                                   |
| APK     | `app/build/outputs/apk/vienna/app-arm64-v8a-vienna.apk`            |
| 安装      | `adb install -r` 覆盖升级，数据未动                                         |
| 用户验收    | 震动 OK；拖动手感仍差（未解决）                                                  |
| logcat  | 长按识别点出现 `HapticFeedbackUtil: 0,1,true`，`Vibrator perform effect 0` |

---

## 9. 交接注意事项

- **不要直接 git revert / 回退整个工作区**：当前有大量未提交改动（85+ 文件），本文只覆盖 `MangaInfoHeader.kt` 相关。
- **不要擅自提交/推送**：除非用户明确要求。
- **`MangaInfoHeader.kt` 当前是 Compose `SelectionContainer` + `pointerInput` 震动方案**，`wrapContentWidth` 实验已回退。
- **如果后续做方案 A**，先备份当前 `MangaInfoHeader.kt` 与 `MangaScreen.kt` 相关逻辑，再逐步改，避免破坏已有震动和选区联动。
- **真机验证是必须的**：仅编译/单测不足以确认此类交互问题。

---

## 10. 下一步建议（按优先级）

1. 用户当前决定：**先不继续修拖动手感**，交接即可。
2. 若后续用户再次反馈手感问题，建议优先评估 **方案 A（原生 TextView 局部回退）**，并提前列出旧坑的具体修复点。
3. 若选择方案 B，需先评估 Compose 版本升级风险和是否值得。
