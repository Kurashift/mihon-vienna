package eu.kanade.presentation.library.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.Badge
import tachiyomi.presentation.core.i18n.stringResource

@Composable
internal fun DownloadsBadge(count: Int) {
    if (count > 0) {
        Badge(
            text = "$count",
            color = MaterialTheme.colorScheme.tertiary,
            textColor = MaterialTheme.colorScheme.onTertiary,
        )
    }
}

@Composable
internal fun UnreadBadge(count: Long) {
    if (count > 0) {
        Badge(text = "$count")
    }
}

@Composable
internal fun IndexLabel(
    index: Int,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.labelSmall.copy(
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    ),
    background: Color? = null,
) {
    if (background == null) {
        Text(
            text = index.toString(),
            modifier = modifier,
            style = style,
            maxLines = 1,
        )
        return
    }
    Box(
        modifier = modifier
            .clip(RectangleShape)
            .background(background)
            .padding(horizontal = 3.dp, vertical = 1.dp),
    ) {
        Text(
            text = index.toString(),
            style = style.copy(fontWeight = FontWeight.SemiBold, fontSize = 10.sp),
            maxLines = 1,
        )
    }
}

@Composable
internal fun LastReadBadge() {
    Box(
        modifier = Modifier
            .clip(RectangleShape)
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 3.dp, vertical = 1.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.History,
            contentDescription = stringResource(MR.strings.label_last_read),
            modifier = Modifier.size(12.dp),
            tint = Color.White,
        )
    }
}

@Composable
internal fun ProgressBadge(
    finishedCount: Long,
    totalChapters: Long,
    filled: Boolean = true,
) {
    if (totalChapters > 0) {
        val text = "$finishedCount/$totalChapters"
        if (filled) {
            // Corner badge on covers: dark rectangular chip, flush with the edge.
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                ),
                maxLines = 1,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        } else {
            // Plain secondary text for list rows.
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun ReadStatusBadge(
    hasFinished: Boolean,
) {
    if (hasFinished) {
        Badge(
            imageVector = Icons.Outlined.Done,
            text = stringResource(MR.strings.label_finished),
        )
    }
}

@Composable
internal fun LanguageBadge(
    isLocal: Boolean,
    sourceLanguage: String,
) {
    if (isLocal) {
        Badge(
            imageVector = Icons.Outlined.Folder,
            color = MaterialTheme.colorScheme.tertiary,
            iconColor = MaterialTheme.colorScheme.onTertiary,
        )
    } else if (sourceLanguage.isNotEmpty()) {
        Badge(
            text = sourceLanguage.uppercase(),
            color = MaterialTheme.colorScheme.tertiary,
            textColor = MaterialTheme.colorScheme.onTertiary,
        )
    }
}

@PreviewLightDark
@Composable
private fun BadgePreview() {
    TachiyomiPreviewTheme {
        Column {
            DownloadsBadge(count = 10)
            UnreadBadge(count = 10)
            ProgressBadge(finishedCount = 3, totalChapters = 12)
            ReadStatusBadge(hasFinished = true)
            LanguageBadge(isLocal = true, sourceLanguage = "EN")
            LanguageBadge(isLocal = false, sourceLanguage = "EN")
        }
    }
}
