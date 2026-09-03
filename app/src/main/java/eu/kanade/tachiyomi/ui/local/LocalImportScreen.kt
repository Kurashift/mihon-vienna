package eu.kanade.tachiyomi.ui.local

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
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

// 来源文件选择状态需要撑过"选择已有合集"的 push/pop：Voyager 离开组合时只有
// rememberSaveable 会随返回恢复，普通 remember 会被丢弃（表现为选完合集后文件被清空）。
private val sourcePreviewListSaver = listSaver<List<LocalChapterTransferService.SourcePreview>, Any?>(
    save = { previews ->
        previews.flatMapTo(arrayListOf()) { preview ->
            arrayListOf<Any?>(
                preview.uri.toString(),
                preview.displayName,
                ArrayList(preview.candidateNames),
                ArrayList(
                    preview.groups.map { group ->
                        arrayListOf<Any?>(
                            group.uri.toString(),
                            group.name,
                            ArrayList(group.candidateNames),
                            ArrayList(group.candidateUris.map { it.toString() }),
                        )
                    },
                ),
                preview.ignoredGroupCount,
            )
        }
    },
    restore = { saved ->
        saved.chunked(5).map { fields ->
            LocalChapterTransferService.SourcePreview(
                uri = Uri.parse(fields[0] as String),
                displayName = fields[1] as String,
                candidateNames = (fields[2] as List<*>).map { it as String },
                groups = (fields[3] as List<*>).map { groupFields ->
                    val group = groupFields as List<*>
                    LocalChapterTransferService.SourceGroupPreview(
                        uri = Uri.parse(group[0] as String),
                        name = group[1] as String,
                        candidateNames = (group[2] as List<*>).map { it as String },
                        candidateUris = (group[3] as List<*>).map { Uri.parse(it as String) },
                    )
                },
                ignoredGroupCount = fields[4] as Int,
            )
        }
    },
)

