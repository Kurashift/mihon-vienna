package eu.kanade.tachiyomi.ui.local

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.local.LocalChapterTransferJob
import eu.kanade.tachiyomi.data.local.LocalChapterTransferService
import kotlinx.coroutines.launch
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.source.local.LocalSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import eu.kanade.presentation.manga.components.MangaCover as MangaCoverView

private enum class ImportTargetMode {
    EXISTING,
    NEW,
}

@Composable
private fun SourceButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    Button(
        modifier = modifier,
        onClick = onClick,
        enabled = enabled,
    ) {
        Icon(icon, contentDescription = null)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}

data class LocalImportScreen(
    private val fixedTargetMangaId: Long? = null,
) : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val mangaRepository = remember { Injekt.get<MangaRepository>() }
        val transferService = remember { Injekt.get<LocalChapterTransferService>() }
        val networkToLocal = remember { Injekt.get<NetworkToLocalManga>() }
        var selectedUris by remember { mutableStateOf<List<android.net.Uri>>(emptyList()) }
        var sourcePreviews by remember { mutableStateOf<List<LocalChapterTransferService.SourcePreview>>(emptyList()) }
        var ignoredSourceCount by remember { mutableLongStateOf(0L) }
        var targetId by remember { mutableLongStateOf(fixedTargetMangaId ?: -1L) }
        var mangas by remember { mutableStateOf<List<Manga>>(emptyList()) }
        var newTitle by remember { mutableStateOf("") }
        var targetMode by remember {
            mutableStateOf(if (fixedTargetMangaId != null) ImportTargetMode.EXISTING else ImportTargetMode.NEW)
        }
        var output by remember { mutableStateOf(LocalChapterTransferService.FolderOutput.DIRECTORY) }
        var deleteSource by remember { mutableStateOf(false) }
        var importing by remember { mutableStateOf(false) }
        var conflictPreview by remember { mutableStateOf<LocalChapterTransferService.ImportPreview?>(null) }
        var pendingTargetId by remember { mutableLongStateOf(-1L) }
        val transferStatus by remember(context) { LocalChapterTransferJob.statusFlow(context) }
            .collectAsStateWithLifecycle(initialValue = null)

        fun addSelectedUris(uris: List<android.net.Uri>) {
            scope.launch {
                val knownUris = sourcePreviews.mapTo(hashSetOf()) { it.uri }
                val uniqueUris = uris.distinct().filterNot { it in knownUris }
                uniqueUris.forEach { uri ->
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                }
                val previews = uniqueUris.mapNotNull { transferService.inspectSource(it) }
                ignoredSourceCount += (uniqueUris.size - previews.size)
                val mergedPreviews = (sourcePreviews + previews).distinctBy { it.uri }
                sourcePreviews = mergedPreviews
                selectedUris = mergedPreviews.map { it.uri }
            }
        }

        LaunchedEffect(Unit) {
            val localIds = mangaRepository.getLocalMangaIds()
            val nonEmptyIds = mangaRepository.getMangaProgressBySource(LocalSource.ID)
                .mapTo(hashSetOf()) { it.mangaId }
            mangas = localIds.filter { it in nonEmptyIds }.mapNotNull { id ->
                runCatching { mangaRepository.getMangaById(id) }.getOrNull()
            }.sortedBy { it.title.lowercase() }
            if (targetId < 0 && mangas.size == 1) targetId = mangas.first().id
        }
        // OpenDocument grants persistable read access when the provider supports it, which lets
        // WorkManager continue a long import after the screen or app process is recreated.
        val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
            addSelectedUris(it)
        }
        val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
            it?.let { uri -> addSelectedUris(listOf(uri)) }
        }

        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(MR.strings.action_import_local_chapters),
                    navigateUp = navigator::pop,
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    ImportSection(title = "添加来源") {
                        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                            if (maxWidth >= 520.dp) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    SourceButton(
                                        modifier = Modifier.weight(1f),
                                        icon = Icons.Outlined.FolderOpen,
                                        label = "从文件夹导入本子",
                                        enabled = !importing,
                                        onClick = { folderPicker.launch(null) },
                                    )
                                    SourceButton(
                                        modifier = Modifier.weight(1f),
                                        icon = Icons.Outlined.InsertDriveFile,
                                        label = "导入单本本子压缩包",
                                        enabled = !importing,
                                        onClick = { filePicker.launch(arrayOf("*/*")) },
                                    )
                                }
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SourceButton(
                                        icon = Icons.Outlined.FolderOpen,
                                        label = "从文件夹导入本子",
                                        enabled = !importing,
                                        onClick = { folderPicker.launch(null) },
                                    )
                                    SourceButton(
                                        icon = Icons.Outlined.InsertDriveFile,
                                        label = "导入单本本子压缩包",
                                        enabled = !importing,
                                        onClick = { filePicker.launch(arrayOf("*/*")) },
                                    )
                                }
                            }
                        }
                        if (selectedUris.isEmpty()) {
                            Text(
                                text = "可添加多个来源；只会接收图片目录或可识别的单本归档",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        } else {
                            Text(
                                text = "已添加 ${sourcePreviews.size} 个来源，共 ${sourcePreviews.sumOf {
                                    it.candidateNames.size
                                }} 个本子",
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            sourcePreviews.forEach { preview ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(preview.displayName, maxLines = 1)
                                        Text(
                                            "包含 ${preview.candidateNames.size} 个本子：${preview.candidateNames.take(
                                                3,
                                            ).joinToString("、")}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            val remaining = sourcePreviews.filterNot { it.uri == preview.uri }
                                            sourcePreviews = remaining
                                            selectedUris = remaining.map { it.uri }
                                        },
                                        enabled = !importing,
                                    ) {
                                        Icon(Icons.Outlined.Close, contentDescription = "移除")
                                    }
                                }
                            }
                            if (ignoredSourceCount > 0) {
                                Text(
                                    text = "已忽略 $ignoredSourceCount 个不包含本子内容的文件或文件夹",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                item {
                    ImportSection(title = "归属合集") {
                        if (fixedTargetMangaId == null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = targetMode == ImportTargetMode.NEW,
                                    onClick = { targetMode = ImportTargetMode.NEW },
                                    label = { Text("新建合集") },
                                )
                                FilterChip(
                                    selected = targetMode == ImportTargetMode.EXISTING,
                                    onClick = {
                                        targetMode = ImportTargetMode.EXISTING
                                        navigator.push(
                                            LocalMangaPickerScreen(
                                                mangas = mangas,
                                                selectedMangaId = targetId,
                                                onSelected = {
                                                    targetId = it
                                                    targetMode = ImportTargetMode.EXISTING
                                                },
                                            ),
                                        )
                                    },
                                    label = { Text("已有合集") },
                                )
                            }
                            if (targetMode == ImportTargetMode.EXISTING) {
                                val selectedManga = mangas.firstOrNull { it.id == targetId }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (selectedManga != null) {
                                        MangaCoverView.Book(
                                            modifier = Modifier
                                                .padding(end = 10.dp)
                                                .fillMaxWidth(0.16f),
                                            data = tachiyomi.domain.manga.model.MangaCover(
                                                mangaId = selectedManga.id,
                                                sourceId = selectedManga.source,
                                                isMangaFavorite = selectedManga.favorite,
                                                url = selectedManga.thumbnailUrl,
                                                lastModified = selectedManga.coverLastModified,
                                            ),
                                        )
                                        Text(
                                            text = selectedManga.title,
                                            modifier = Modifier.weight(1f),
                                            style = MaterialTheme.typography.bodyLarge,
                                            maxLines = 2,
                                        )
                                    } else {
                                        Text(
                                            text = "尚未选择合集",
                                            modifier = Modifier.weight(1f),
                                            style = MaterialTheme.typography.bodyLarge,
                                        )
                                    }
                                    TextButton(
                                        onClick = {
                                            navigator.push(
                                                LocalMangaPickerScreen(mangas) {
                                                    targetId = it
                                                    targetMode = ImportTargetMode.EXISTING
                                                },
                                            )
                                        },
                                    ) { Text("选择") }
                                }
                            } else {
                                OutlinedTextField(
                                    value = newTitle,
                                    onValueChange = { newTitle = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("新建合集名称") },
                                    supportingText = { Text("已选合集和名称输入会保留，切换模式不会丢失") },
                                    singleLine = true,
                                )
                            }
                        } else {
                            Text(
                                text = "当前合集：${mangas.firstOrNull { it.id == fixedTargetMangaId }?.title.orEmpty()}",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
                item {
                    ImportSection(title = "导入设置") {
                        Text(
                            "单本压缩包保持原格式；目录来源可选文件夹或 CBZ。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = output == LocalChapterTransferService.FolderOutput.DIRECTORY,
                                onClick = { output = LocalChapterTransferService.FolderOutput.DIRECTORY },
                            )
                            Text("文件夹")
                            RadioButton(
                                selected = output == LocalChapterTransferService.FolderOutput.CBZ,
                                onClick = { output = LocalChapterTransferService.FolderOutput.CBZ },
                            )
                            Text("CBZ")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = deleteSource, onCheckedChange = { deleteSource = it })
                            Column(modifier = Modifier.padding(start = 8.dp)) {
                                Text("成功后删除源文件")
                            }
                        }
                    }
                }
                transferStatus?.takeUnless { it.state.isFinished }?.let { status ->
                    item {
                        ImportSection(title = "导入进度") {
                            LinearProgressIndicator(
                                progress = {
                                    if (status.totalBytes > 0) {
                                        status.copiedBytes.toFloat() / status.totalBytes
                                    } else if (status.total > 0) {
                                        status.completed.toFloat() / status.total
                                    } else {
                                        0f
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text("${status.completed}/${status.total}：${status.currentName}")
                        }
                    }
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = navigator::pop, enabled = !importing) { Text("取消") }
                        Button(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                scope.launch {
                                    importing = true
                                    val resolvedTarget = if (fixedTargetMangaId !=
                                        null
                                    ) {
                                        fixedTargetMangaId
                                    } else if (targetMode == ImportTargetMode.EXISTING &&
                                        targetId >= 0
                                    ) {
                                        targetId
                                    } else if (targetMode == ImportTargetMode.NEW && newTitle.isNotBlank()) {
                                        val safeTitle = newTitle.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
                                        networkToLocal(
                                            Manga.create().copy(
                                                source = LocalSource.ID,
                                                url = safeTitle,
                                                title = safeTitle,
                                            ),
                                        ).id
                                    } else {
                                        -1L
                                    }
                                    if (resolvedTarget >= 0 && selectedUris.isNotEmpty()) {
                                        val preview = runCatching {
                                            transferService.previewImport(selectedUris, resolvedTarget)
                                        }.getOrNull()
                                        if (preview == null) {
                                            importing = false
                                        } else if (preview.conflicts.isNotEmpty()) {
                                            pendingTargetId = resolvedTarget
                                            conflictPreview = preview
                                        } else if (LocalChapterTransferJob.start(
                                                context = context,
                                                uris = selectedUris,
                                                targetMangaId = resolvedTarget,
                                                options = LocalChapterTransferService.Options(output, deleteSource),
                                            )
                                        ) {
                                            importing = false
                                            navigator.pop()
                                        } else {
                                            importing = false
                                        }
                                    } else {
                                        importing = false
                                    }
                                }
                            },
                            enabled = !importing && selectedUris.isNotEmpty() &&
                                (
                                    fixedTargetMangaId != null ||
                                        (targetMode == ImportTargetMode.EXISTING && targetId >= 0) ||
                                        (targetMode == ImportTargetMode.NEW && newTitle.isNotBlank())
                                    ),
                        ) { Text("开始导入") }
                    }
                }
            }
        }
        conflictPreview?.let { preview ->
            AlertDialog(
                onDismissRequest = {
                    conflictPreview = null
                    importing = false
                },
                title = { Text(stringResource(MR.strings.local_transfer_conflict_title)) },
                text = {
                    Text(
                        stringResource(
                            MR.strings.local_transfer_conflict_message,
                            preview.conflicts.size,
                        ),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val target = pendingTargetId
                            if (target >= 0 && LocalChapterTransferJob.start(
                                    context = context,
                                    uris = selectedUris,
                                    targetMangaId = target,
                                    options = LocalChapterTransferService.Options(output, deleteSource),
                                )
                            ) {
                                conflictPreview = null
                                pendingTargetId = -1L
                                importing = false
                                navigator.pop()
                            }
                        },
                    ) { Text(stringResource(MR.strings.local_transfer_continue)) }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            conflictPreview = null
                            pendingTargetId = -1L
                            importing = false
                        },
                    ) { Text(stringResource(MR.strings.action_cancel)) }
                },
            )
        }
    }
}

@Composable
private fun ImportSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
