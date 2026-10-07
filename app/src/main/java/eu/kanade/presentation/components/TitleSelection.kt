package eu.kanade.presentation.components

import android.text.Selection
import android.text.Spannable
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/**
 * 标题选区的桥接层。原生 TextView 的选区由系统的 ActionMode 统一管理（点击外部、返回键、
 * 滚动都会由系统收掉），Compose 侧只需要知道「当前有没有选区」以及一个主动清除的入口。
 *
 * 由页面（如 MangaScreen / AudioDetailContent）持有并 remember，标题所在 item 被回收时通过
 * [unbindClearAction] 解绑，避免持有已经 detach 的 TextView。
 */
class TitleSelectionController internal constructor() {
    /** 是否有标题选区（ActionMode 是否挂着）。用于返回键拦截和点击抑制。 */
    var isActive by mutableStateOf(false)
        private set

    private var actionMode: ActionMode? = null
    private var clearAction: (() -> Unit)? = null

    internal fun setActive(active: Boolean) {
        isActive = active
    }

    internal fun bindActionMode(mode: ActionMode?) {
        actionMode = mode
    }

    internal fun bindClearAction(action: () -> Unit) {
        clearAction = action
    }

    internal fun unbindClearAction() {
        actionMode = null
        clearAction = null
    }

    // 标题 TextView 在窗口里的位置（boundsInWindow）。点外部拦截用：按下点落在标题内时
    // 交由 TextView 自己处理（拖手柄、点标题跳搜索），落在外面才清选区。初始 Zero 无所谓，
    // 因为要长按标题才可能激活选区，那时标题早已布局并上报过真实 rect。
    private var titleRect: Rect = Rect.Zero

    internal fun updateTitleRect(rect: Rect) {
        titleRect = rect
    }

    internal fun isOutsideTitle(position: Offset): Boolean = !titleRect.contains(position)

    /**
     * 主动收掉选区。优先 finish 掉 ActionMode——这才是彻底的收尾，选区高亮、手柄、
     * 工具栏会一起消失，和点外部/系统返回键的效果一致。没有 ActionMode 时（例如选区
     * 存在但工具栏还没起来）退化成直接清空 Selection。
     */
    fun clear() {
        val mode = actionMode
        if (mode != null) {
            mode.finish()
        } else {
            clearAction?.invoke()
        }
    }
}

internal fun TextView.clearTextSelection() {
    // setTextIsSelectable(true) 会把 buffer 类型切成 SPANNABLE，所以这里通常成立；
    // 万一不是 Spannable 就无从清除，直接跳过，系统后续仍会按常规流程收掉选区。
    val text = text as? Spannable ?: return
    Selection.setSelection(text, 0, 0)
}

internal fun TextView.selectedTextOrNull(): String? {
    val start = selectionStart
    val end = selectionEnd
    if (start < 0 || end < 0 || start == end) return null
    val text = text ?: return null
    return text.subSequence(minOf(start, end), maxOf(end, start)).toString()
}

/** 选区菜单里的一项：[id] 供系统菜单路由，[label] 显示在工具栏上，[onAction] 收到框选的文本。 */
internal class TitleSelectionAction(
    val id: Int,
    val label: String,
    val onAction: (selectedText: String) -> Unit,
)

/**
 * 标题选区的菜单：固定为调用方给定的几项，并尽量删掉系统补进来的项。
 *
 * 注意一个已经实测确认的边界：这个回调**无法保证**工具栏最终只剩这几项。悬浮工具栏每次显示
 * 时才会去读菜单，而 ROM 注入的 assist 项（网页搜索/翻译等）是在这之后异步加进来的，加完立刻
 * 触发工具栏刷新——我们只能在它加完之后动手，永远慢一拍。这里的删除只能保证我们的项一定在
 * 菜单里（且排在前面），剩下来的系统项由平台决定，删不掉就不要再跟它赛跑。
 */
internal class TitleSelectionCallback(
    private val textView: TextView,
    private val actions: List<TitleSelectionAction>,
    private val onActionModeCreated: (ActionMode) -> Unit,
    private val onActionModeDestroyed: () -> Unit,
    private val onSelectionActiveChange: (Boolean) -> Unit,
) : ActionMode.Callback {

    private val actionIds = actions.mapTo(hashSetOf()) { it.id }

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        onActionModeCreated(mode)
        onSelectionActiveChange(true)
        populateMenu(menu)
        return true
    }

    // 这里故意不重新 add：ActionMode 每次内容变化都会回调 onPrepareActionMode，重复
    // add 会让菜单在几项之间闪。删除系统项必须在这里做——onCreateActionMode 里
    // 的 menu.clear() 只清得掉那一刻的项，系统 Editor 之后补回来的（尤其是 assist/翻译）
    // 它管不到。
    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
        menu.removeForeignTitleActions()
        return true
    }

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        val selectedText = textView.selectedTextOrNull() ?: return false
        val action = actions.firstOrNull { it.id == item.itemId } ?: return false
        action.onAction(selectedText)
        mode.finish()
        return true
    }

    override fun onDestroyActionMode(mode: ActionMode) {
        onActionModeDestroyed()
        onSelectionActiveChange(false)
    }

    private fun populateMenu(menu: Menu) {
        menu.clear()
        // 工具栏只有调用方给定的几项：没有 NEVER 项就没有三点溢出菜单，系统注入项在
        // clear 时一起被清掉，prepare 阶段再兜底删一轮。
        actions.forEachIndexed { index, action ->
            menu.add(Menu.NONE, action.id, index, action.label)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
    }

    /**
     * 把非自建的系统项全部删掉。选区菜单会被系统 Editor 追加内容：全选、翻译、网页搜索
     * （放大镜）等——全选来自系统，翻译/网页搜索来自 ACTION_PROCESS_TEXT 或 ROM 注入，由
     * 手机上装的其它应用提供。
     */
    private fun Menu.removeForeignTitleActions() {
        for (index in size() - 1 downTo 0) {
            val item = getItem(index)
            if (item.itemId !in actionIds) {
                removeItem(item.itemId)
            }
        }
    }
}