private val uriListSaver = listSaver<List<Uri>, String>(
    save = { uris -> uris.mapTo(arrayListOf()) { it.toString() } },
    restore = { saved -> saved.map { Uri.parse(it) } },
)

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
        var selectedUris by rememberSaveable(stateSaver = uriListSaver) {
            mutableStateOf<List<Uri>>(emptyList())
        }
        var sourcePreviews by rememberSaveable(stateSaver = sourcePreviewListSaver) {
            mutableStateOf<List<LocalChapterTransferService.SourcePreview>>(emptyList())
        }
        var ignoredSourceCount by remember { mutableLongStateOf(0L) }
        var targetId by rememberSaveable { mutableStateOf(fixedTargetMangaId ?: -1L) }
        var mangas by remember { mutableStateOf<List<Manga>>(emptyList()) }
        var allLocalMangas by remember { mutableStateOf<List<Manga>>(emptyList()) }
        var newTitle by rememberSaveable { mutableStateOf("") }
        var targetMode by rememberSaveable {
            mutableStateOf(if (fixedTargetMangaId != null) ImportTargetMode.EXISTING else ImportTargetMode.NEW)
        }
        var showMangaPicker by rememberSaveable { mutableStateOf(false) }
        var output by remember { mutableStateOf(LocalChapterTransferService.FolderOutput.DIRECTORY) }
        var deleteSource by remember { mutableStateOf(false) }
        var importing by remember { mutableStateOf(false) }
        var conflictPreview by remember { mutableStateOf<LocalChapterTransferService.ImportPreview?>(null) }
        var pendingTargetId by remember { mutableLongStateOf(-1L) }
        var pendingGroupedPlans by remember {
            mutableStateOf<List<LocalChapterTransferService.GroupPreviewRequest>>(emptyList())
        }
        val transferStatus by remember(context) { LocalChapterTransferJob.statusFlow(context) }
            .collectAsStateWithLifecycle(initialValue = null)
        val isGroupedImport = fixedTargetMangaId == null && sourcePreviews.isNotEmpty() &&
            sourcePreviews.all { it.groups.isNotEmpty() }
        val sourceGroups = sourcePreviews.flatMap { it.groups }
        val groupedNameCollisions = localGroupedImportNameCollisionCount(sourceGroups.map { it.name })
        val hasInvalidGroupedName = hasInvalidLocalGroupedImportName(sourceGroups.map { it.name })
        val groupedTargetKeys = sourceGroups.map { localMangaDirectoryIdentity(it.name) }.distinct()
        val groupedTargetResolutions = sourceGroups
            .groupBy { localMangaDirectoryIdentity(it.name) }
            .values
            .map { groups ->
                groups to resolveLocalGroupedImportTarget(
                    proposedName = groups.first().name,
                    existingUrls = allLocalMangas.map(Manga::url),
                )
            }
        val existingGroupedTargetCount = groupedTargetResolutions.count { (_, target) -> target?.exists == true }
        val ambiguousExistingGroupedTargetCount = groupedTargetResolutions.count { (_, target) -> target == null }

        fun groupedPlans(): List<LocalChapterTransferService.GroupPreviewRequest> {
            return groupedTargetResolutions.mapNotNull { (groups, target) ->
                target?.let {
                    LocalChapterTransferService.GroupPreviewRequest(
                        targetUrl = it.url,
                        uris = groups.flatMap { it.candidateUris }.distinct(),
                    )
                }
            }
        }

        suspend fun startGroupedImport(
            plans: List<LocalChapterTransferService.GroupPreviewRequest>,
        ): Boolean {
            if (LocalChapterTransferJob.isRunning(context)) return false
            val currentLocalMangas = mangaRepository.getLocalMangaIds().mapNotNull { id ->
                runCatching { mangaRepository.getMangaById(id) }.getOrNull()
            }
            val resolvedExisting = plans.map { plan ->
                val target = resolveLocalGroupedImportTarget(plan.targetUrl, currentLocalMangas.map(Manga::url))
                    ?: return false
                plan to target
            }
            val createdByUrl = networkToLocal(
                resolvedExisting.mapNotNull { (plan, target) ->
                    if (target.exists) return@mapNotNull null
                    Manga.create().copy(
                        source = LocalSource.ID,
                        url = target.url,
                        title = target.url,
                    )
                },
            ).associateBy { it.url }
            val groups = resolvedExisting.map { (plan, target) ->
                val manga = if (target.exists) {
                    currentLocalMangas.singleOrNull { it.url == target.url } ?: return false
                } else {
                    createdByUrl[target.url] ?: return false
                }
                LocalChapterTransferService.GroupImport(
                    targetMangaId = manga.id,
                    uris = plan.uris,
                )
            }
            return LocalChapterTransferJob.startGrouped(
                context = context,
                groups = groups,
                options = LocalChapterTransferService.Options(output, deleteSource),
            )
        }

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
                val inspected = uniqueUris.mapNotNull { transferService.inspectSource(it) }
                val expectedGrouped = sourcePreviews.firstOrNull()?.groups?.isNotEmpty()
                    ?: inspected.firstOrNull()?.groups?.isNotEmpty()
                val previews = inspected.filter { it.groups.isNotEmpty() == expectedGrouped }
                ignoredSourceCount += (uniqueUris.size - previews.size)
                val mergedPreviews = (sourcePreviews + previews).distinctBy { it.uri }
                sourcePreviews = mergedPreviews
                selectedUris = if (fixedTargetMangaId != null) {
                    mergedPreviews.flatMap { preview ->
                        preview.groups.takeIf { it.isNotEmpty() }
                            ?.flatMap { it.candidateUris }
                            ?: listOf(preview.uri)
                    }.distinct()
                } else {
                    mergedPreviews.map { it.uri }
                }
            }
        }

        LaunchedEffect(Unit) {
            val localIds = mangaRepository.getLocalMangaIds()
            val nonEmptyIds = mangaRepository.getMangaProgressBySource(LocalSource.ID)
                .mapTo(hashSetOf()) { it.mangaId }
            allLocalMangas = localIds.mapNotNull { id ->
                runCatching { mangaRepository.getMangaById(id) }.getOrNull()
            }
            mangas = allLocalMangas.filter { it.id in nonEmptyIds }.sortedBy { it.title.lowercase() }
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
                                        label = "从文件夹导入",
                                        enabled = !importing,
                                        onClick = { folderPicker.launch(null) },
                                    )
                                    SourceButton(
                                        modifier = Modifier.weight(1f),
                                        icon = Icons.Outlined.InsertDriveFile,
                                        label = "从文件导入",
                                        enabled = !importing,
                                        onClick = { filePicker.launch(arrayOf("*/*")) },
                                    )
                                }
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SourceButton(
                                        icon = Icons.Outlined.FolderOpen,
                                        label = "从文件夹导入",
                                        enabled = !importing,
                                        onClick = { folderPicker.launch(null) },
                                    )
                                    SourceButton(
                                        icon = Icons.Outlined.InsertDriveFile,
                                        label = "从文件导入",
                                        enabled = !importing,
                                        onClick = { filePicker.launch(arrayOf("*/*")) },
                                    )
                                }
                            }
                        }
                        if (selectedUris.isEmpty()) {
                            Text(
                                text = "可导入合集文件夹，也可单选或多选 CBZ/ZIP/EPUB 等压缩包",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        } else {
                            Text(
                                text = if (isGroupedImport) {
                                    "已识别 ${sourceGroups.size} 个合集，共 ${sourcePreviews.sumOf {
                                        it.candidateNames.size
                                    }} 个本子"
                                } else {
                                    "已添加 ${sourcePreviews.size} 个来源，共 ${sourcePreviews.sumOf {
                                        it.candidateNames.size
                                    }} 个本子"
                                },
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
                                            if (preview.groups.isNotEmpty() && fixedTargetMangaId == null) {
                                                "${preview.groups.size} 个合集：${preview.groups.take(3).joinToString("、") {
                                                    it.name
                                                }}"
                                            } else {
                                                "包含 ${preview.candidateNames.size} 个本子：${preview.candidateNames.take(
                                                    3,
                                                ).joinToString("、")}"
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            val remaining = sourcePreviews.filterNot { it.uri == preview.uri }
                                            sourcePreviews = remaining
                                            selectedUris = if (fixedTargetMangaId != null) {
                                                remaining.flatMap { source ->
                                                    source.groups.takeIf { it.isNotEmpty() }
                                                        ?.flatMap { it.candidateUris }
                                                        ?: listOf(source.uri)
                                                }.distinct()
                                            } else {
                                                remaining.map { it.uri }
                                            }
                                        },
                                        enabled = !importing,
                                    ) {
                                        Icon(Icons.Outlined.Close, contentDescription = "移除")
                                    }
                                }
                                if (preview.ignoredGroupCount > 0) {
                                    Text(
                                        text = "已忽略 ${preview.ignoredGroupCount} 个不属于作者合集的项目",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (ignoredSourceCount > 0) {
                                Text(
                                    text = "已忽略 $ignoredSourceCount 个不兼容当前结构或不包含本子内容的来源",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                item {
                    ImportSection(title = "归属合集") {
                        if (isGroupedImport) {
                            Text("将按一级文件夹名称自动复用或创建合集")
                            Text(
                                "复用 $existingGroupedTargetCount 个，新建 ${groupedTargetKeys.size - existingGroupedTargetCount} 个",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                sourceGroups.map {
                                    localMangaDirectoryName(it.name)
                                }.distinct().take(6).joinToString("、"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (groupedNameCollisions > 0) {
                                Text(
                                    "有 $groupedNameCollisions 组文件夹名称在目标目录中会重名，请先调整名称",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            if (hasInvalidGroupedName) {
                                Text(
                                    "存在无法作为合集名称的空白文件夹，请先调整名称",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            if (ambiguousExistingGroupedTargetCount > 0) {
                                Text(
                                    "已有合集名称存在歧义，请先在本库中整理同名合集",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        } else if (fixedTargetMangaId == null) {
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
                                        showMangaPicker = true
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
                                                .width(44.dp),
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
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    } else {
                                        Text(
                                            text = "尚未选择合集",
                                            modifier = Modifier.weight(1f),
                                            style = MaterialTheme.typography.bodyLarge,
                                        )
                                    }
                                    TextButton(onClick = { showMangaPicker = true }) { Text("选择") }
                                }
                            } else {
                                OutlinedTextField(
                                    value = newTitle,
                                    onValueChange = { newTitle = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("新建合集名称") },
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
                            Text("CBZ（压缩后再导入）")
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
                                        val safeTitle = localMangaDirectoryName(newTitle)
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
                                    if (
                                        isGroupedImport && groupedNameCollisions == 0 && !hasInvalidGroupedName &&
                                        ambiguousExistingGroupedTargetCount == 0
                                    ) {
                                        val plans = groupedPlans()
                                        val preview = runCatching {
                                            transferService.previewGroupedImport(plans)
                                        }.getOrNull()
                                        if (preview == null) {
                                            importing = false
                                        } else if (preview.conflicts.isNotEmpty()) {
                                            pendingTargetId = -1L
                                            pendingGroupedPlans = plans
                                            conflictPreview = preview
                                        } else if (startGroupedImport(plans)) {
                                            importing = false
                                            navigator.pop()
                                        } else {
                                            importing = false
                                        }
                                    } else if (resolvedTarget >= 0 && selectedUris.isNotEmpty()) {
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
                                    (
                                        isGroupedImport && groupedNameCollisions == 0 && !hasInvalidGroupedName &&
                                            ambiguousExistingGroupedTargetCount == 0
                                        ) ||
                                        (
                                            fixedTargetMangaId != null ||
                                                (targetMode == ImportTargetMode.EXISTING && targetId >= 0) ||
                                                (targetMode == ImportTargetMode.NEW && newTitle.isNotBlank())
                                            )
                                    ),
                        ) { Text("开始导入") }
                    }
                }
            }
        }
        if (showMangaPicker) {
            LocalMangaPickerDialog(
                mangas = mangas,
                selectedMangaId = targetId,
                onSelected = {
                    targetId = it
                    targetMode = ImportTargetMode.EXISTING
                },
                onDismissRequest = { showMangaPicker = false },
            )
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
                            scope.launch {
                                val started = if (pendingGroupedPlans.isNotEmpty()) {
                                    startGroupedImport(pendingGroupedPlans)
                                } else {
                                    val target = pendingTargetId
                                    target >= 0 && LocalChapterTransferJob.start(
                                        context = context,
                                        uris = selectedUris,
                                        targetMangaId = target,
                                        options = LocalChapterTransferService.Options(output, deleteSource),
                                    )
                                }
                                if (started) {
                                    conflictPreview = null
                                    pendingTargetId = -1L
                                    pendingGroupedPlans = emptyList()
                                    importing = false
                                    navigator.pop()
                                } else {
                                    importing = false
                                }
                            }
                        },
                    ) { Text(stringResource(MR.strings.local_transfer_continue)) }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            conflictPreview = null
                            pendingTargetId = -1L
                            pendingGroupedPlans = emptyList()
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
