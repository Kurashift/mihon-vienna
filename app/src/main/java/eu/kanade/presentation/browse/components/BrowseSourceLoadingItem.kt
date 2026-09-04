package eu.kanade.presentation.browse.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Key prefix for the skeleton slots below the last loaded page. */
internal const val BROWSE_SOURCE_TRAILING_SLOT_KEY_PREFIX = "browse-source-trailing-slot-"

@Composable
internal fun BrowseSourceLoadingItem() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
    }
}

/**
 * One skeleton slot standing in for a page that has not loaded yet.
 *
 * The spinner rides on the first slot instead of taking a row of its own: a row that appears
 * and disappears on every page load changes the length of the list, and the scroller reads
 * that as the content moving underneath the thumb.
 */
@Composable
internal fun BrowseSourceTrailingSlot(
    showSpinner: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier) {
        content()
        if (showSpinner) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }
    }
}
