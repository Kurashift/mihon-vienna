package tachiyomi.source.local.filter

import android.content.Context
import eu.kanade.tachiyomi.source.model.Filter
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

sealed class OrderBy(context: Context, selection: Selection) : Filter.Sort(
    context.stringResource(MR.strings.local_filter_order_by),
    arrayOf(context.stringResource(MR.strings.title), context.stringResource(MR.strings.date)),
    selection,
) {
    class Popular(context: Context, selection: Selection = Selection(0, true)) : OrderBy(context, selection)
    class Latest(context: Context, selection: Selection = Selection(1, false)) : OrderBy(context, selection)
}
