package eu.kanade.tachiyomi.ui.local

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.condensedBulletList
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
        var rejectedSourceCount by remember { mutableLongStateOf(0L) }
        var rejectedSourceDetails by remember {
            mutableStateOf<List<Pair<String, LocalChapterTransferService.SourceRejection>>>(emptyList())
        }
        var targetId by rememberSaveable { mutableStateOf(fixedTargetMangaId ?: -1L) }
        var mangas by remember { mutableStateOf<List<Manga>>(emptyList()) }
        var allLocalMangas by remember { mutableStateOf<List<Manga>>(emptyList()) }
        var newTitle by rememberSaveable { mutableStateOf("") }
        var targetMode by rememberSaveable {
            mutableStateOf(if (fixedTargetMangaId != null) ImportTargetMode.EXISTING else ImportTargetMode.NEW)
        }
        var showMangaPicker by rememberSaveable { mutableStateOf(false) }
        var showCollectionList by rememberSaveable { mutableStateOf(false) }
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
        // A batch of folders is imported as collections, one per folder, named after it. A batch
        // that includes a file keeps the manual target it always had: a file is one chapter of a
        // collection the reader names, and the folders picked alongside it join that same target.
        val sourceShapes = sourcePreviews.map {
            LocalImportSourceShape(
                displayName = it.displayName,
                isDirectory = it.isDirectory,
                groupNames = it.groups.map { group -> group.name },
            )
        }
        val isCollectionImport = fixedTargetMangaId == null && isLocalCollectionImport(sourceShapes)
        // Names as the folders give them, before any renaming, de-duplicated.
        val derivedCollectionNames = localImportCollectionNames(sourceShapes)
        // One collection is named in the field the manual target has always used, so its name is the
        // reader's to edit there and the batch is imported through the ordinary single-target path -
        // which reuses an existing collection of that name by itself. Several collections have names
        // the folders own: they are listed to be checked, and renamed one by one from the list.
        val isSingleCollection = isCollectionImport && derivedCollectionNames.size == 1
        val isMultiCollection = isCollectionImport && derivedCollectionNames.size > 1
        // Renames made from the collection list, keyed by the name the folder gave. A map rather than
        // a rewritten source list: the source keeps naming its own folder, so removing a folder from
        // the batch cannot leave a rename pointing at nothing.
        var collectionRenames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
        // The collections this batch contributes. A folder that is itself the collection - its
        // contents are directly the works - has no first-level containers of its own, so it stands in
        // as one collection over itself: `importUrisInternal` expands a picked folder anyway, so
        // handing it the folder is exactly what already happened for a folder picked as a chapter
        // source. Everything downstream (name collision, target reuse, the import itself) then works
        // on one shape instead of two.
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
            val derived = localMangaDirectoryName(group.name)
            collectionRenames[derived]?.let { group.copy(name = it) } ?: group
        }
        // The name a single collection is created or reused under comes from the target controls: the
        // text field, or the collection picked instead. The group is what carries the chapter URIs - a
        // 根目录/作者/本子 folder holds its works one level down, and only the group knows that - so a
        // single collection still travels the grouped path with a group of one.
        val singleCollectionName = when {
            !isSingleCollection -> null
            targetMode == ImportTargetMode.EXISTING && targetId >= 0 ->
                mangas.firstOrNull { it.id == targetId }?.url
            else -> newTitle
        }
        val effectiveGroups = if (singleCollectionName != null) {
            renamedSourceGroups.map { it.copy(name = singleCollectionName) }
        } else {
            renamedSourceGroups
        }
        val groupedNameCollisions = localGroupedImportNameCollisionCount(effectiveGroups.map { it.name })
        val hasInvalidGroupedName = hasInvalidLocalGroupedImportName(effectiveGroups.map { it.name })
        val groupedTargetResolutions = effectiveGroups
            .groupBy { localMangaDirectoryIdentity(it.name) }
            .values
            .map { groups ->
                groups to resolveLocalGroupedImportTarget(
                    proposedName = groups.first().name,
                    existingUrls = allLocalMangas.map(Manga::url),
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
                existingUrls = allLocalMangas.map(Manga::url),
            )
            CollectionListItem(
                key = derived,
                name = localMangaDirectoryName(renamed),
                exists = target?.exists,
            )
        }
        val existingCollectionCount = collectionList.count { it.exists == true }
        val newCollectionCount = collectionList.count { it.exists == false }
        val ambiguousCollectionCount = collectionList.count { it.exists == null }
        // The folder's own name is the pre-filled answer, not a decision made for the reader. Tracked
        // against the value this effect last wrote, so a name the reader typed is never overwritten
        // while picking another folder still fills the field in.
        var lastPrefilledTitle by rememberSaveable { mutableStateOf<String?>(null) }
        LaunchedEffect(isSingleCollection, derivedCollectionNames) {
            val derived = derivedCollectionNames.singleOrNull()
                ?.takeIf { isSingleCollection }
                ?: return@LaunchedEffect
            if (newTitle.isBlank() || newTitle == lastPrefilledTitle) {
                newTitle = derived
                lastPrefilledTitle = derived
            }
        }

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
                val inspected = uniqueUris.map { transferService.inspectSource(it) }
                // Every readable pick joins the batch. There used to be a second filter here that
                // dropped a source whose layout differed from the ones already picked, because a
                // batch was imported through one target and a 根目录/作者/本子 folder could not share
                // that with a folder holding the works directly. A folder now names its own
                // collection, so the two layouts no longer conflict - and the filter is what used to
                // refuse exactly that combination.
                val usable = inspected.mapNotNull { it.preview }
                val rejected = inspected.filter { it.preview == null }
                // Both accumulate across picks so the count and the listed reasons never drift
                // apart: a user who tries several folders sees every rejection, not only the last
                // batch's.
                rejectedSourceDetails = rejectedSourceDetails + rejected.mapNotNull { inspection ->
                    inspection.rejection?.let { rejection -> inspection.displayName to rejection }
                }
                rejectedSourceCount += rejected.count { it.rejection != null }
                val mergedPreviews = (sourcePreviews + usable).distinctBy { it.uri }
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
                    ImportSection(title = "导入来源") {
                        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                            if (maxWidth >= 520.dp) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    SourceButton(
                                        modifier = Modifier.weight(1f),
                                        icon = Icons.Outlined.FolderOpen,
                                        label = "选择文件夹",
                                        enabled = !importing,
                                        onClick = { folderPicker.launch(null) },
                                    )
                                    SourceButton(
                                        modifier = Modifier.weight(1f),
                                        icon = Icons.Outlined.InsertDriveFile,
                                        label = "选择文件",
                                        enabled = !importing,
                                        onClick = { filePicker.launch(arrayOf("*/*")) },
                                    )
                                }
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SourceButton(
                                        icon = Icons.Outlined.FolderOpen,
                                        label = "选择文件夹",
                                        enabled = !importing,
                                        onClick = { folderPicker.launch(null) },
                                    )
                                    SourceButton(
                                        icon = Icons.Outlined.InsertDriveFile,
                                        label = "选择文件",
                                        enabled = !importing,
                                        onClick = { filePicker.launch(arrayOf("*/*")) },
                                    )
                                }
                            }
                        }
                        if (selectedUris.isEmpty()) {
                            Text(
                                text = "文件夹：整个文件夹作为一个来源导入\n" +
                                    "文件：支持 CBZ、ZIP、RAR、7Z、TAR 等压缩包与 EPUB",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        } else {
                            Text(
                                // The collection count belongs to the target card, which is where the
                                // names are; repeating it here only said the same thing twice.
                                text = if (isCollectionImport) {
                                    "共 ${sourcePreviews.sumOf { it.candidateNames.size }} 个本子"
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
                                            // The name a folder gives its collection is stated once, on
                                            // the target card. Here it is only what the source holds.
                                            if (isCollectionImport && preview.groups.isNotEmpty()) {
                                                "${preview.groups.size} 个合集，共 ${preview.candidateNames.size} 个本子"
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
                                        text = "已忽略 ${preview.ignoredGroupCount} 个无法识别为合集的一级文件夹",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        // Rendered outside the branches above: when every pick is rejected nothing
                        // else on screen changes, so this is the only thing telling the user their
                        // pick was seen and why it produced nothing.
                        if (rejectedSourceCount > 0) {
                            Text(
                                text = "有 $rejectedSourceCount 个来源未被导入：",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            rejectedSourceDetails.forEach { (name, rejection) ->
                                Text(
                                    text = "· $name：${rejection.reasonText()}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
                item {
                    ImportSection(title = "目标合集") {
                        if (isMultiCollection) {
                            Text(
                                "按文件夹名称新建或复用 ${collectionList.size} 个合集",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "复用 $existingCollectionCount 个，新建 $newCollectionCount 个",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            // The names in full, on one line each and clickable, rather than run
                            // together into a paragraph that has to be truncated: with a batch this
                            // is the one thing worth reading, and the list is where a name can be
                            // fixed. Long names ellipsize instead of wrapping, so the rows stay one
                            // height and the list stays scannable.
                            CollectionNameList(
                                items = collectionList,
                                onClick = { showCollectionList = true },
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
                            if (ambiguousCollectionCount > 0) {
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
                                    label = { Text("新建") },
                                )
                                FilterChip(
                                    selected = targetMode == ImportTargetMode.EXISTING,
                                    onClick = {
                                        targetMode = ImportTargetMode.EXISTING
                                        showMangaPicker = true
                                    },
                                    label = { Text("选已有的") },
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
                                    TextButton(onClick = { showMangaPicker = true }) { Text("选择合集") }
                                }
                            } else {
                                // Pre-filled with the picked folder's name when there is exactly one
                                // collection, and plain empty otherwise. It is an ordinary text field
                                // either way: the folder's name is the answer to type over, not a
                                // decision made for the reader - and a name that already exists is
                                // reused by the import path itself, so reuse needs no separate case.
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
                        Text("篇目保存方式", style = MaterialTheme.typography.bodyMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = output == LocalChapterTransferService.FolderOutput.DIRECTORY,
                                onClick = { output = LocalChapterTransferService.FolderOutput.DIRECTORY },
                            )
                            Text("保留文件夹")
                            RadioButton(
                                selected = output == LocalChapterTransferService.FolderOutput.CBZ,
                                onClick = { output = LocalChapterTransferService.FolderOutput.CBZ },
                            )
                            Text("打包成 CBZ")
                        }
                        Text(
                            text = if (output == LocalChapterTransferService.FolderOutput.CBZ) {
                                "文件夹来源导入时打包成 CBZ；文件来源原样保存"
                            } else {
                                "文件夹来源保持文件夹结构；文件来源原样保存"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = deleteSource, onCheckedChange = { deleteSource = it })
                            Column(modifier = Modifier.padding(start = 8.dp)) {
                                Text("导入成功后删除来源")
                                Text(
                                    "删除本次导入的来源文件夹或文件",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
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
                                    if (isCollectionImport && collectionNamesUsable) {
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
                                    (isCollectionImport && collectionNamesUsable) ||
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
        if (showCollectionList) {
            CollectionNameDialog(
                items = collectionList,
                onRename = { key, renamed ->
                    // Keyed by the name the folder gave, so renaming the same row twice replaces the
                    // first rename rather than stacking a second on a name that no longer exists -
                    // and a name edited back to the folder's own drops the entry instead of leaving
                    // a rename that says nothing.
                    collectionRenames = if (renamed == key || renamed.isBlank()) {
                        collectionRenames - key
                    } else {
                        collectionRenames + (key to renamed)
                    }
                },
                onDismissRequest = { showCollectionList = false },
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
                    }
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
                text = "还有 ${items.size - COLLECTION_LIST_PREVIEW_LIMIT} 个，点按查看全部",
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
        title = { Text("本次导入的合集") },
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
                                label = { Text("合集名称") },
                            )
                            TextButton(
                                onClick = {
                                    onRename(item.key, localMangaDirectoryName(draft))
                                    editingKey = null
                                },
                            ) { Text("保存") }
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
        true -> "复用" to MaterialTheme.colorScheme.primary
        false -> "新建" to MaterialTheme.colorScheme.onSurfaceVariant
        null -> "名称有歧义" to MaterialTheme.colorScheme.error
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

/**
 * What to tell the user about a rejected source.
 *
 * Each reason names a different fix, so they are never collapsed into one "could not import"
 * message: an unreadable folder is a permission problem, an empty one is a wrong-folder problem,
 * and a library folder is a "you do not need to import this" problem.
 */
private fun LocalChapterTransferService.SourceRejection.reasonText(): String {
    return when (this) {
        LocalChapterTransferService.SourceRejection.Unreadable ->
            "无法读取，可能是权限不足或系统限制的目录"
        LocalChapterTransferService.SourceRejection.NoContent ->
            "没有找到可导入的本子或压缩包"
        LocalChapterTransferService.SourceRejection.InsideLibrary ->
            "已经在本地库中，无需重复导入"
    }
}
