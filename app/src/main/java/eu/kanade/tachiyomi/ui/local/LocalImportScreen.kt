package eu.kanade.tachiyomi.ui.local

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.condensedBulletList
import eu.kanade.presentation.more.settings.widget.SwitchPreferenceWidget
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.local.LocalChapterTransferJob
import eu.kanade.tachiyomi.data.local.LocalChapterTransferService
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.launch
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.source.local.LocalSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * One collection a batch will create or reuse, as the target list shows it.
 *
 * [key] is the name the folder gave, and never changes: it is what a rename is recorded against, so
 * renaming the same row twice replaces the first rename instead of stacking a second one on a name
 * that no longer exists. [name] is what the collection will actually be called, after any rename.
 *
 * [exists] is null when the name cannot be resolved against the library: either several stored
 * collections match it after normalization, or the name is blank. The reader has to tidy that up
 * before the import can run, so it is neither "new" nor "reused".
 */
private data class CollectionListItem(
    val key: String,
    val name: String,
    val exists: Boolean?,
)

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
                preview.isDirectory,
            )
        }
    },
    restore = { saved ->
        saved.chunked(6).map { fields ->
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
                // Absent only in state saved by a build before this field existed. Defaulting to
                // false degrades that batch to the manual target, which is what every source had
                // before the field: guessing "folder" would turn a restored file into a collection
                // named after the file, and a name the reader never chose is worse than one they
                // have to type.
                isDirectory = fields.getOrNull(5) as? Boolean ?: false,
            )
        }
    },
)

private fun LocalChapterTransferService.SourcePreview.shape() =
    LocalImportSourceShape(displayName, isDirectory, groups.map { it.name })

@Composable
private fun ImportNotice(message: String) {
    Text(
        text = message,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
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
        var sourcePreviews by rememberSaveable(stateSaver = sourcePreviewListSaver) {
            mutableStateOf<List<LocalChapterTransferService.SourcePreview>>(emptyList())
        }
        var rejectedSources by remember {
            mutableStateOf<List<LocalImportRejection>>(emptyList())
        }
        var draft by rememberSaveable { mutableStateOf(LocalImportDraft()) }
        var mangas by remember { mutableStateOf<List<Manga>>(emptyList()) }
        var allLocalMangas by remember { mutableStateOf<List<Manga>>(emptyList()) }
        var libraryLoaded by remember { mutableStateOf(false) }
        var inspecting by remember { mutableStateOf(false) }
        var showMangaPicker by rememberSaveable { mutableStateOf(false) }
        var showCollectionList by rememberSaveable { mutableStateOf(false) }
        val output = if (draft.packFolders) {
            LocalChapterTransferService.FolderOutput.CBZ
        } else {
            LocalChapterTransferService.FolderOutput.DIRECTORY
        }
        val deleteSource = draft.deleteOriginals
        var importing by remember { mutableStateOf(false) }
        var conflictPreview by remember { mutableStateOf<LocalChapterTransferService.ImportPreview?>(null) }
        var pendingTargetId by remember { mutableLongStateOf(-1L) }
        var pendingGroupedPlans by remember {
            mutableStateOf<List<LocalChapterTransferService.GroupPreviewRequest>>(emptyList())
        }
        val transferStatus by remember(context) { LocalChapterTransferJob.statusFlow(context) }
            .collectAsStateWithLifecycle(initialValue = null)
        val sourceShapes = remember(sourcePreviews) { sourcePreviews.map { it.shape() } }
        val selectedUris = remember(sourcePreviews, fixedTargetMangaId) {
            sourcePreviews.flatMap { source ->
                if (fixedTargetMangaId != null && source.groups.isNotEmpty()) {
                    source.groups.flatMap { it.candidateUris }
                } else {
                    listOf(source.uri)
                }
            }.distinct()
        }
        val isCollectionImport = fixedTargetMangaId == null && isLocalCollectionImport(sourceShapes)
        val derivedCollectionNames = localImportCollectionNames(sourceShapes)
        val isSingleCollection = isCollectionImport && derivedCollectionNames.size == 1
        val isMultiCollection = isCollectionImport && derivedCollectionNames.size > 1
        val collectionRenames = draft.renames
        val derivedName = derivedCollectionNames.singleOrNull()?.takeIf { isSingleCollection }
        val newTitle =
            draft.typedName ?: derivedName?.let { collectionRenames[it] ?: localMangaDirectoryName(it) }.orEmpty()
        val targetId = fixedTargetMangaId ?: draft.pickedId
        val selectedManga = allLocalMangas.firstOrNull { it.id == targetId }
        val existingUrls = remember(allLocalMangas) { allLocalMangas.map(Manga::url) }
        val sourceGroups = sourcePreviews.flatMap { preview ->
            when {
                preview.groups.isNotEmpty() -> preview.groups
                !isCollectionImport -> emptyList()
                else -> listOf(
                    LocalChapterTransferService.SourceGroupPreview(
                        uri = preview.uri,
                        name = preview.displayName,
                        candidateNames = preview.candidateNames,
                        candidateUris = listOf(preview.uri),
                    ),
                )
            }
        }
        val renamedSourceGroups = sourceGroups.map { group ->
            collectionRenames[group.name]?.let { group.copy(name = it) } ?: group
        }
        val singleCollectionName = when {
            !isSingleCollection -> null
            targetId != null -> selectedManga?.url.orEmpty()
            else -> newTitle
        }
        val effectiveGroups = if (singleCollectionName != null) {
            renamedSourceGroups.map { it.copy(name = singleCollectionName) }
        } else {
            renamedSourceGroups
        }
        val groupedNameCollisions = if (isMultiCollection) {
            localGroupedImportNameCollisionCount(derivedCollectionNames, collectionRenames)
        } else {
            0
        }
        val hasInvalidGroupedName = hasInvalidLocalGroupedImportName(effectiveGroups.map { it.name })
        val groupedTargetResolutions = effectiveGroups
            .groupBy { localMangaDirectoryIdentity(it.name) }
            .values
            .map { groups ->
                groups to resolveLocalGroupedImportTarget(
                    proposedName = groups.first().name,
                    existingUrls = existingUrls,
                )
            }
        val ambiguousExistingGroupedTargetCount = groupedTargetResolutions.count { (_, target) -> target == null }

        /** Whether every name this batch will import under is one the import can act on. */
        val collectionNamesUsable = !hasInvalidGroupedName &&
            groupedNameCollisions == 0 &&
            ambiguousExistingGroupedTargetCount == 0 &&
            // A single collection is named by the field or by the collection picked instead, so
            // neither of those chosen is an unfinished name rather than a reason to fall back to the
            // folder's own.
            (!isSingleCollection || !singleCollectionName.isNullOrBlank())

        /**
         * The list the target card shows, in batch order: the name each collection will be created
         * or reused under, and whether it already exists. A null third means the name is ambiguous
         * against the library and cannot be resolved without the reader tidying it up first.
         *
         * Built from the folders' own names rather than from [effectiveGroups], so each row keeps a
         * key that a rename cannot move out from under it.
         */
        val collectionList = derivedCollectionNames.map { derived ->
            val renamed = collectionRenames[derived] ?: derived
            val target = resolveLocalGroupedImportTarget(
                proposedName = renamed,
                existingUrls = existingUrls,
            )
            CollectionListItem(
                key = derived,
                name = localMangaDirectoryName(renamed),
                exists = target?.exists,
            )
        }
        val existingCollectionCount = collectionList.count { it.exists == true }
        val newCollectionCount = collectionList.count { it.exists == false }
        val destination = localImportDestination(
            hasSources =
            selectedUris.isNotEmpty() && libraryLoaded &&
                (targetId == null || selectedManga != null || isMultiCollection),
            grouped = isCollectionImport,
            groupsReady = collectionNamesUsable,
            targetId = targetId,
            name = newTitle,
        )
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
            options: LocalChapterTransferService.Options = LocalChapterTransferService.Options(output, deleteSource),
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
                options = options,
            )
        }

        fun addSelectedUris(uris: List<Uri>) {
            if (uris.isEmpty()) return
            inspecting = true
            scope.launch {
                try {
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
                    val inspected = uniqueUris.map { uri ->
                        try {
                            transferService.inspectSource(uri)
                        } catch (e: SecurityException) {
                            LocalChapterTransferService.SourceInspection(
                                uri.lastPathSegment.orEmpty(),
                                rejection = LocalChapterTransferService.SourceRejection.Unreadable,
                            )
                        }
                    }
                    val usable = inspected.mapNotNull { it.preview }
                    val rejected = inspected.mapNotNull { inspection ->
                        inspection.rejection?.let { rejection -> inspection.displayName to rejection }
                    }
                    rejectedSources = localImportRejectionsAfterPick(
                        previous = rejectedSources,
                        picked = rejected,
                        usableSourceCount = usable.size,
                    )
                    sourcePreviews = (sourcePreviews + usable).distinctBy { it.uri }
                } finally {
                    inspecting = false
                }
            }
        }

        LaunchedEffect(Unit) {
            allLocalMangas = mangaRepository.getLocalMangaIds().mapNotNull {
                mangaRepository.getMangaByIdOrNull(it)
            }
            mangas = mangaRepository.getLocalCollectionTargets(allLocalMangas)
            libraryLoaded = true
        }
        // OpenDocument grants persistable read access when the provider supports it, which lets
        // WorkManager continue a long import after the screen or app process is recreated.
        val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
            addSelectedUris(it)
        }
        val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
            it?.let { uri -> addSelectedUris(listOf(uri)) }
        }

        val editable = !inspecting && !importing
        fun submit() {
            if (!editable || destination == null) return
            importing = true
            scope.launch {
                try {
                    val plans = if (destination == LocalImportDestination.Grouped) groupedPlans() else emptyList()
                    val id = when (destination) {
                        LocalImportDestination.Grouped -> -1L
                        is LocalImportDestination.Existing -> destination.id
                        is LocalImportDestination.Named -> networkToLocal(
                            Manga.create().copy(
                                source = LocalSource.ID,
                                url = destination.name,
                                title = destination.name,
                            ),
                        ).id
                    }
                    val preview = if (destination == LocalImportDestination.Grouped) {
                        transferService.previewGroupedImport(plans)
                    } else {
                        transferService.previewImport(selectedUris, id)
                    }
                    if (preview.conflicts.isNotEmpty()) {
                        pendingTargetId = id
                        pendingGroupedPlans = plans
                        conflictPreview = preview
                    } else {
                        val started = if (destination == LocalImportDestination.Grouped) {
                            startGroupedImport(plans)
                        } else {
                            LocalChapterTransferJob.start(
                                context,
                                selectedUris,
                                id,
                                LocalChapterTransferService.Options(output, deleteSource),
                            )
                        }
                        if (started) navigator.pop() else context.toast(MR.strings.local_import_start_failed)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    context.toast(MR.strings.local_import_start_failed)
                } finally {
                    importing = conflictPreview != null
                }
            }
        }

        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(MR.strings.action_import_local_chapters_short),
                    navigateUp = navigator::pop,
                )
            },
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    item {
                        TextPreferenceWidget(
                            title = stringResource(MR.strings.local_import_select_folder),
                            icon = Icons.Outlined.FolderOpen,
                            onPreferenceClick = if (editable) ({ folderPicker.launch(null) }) else null,
                        )
                        TextPreferenceWidget(
                            title = stringResource(MR.strings.local_import_select_file),
                            icon = Icons.Outlined.InsertDriveFile,
                            onPreferenceClick = if (editable) ({ filePicker.launch(arrayOf("*/*")) }) else null,
                        )
                    }
                    items(sourcePreviews, key = { it.uri.toString() }) { source ->
                        TextPreferenceWidget(
                            title = source.displayName,
                            subtitle = if (source.isDirectory) {
                                stringResource(MR.strings.local_import_work_count, source.candidateNames.size)
                            } else {
                                null
                            },
                            widget = {
                                IconButton(
                                    enabled = editable,
                                    onClick = {
                                        val remaining = sourcePreviews - source
                                        draft = draft.afterRemoval(remaining.map { it.shape() })
                                        sourcePreviews = remaining
                                    },
                                ) {
                                    Icon(
                                        Icons.Outlined.Close,
                                        stringResource(MR.strings.local_import_remove, source.displayName),
                                    )
                                }
                            },
                        )
                        if (source.ignoredGroupCount > 0) {
                            ImportNotice(
                                stringResource(MR.strings.local_import_ignored_groups, source.ignoredGroupCount),
                            )
                        }
                    }
                    items(rejectedSources) { (name, reason) ->
                        ImportNotice(stringResource(MR.strings.local_import_rejected_entry, name, reason.reasonText()))
                    }
                    if (sourcePreviews.isNotEmpty()) {
                        item {
                            HorizontalDivider()
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                when {
                                    fixedTargetMangaId != null -> {
                                        Text(
                                            stringResource(
                                                MR.strings.local_import_current_collection,
                                                selectedManga?.title.orEmpty(),
                                            ),
                                        )
                                    }
                                    isMultiCollection -> {
                                        Text(
                                            stringResource(
                                                MR.strings.local_import_summary,
                                                collectionList.size,
                                                newCollectionCount,
                                                existingCollectionCount,
                                            ),
                                            style = MaterialTheme.typography.titleSmall,
                                        )
                                        CollectionNameList(items = collectionList, onClick = {
                                            if (editable) {
                                                showCollectionList =
                                                    true
                                            }
                                        })
                                    }
                                    else -> {
                                        if (targetId == null) {
                                            OutlinedTextField(
                                                value = newTitle,
                                                onValueChange = { draft = draft.copy(typedName = it) },
                                                modifier = Modifier.fillMaxWidth(),
                                                label = {
                                                    Text(stringResource(MR.strings.local_import_collection_name))
                                                },
                                                enabled = editable,
                                                singleLine = true,
                                            )
                                        } else {
                                            Text(
                                                stringResource(
                                                    MR.strings.local_import_current_collection,
                                                    selectedManga?.title.orEmpty(),
                                                ),
                                            )
                                        }
                                        TextButton(
                                            onClick = { showMangaPicker = true },
                                            enabled = editable && mangas.isNotEmpty(),
                                        ) { Text(stringResource(MR.strings.local_import_pick_collection_title)) }
                                        if (targetId != null) {
                                            TextButton(onClick = {
                                                draft = draft.copy(pickedId = null)
                                            }, enabled = editable) {
                                                Text(stringResource(MR.strings.local_import_enter_name))
                                            }
                                        }
                                    }
                                }
                                if (isCollectionImport && groupedNameCollisions > 0) {
                                    Text(
                                        stringResource(MR.strings.local_import_name_collision, groupedNameCollisions),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                                if (isMultiCollection && hasInvalidGroupedName) {
                                    Text(
                                        stringResource(MR.strings.local_import_blank_name),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                } else if (isCollectionImport && !hasInvalidGroupedName &&
                                    ambiguousExistingGroupedTargetCount > 0
                                ) {
                                    Text(
                                        stringResource(MR.strings.local_import_ambiguous_name),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                            HorizontalDivider()
                            TextPreferenceWidget(
                                title = stringResource(MR.strings.local_import_section_settings),
                                widget = {
                                    Icon(
                                        if (draft.expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                        null,
                                    )
                                },
                                onPreferenceClick = if (!editable) {
                                    null
                                } else {
                                    { draft = draft.copy(expanded = !draft.expanded) }
                                },
                            )
                            if (draft.expanded) {
                                if (sourceShapes.any { it.isDirectory }) {
                                    SwitchPreferenceWidget(
                                        title = stringResource(MR.strings.local_import_output_cbz),
                                        checked = draft.packFolders,
                                        onCheckedChanged = { if (editable) draft = draft.copy(packFolders = it) },
                                    )
                                }
                                SwitchPreferenceWidget(
                                    title = stringResource(MR.strings.local_import_delete_source),
                                    checked = deleteSource,
                                    onCheckedChanged = { if (editable) draft = draft.copy(deleteOriginals = it) },
                                )
                            }
                        }
                    }
                    transferStatus?.takeUnless { it.state.isFinished }?.let { status ->
                        item {
                            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                LinearProgressIndicator(
                                    progress = {
                                        when {
                                            status.totalBytes > 0 -> status.copiedBytes.toFloat() / status.totalBytes
                                            status.total > 0 -> status.completed.toFloat() / status.total
                                            else -> 0f
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text(
                                    stringResource(
                                        MR.strings.local_import_progress,
                                        status.completed,
                                        status.total,
                                        status.currentName,
                                    ),
                                )
                            }
                        }
                    }
                }
                if (sourcePreviews.isNotEmpty()) {
                    Button(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        enabled = editable && destination != null && transferStatus?.state?.isFinished != false,
                        onClick = ::submit,
                    ) {
                        val label = if (deleteSource) {
                            MR.strings.local_import_and_delete
                        } else {
                            MR.strings.action_import_start
                        }
                        Text(stringResource(label))
                    }
                }
            }
        }
        if (showMangaPicker) {
            LocalMangaPickerDialog(
                mangas = mangas,
                selectedMangaId = targetId ?: -1L,
                onSelected = {
                    draft = draft.afterCollectionPicker(it)
                },
                onDismissRequest = { showMangaPicker = false },
            )
        }
        if (showCollectionList) {
            CollectionNameDialog(
                items = collectionList,
                onRename = { key, renamed ->
                    // Keyed by the name the folder gave, so renaming the same row twice replaces the
                    // first rename rather than stacking a second on a name that no longer exists -
                    // and a name edited back to the folder's own drops the entry instead of leaving
                    // a rename that says nothing.
                    draft = draft.copy(
                        renames = if (renamed == key || renamed.isBlank()) {
                            collectionRenames - key
                        } else {
                            collectionRenames + (key to renamed)
                        },
                    )
                },
                onDismissRequest = { showCollectionList = false },
            )
        }
        conflictPreview?.let { preview ->
            var replace by remember(preview) { mutableStateOf(false) }
            AlertDialog(
                onDismissRequest = {
                    conflictPreview = null
                    pendingGroupedPlans = emptyList()
                    pendingTargetId = -1L
                    importing = false
                },
                title = { Text(stringResource(MR.strings.local_transfer_conflict_title)) },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(
                                MR.strings.local_transfer_conflict_message,
                                preview.conflicts.size,
                            ),
                        )
                        // The names are what decides whether continuing is safe: "12 items will be
                        // skipped" cannot be checked, but the list can.
                        Text(
                            text = condensedBulletList(preview.conflicts),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (preview.replacements.isNotEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.material3.Checkbox(
                                    checked = replace,
                                    onCheckedChange = { replace = it },
                                )
                                Text(stringResource(MR.strings.local_transfer_replace))
                            }
                            if (replace) {
                                Text(
                                    stringResource(
                                        MR.strings.local_transfer_replace_scope,
                                        preview.replacements.size,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            scope.launch {
                                val options = LocalChapterTransferService.Options(
                                    folderOutput = output,
                                    deleteSourceAfterSuccess = deleteSource,
                                    replacements = if (replace) preview.replacements else emptyList(),
                                    replaceConflicts = replace,
                                )
                                val started = if (pendingGroupedPlans.isNotEmpty()) {
                                    startGroupedImport(pendingGroupedPlans, options)
                                } else {
                                    val target = pendingTargetId
                                    target >= 0 && LocalChapterTransferJob.start(
                                        context = context,
                                        uris = selectedUris,
                                        targetMangaId = target,
                                        options = options,
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
                    ) {
                        val action = if (replace) {
                            MR.strings.local_transfer_replace
                        } else {
                            MR.strings.local_transfer_continue
                        }
                        Text(stringResource(action))
                    }
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

/**
 * The collections a batch will create or reuse, as a list of names inside the target card.
 *
 * Shows every name rather than the first few: with a batch this list is the one thing worth
 * reading, and a truncated one cannot be checked. The rows are capped in height and the whole list
 * is clickable, because a long batch cannot be read in the card anyway - [CollectionNameDialog]
 * opens the same list with room to scroll it and to fix a name.
 */
@Composable
private fun CollectionNameList(
    items: List<CollectionListItem>,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
    ) {
        items.take(COLLECTION_LIST_PREVIEW_LIMIT).forEach { item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = item.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                CollectionStateLabel(item.exists)
            }
        }
        if (items.size > COLLECTION_LIST_PREVIEW_LIMIT) {
            Text(
                text = stringResource(
                    MR.strings.local_import_more_items,
                    items.size - COLLECTION_LIST_PREVIEW_LIMIT,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }
    }
}

/**
 * The whole batch's collections, scrollable, with a rename per row.
 *
 * Kept as its own dialog rather than an expanded card because the list is unbounded: a 根目录/作者/本子
 * pick of a hundred authors has a hundred rows, which is a screen of its own and not something to
 * push the import button off the bottom of the page for.
 */
@Composable
private fun CollectionNameDialog(
    items: List<CollectionListItem>,
    onRename: (original: String, renamed: String) -> Unit,
    onDismissRequest: () -> Unit,
) {
    // The row being edited, by its stable key: renaming in place keeps the list and its own scroll
    // position, where a second dialog over this one would hide the names being compared.
    var editingKey by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(MR.strings.local_import_collections_title)) },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(items, key = { it.key }) { item ->
                    if (editingKey == item.key) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = draft,
                                onValueChange = { draft = it },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                label = { Text(stringResource(MR.strings.local_import_collection_name)) },
                            )
                            TextButton(
                                onClick = {
                                    onRename(item.key, localMangaDirectoryName(draft))
                                    editingKey = null
                                },
                            ) { Text(stringResource(MR.strings.action_save)) }
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .clickable {
                                    editingKey = item.key
                                    draft = item.name
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = item.name,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                // Two lines rather than one: the dialog exists to read names in
                                // full, so a long one is worth a taller row here.
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            CollectionStateLabel(item.exists)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(MR.strings.action_ok)) }
        },
    )
}

/** Whether a collection in the list will be created, reused, or needs its name sorted out first. */
@Composable
private fun CollectionStateLabel(exists: Boolean?) {
    val (label, color) = when (exists) {
        true -> stringResource(MR.strings.local_import_state_reused) to MaterialTheme.colorScheme.primary
        false -> stringResource(MR.strings.local_import_state_new) to MaterialTheme.colorScheme.onSurfaceVariant
        null -> stringResource(MR.strings.local_import_state_ambiguous) to MaterialTheme.colorScheme.error
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = Modifier.padding(start = 12.dp),
    )
}

/** How many names the target card lists before deferring to [CollectionNameDialog]. */
private const val COLLECTION_LIST_PREVIEW_LIMIT = 5

/**
 * What to tell the user about a rejected source.
 *
 * Each reason names a different fix, so they are never collapsed into one "could not import"
 * message: an unreadable folder is a permission problem, an empty one is a wrong-folder problem,
 * and a library folder is a "you do not need to import this" problem.
 */
@Composable
private fun LocalChapterTransferService.SourceRejection.reasonText(): String {
    return when (this) {
        LocalChapterTransferService.SourceRejection.Unreadable ->
            stringResource(MR.strings.local_import_reject_unreadable)
        LocalChapterTransferService.SourceRejection.NoContent ->
            stringResource(MR.strings.local_import_reject_no_content)
        LocalChapterTransferService.SourceRejection.InsideLibrary ->
            stringResource(MR.strings.local_import_reject_inside_library)
    }
}
