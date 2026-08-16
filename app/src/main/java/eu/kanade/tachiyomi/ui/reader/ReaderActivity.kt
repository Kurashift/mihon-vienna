package eu.kanade.tachiyomi.ui.reader

import android.annotation.SuppressLint
import android.app.assist.AssistContent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.View.LAYER_TYPE_HARDWARE
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import androidx.core.graphics.Insets
import androidx.core.net.toUri
import androidx.core.transition.doOnEnd
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.google.android.material.transition.platform.MaterialContainerTransform
import com.hippo.unifile.UniFile
import eu.kanade.core.util.ifSourcesLoaded
import eu.kanade.domain.base.BasePreferences
import eu.kanade.presentation.audio.AudioReaderFloatingBar
import eu.kanade.presentation.audio.AudioQuickPlaySheet
import eu.kanade.presentation.reader.DisplayRefreshHost
import eu.kanade.presentation.reader.OrientationSelectDialog
import eu.kanade.presentation.reader.ReaderContentOverlay
import eu.kanade.presentation.reader.ReaderPageActionsDialog
import eu.kanade.presentation.reader.ReaderPageIndicator
import eu.kanade.presentation.reader.ReadingModeSelectDialog
import eu.kanade.presentation.reader.appbars.ReaderAppBars
import eu.kanade.presentation.reader.components.ChapterNavigatorType
import eu.kanade.presentation.reader.settings.ReaderSettingsDialog
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.coil.TachiyomiImageDecoder
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.databinding.ReaderActivityBinding
import eu.kanade.tachiyomi.source.online.HttpSource
import tachiyomi.source.local.isLocal
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.ui.audio.AudioPlayerController
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel.SetAsCoverResult.AddToLibraryFirst
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel.SetAsCoverResult.Error
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel.SetAsCoverResult.Success
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsViewModel
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderProgressIndicator
import eu.kanade.tachiyomi.ui.reader.viewer.pager.R2LPagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonViewer
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.util.system.isNightMode
import eu.kanade.tachiyomi.util.system.openInBrowser
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.view.setComposeContent
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.Constants
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import kotlin.time.Duration.Companion.seconds

class ReaderActivity : BaseActivity() {

    companion object {
        private const val SWIPE_DIRECTION_EXTRA = "swipe_direction"
        private const val ANIM_NONE = 0
        private const val ANIM_PUSH_X = 1
        private const val ANIM_POP_X = 2
        private const val ANIM_PUSH_Y = 3
        private const val ANIM_POP_Y = 4

        fun newIntent(
            context: Context,
            mangaId: Long?,
            chapterId: Long?,
            swipeJump: Boolean = false,
            pageIndex: Int? = null,
        ): Intent {
            return Intent(context, ReaderActivity::class.java).apply {
                putExtra("manga", mangaId)
                putExtra("chapter", chapterId)
                putExtra("swipe_jump", swipeJump)
                if (chapterId != null && pageIndex != null) {
                    putExtra("chapter_id", chapterId)
                    putExtra("page_index", pageIndex)
                }
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }
    }

    private val readerPreferences = Injekt.get<ReaderPreferences>()
    private val preferences = Injekt.get<BasePreferences>()

    lateinit var binding: ReaderActivityBinding

    val viewModel by viewModels<ReaderViewModel>()
    private var assistUrl: String? = null

    /**
     * Configuration at reader level, like background color or forced orientation.
     */
    private var config: ReaderConfig? = null

    private var menuToggleToast: Toast? = null
    private val displayRefreshHost = DisplayRefreshHost()

    private val windowInsetsController by lazy { WindowInsetsControllerCompat(window, window.decorView) }

    private var loadingIndicator: ReaderProgressIndicator? = null

    private var firstReaderFrameReady = false
    private var firstReaderFrameGate: ViewTreeObserver.OnPreDrawListener? = null

    private var randomJumping = false

    /** True when this reader was opened from another reader through a random swipe. */
    private var openedViaSwipeJump = false

    private var swipeJumpAnim = ANIM_NONE

    /** Set while an onNewIntent-driven jump is finishing this instance, so its close
     *  transition doesn't fight the fresh instance's open transition. */
    private var restartingForJump = false

    /** The intent that must relaunch the reader once this finishing instance is destroyed. */
    private var pendingJumpIntent: Intent? = null

    /**
     * Until this uptime, taps on the viewer are ignored right after a swipe jump,
     * so the activity re-creation that follows the jump cannot mis-open the menu.
     */
    private var swipeJumpTapSuppressUntil = 0L

    fun isSwipeJumpTapSuppressed(): Boolean =
        SystemClock.uptimeMillis() < swipeJumpTapSuppressUntil

    var isScrollingThroughPages = false
        private set

    /**
     * Called when the activity is created. Initializes the presenter and configuration.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        registerSecureActivity(this)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        super.onCreate(savedInstanceState)

        // Swipe jumps animate along the swipe direction. A normal chapter-list entry has no
        // activity alpha transition: the webtoon viewer owns its first-frame reveal, and fading
        // the whole window at the same time exposes the loading/background hand-off as a flash.
        val openTransition = when (intent.getStringExtra(SWIPE_DIRECTION_EXTRA)) {
            "left" -> Triple(R.anim.shared_axis_x_push_enter, R.anim.shared_axis_x_push_exit, ANIM_PUSH_X)
            "right" -> Triple(R.anim.shared_axis_x_pop_enter, R.anim.shared_axis_x_pop_exit, ANIM_POP_X)
            "up" -> Triple(R.anim.shared_axis_y_push_enter, R.anim.shared_axis_y_push_exit, ANIM_PUSH_Y)
            "down" -> Triple(R.anim.shared_axis_y_pop_enter, R.anim.shared_axis_y_pop_exit, ANIM_POP_Y)
            else -> null
        }
        val (openEnter, openExit) = openTransition?.let { (enter, exit, jumpAnim) ->
            swipeJumpAnim = jumpAnim
            enter to exit
        } ?: run {
            swipeJumpAnim = ANIM_NONE
            0 to 0
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, openEnter, openExit)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(openEnter, openExit)
        }

        binding = ReaderActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        holdFirstReaderFrame()
        binding.setComposeOverlay()

        if (!viewModel.hasValidArgs) {
            finish()
            return
        }

        NotificationReceiver.dismissNotification(
            this,
            viewModel.mangaId.hashCode(),
            Notifications.ID_NEW_CHAPTERS,
        )

        if (intent.getBooleanExtra("swipe_jump", false)) {
            swipeJumpTapSuppressUntil = SystemClock.uptimeMillis() + 400
            openedViaSwipeJump = true
        } else {
            RandomReaderHistory.clear()
        }

        onBackPressedDispatcher.addCallback(this) {
            val previous = RandomReaderHistory.pop()
            if (previous == null) {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            } else {
                navigateToRandomHistory(previous)
            }
        }

        config = ReaderConfig()
        setMenuVisibility(viewModel.state.value.menuVisible)

        // Finish when incognito mode is disabled
        preferences.incognitoMode.changes()
            .drop(1)
            .onEach { if (!it) finish() }
            .launchIn(lifecycleScope)

        viewModel.state
            .map { it.initError }
            .distinctUntilChanged()
            .filterNotNull()
            .onEach(::setInitialChapterError)
            .launchIn(lifecycleScope)

        viewModel.state
            .map { it.isLoadingAdjacentChapter }
            .distinctUntilChanged()
            .onEach(::setProgressDialog)
            .launchIn(lifecycleScope)

        viewModel.state
            .map { it.manga }
            .distinctUntilChanged()
            .filterNotNull()
            .onEach { updateViewer() }
            .launchIn(lifecycleScope)

        viewModel.state
            .map { it.viewerChapters }
            .distinctUntilChanged()
            .filterNotNull()
            .onEach(::setChapters)
            .launchIn(lifecycleScope)

        viewModel.eventFlow
            .onEach { event ->
                when (event) {
                    ReaderViewModel.Event.ReloadViewerChapters -> {
                        viewModel.state.value.viewerChapters?.let(::setChapters)
                    }
                    ReaderViewModel.Event.ReloadViewer -> {
                        updateViewer()
                    }
                    ReaderViewModel.Event.PageChanged -> {
                        displayRefreshHost.flash()
                    }
                    is ReaderViewModel.Event.SetOrientation -> {
                        setOrientation(event.orientation)
                    }
                    is ReaderViewModel.Event.SavedImage -> {
                        onSaveImageResult(event.result)
                    }
                    is ReaderViewModel.Event.ShareImage -> {
                        onShareImageResult(event.uri, event.page)
                    }
                    is ReaderViewModel.Event.CopyImage -> {
                        onCopyImageResult(event.uri)
                    }
                    is ReaderViewModel.Event.SetCoverResult -> {
                        onSetAsCoverResult(event.result)
                    }
                    is ReaderViewModel.Event.SetChapterCoverResult -> {
                        onSetChapterCoverResult(event.result)
                    }
                }
            }
            .launchIn(lifecycleScope)
    }

    private fun ReaderActivityBinding.setComposeOverlay(): Unit = composeOverlay.setComposeContent {
        val state by viewModel.state.collectAsState()
        val showPageNumber by readerPreferences.showPageNumber.collectAsState()
        val settingsviewModel = remember {
            ReaderSettingsViewModel(
                readerState = viewModel.state,
                onChangeReadingMode = viewModel::setMangaReadingMode,
                onChangeOrientation = viewModel::setMangaOrientationType,
            )
        }
        val audioController = remember { Injekt.get<AudioPlayerController>() }
        val hasAudioSession = audioController.state.item != null
        val audioVisible = audioController.readerControlsVisible
        var showAudioSheet by rememberSaveable { mutableStateOf(false) }

        LaunchedEffect(hasAudioSession) {
            if (!hasAudioSession) showAudioSheet = false
        }

        Box(modifier = Modifier.fillMaxSize()) {
            if (!state.menuVisible && showPageNumber) {
                ReaderPageIndicator(
                    currentPage = state.currentPage,
                    totalPages = state.totalPages,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding(),
                    onClick = { toggleMenu() },
                )
            }

            ContentOverlay(state = state)

            AppBars(
                state = state,
                audioAvailable = hasAudioSession,
                audioVisible = audioVisible,
                onToggleAudio = {
                    if (audioVisible) {
                        audioController.hideReaderControls()
                    } else {
                        audioController.showReaderControls()
                    }
                },
                audioControls = if (hasAudioSession && audioVisible) {
                    {
                        AudioReaderFloatingBar(
                            compact = false,
                            onExpand = {},
                            onDismiss = audioController::hideReaderControls,
                            onOpenPlaylist = { showAudioSheet = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp),
                        )
                    }
                } else {
                    null
                },
            )

            if (!state.menuVisible && hasAudioSession && audioVisible) {
                AudioReaderFloatingBar(
                    compact = true,
                    onExpand = { setMenuVisibility(true) },
                    onDismiss = audioController::hideReaderControls,
                    onOpenPlaylist = { showAudioSheet = true },
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .navigationBarsPadding()
                        .padding(12.dp),
                )
            }
        }

        if (showAudioSheet) {
            AudioQuickPlaySheet(
                onDismiss = { showAudioSheet = false },
                onOpenWork = { item ->
                    showAudioSheet = false
                    startActivity(
                        Intent(this@ReaderActivity, MainActivity::class.java).apply {
                            action = Constants.SHOW_AUDIO_DETAIL
                            putExtra(Constants.AUDIO_WORK_EXTRA, item)
                            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        },
                    )
                },
            )
        }

        val onDismissRequest = viewModel::closeDialog
        when (state.dialog) {
            is ReaderViewModel.Dialog.Loading -> {
                AlertDialog(
                    onDismissRequest = {},
                    confirmButton = {},
                    text = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator()
                            Text(stringResource(MR.strings.loading))
                        }
                    },
                )
            }
            is ReaderViewModel.Dialog.Settings -> {
                ReaderSettingsDialog(
                    onDismissRequest = onDismissRequest,
                    onShowMenus = { setMenuVisibility(true) },
                    onHideMenus = { setMenuVisibility(false) },
                    viewModel = settingsviewModel,
                )
            }
            is ReaderViewModel.Dialog.ReadingModeSelect -> {
                ReadingModeSelectDialog(
                    onDismissRequest = onDismissRequest,
                    viewModel = settingsviewModel,
                    onChange = { stringRes ->
                        menuToggleToast?.cancel()
                        menuToggleToast = toast(stringRes)
                    },
                )
            }
            is ReaderViewModel.Dialog.OrientationModeSelect -> {
                OrientationSelectDialog(
                    onDismissRequest = onDismissRequest,
                    viewModel = settingsviewModel,
                    onChange = { stringRes ->
                        menuToggleToast?.cancel()
                        menuToggleToast = toast(stringRes)
                    },
                )
            }
            is ReaderViewModel.Dialog.PageActions -> {
                ReaderPageActionsDialog(
                    onDismissRequest = onDismissRequest,
                    onSetAsCover = viewModel::setAsCover,
                    onSetAsChapterCover = viewModel::setAsChapterCover.takeIf {
                        viewModel.manga?.isLocal() == true
                    },
                    onShare = viewModel::shareImage,
                    onSave = viewModel::saveImage,
                )
            }
            null -> {}
        }
    }

    /**
     * Called when a new intent is delivered to this singleTask activity, e.g. the user opens a
     * different chapter while the reader is already alive in the task. The existing instance keeps
     * its old ViewModel, so restart it with the new intent to load the requested chapter and its
     * stored progress instead of silently showing the previous chapter.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Guard against re-entry: once finish() is called this instance is finishing,
        // and some Android versions may re-deliver the intent to the finishing instance
        // before the fresh one is created. Without this the finish+start can loop.
        if (isFinishing) return
        restartingForJump = intent.hasExtra(SWIPE_DIRECTION_EXTRA)
        pendingJumpIntent = intent
        finish()
    }

    /**
     * Called when the user swipes horizontally in the webtoon viewer and routes the gesture to
     * the shared random pools.
     */
    fun onHorizontalSwipe(leftSwipe: Boolean) {
        // Left swipe picks from the current source's in-progress manga, right swipe from
        // the good doujin list, so both swipe directions jump somewhere useful.
        if (leftSwipe) {
            jumpToRandomManga(
                anim = ANIM_PUSH_X,
                direction = "left",
                targetSelector = viewModel::getRandomInProgressTarget,
            )
        } else {
            jumpToRandomManga(
                anim = ANIM_POP_X,
                direction = "right",
                targetSelector = viewModel::getRandomGoodDoujinTarget,
            )
        }
    }

    /**
     * Called when the user swipes vertically in a horizontal pager viewer and routes the gesture
     * to the same random pools used by the webtoon viewer.
     */
    fun onVerticalSwipe(upSwipe: Boolean) {
        // Same split as the webtoon swipe: forward direction picks from the current
        // source's in-progress manga, backward direction from the good doujin list.
        if (upSwipe) {
            jumpToRandomManga(
                anim = ANIM_PUSH_Y,
                direction = "up",
                targetSelector = viewModel::getRandomInProgressTarget,
            )
        } else {
            jumpToRandomManga(
                anim = ANIM_POP_Y,
                direction = "down",
                targetSelector = viewModel::getRandomGoodDoujinTarget,
            )
        }
    }

    private fun jumpToRandomManga(
        anim: Int,
        direction: String,
        targetSelector: suspend () -> Pair<Long, Long>?,
    ) {
        if (randomJumping) return
        randomJumping = true
        swipeJumpAnim = anim
        lifecycleScope.launch {
            val target = try {
                targetSelector()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Random manga jump failed" }
                null
            }
            if (target != null) {
                // Remember the chapter we jumped from so the next random jump doesn't land on
                // that same chapter; the manga's other unread chapters stay in the pools.
                val currentChapterId = viewModel.getCurrentChapterId()
                if (currentChapterId != null) {
                    viewModel.rememberSkippedChapter(viewModel.mangaId, currentChapterId)
                    RandomReaderHistory.push(
                        RandomReaderHistory.Entry(
                            mangaId = viewModel.mangaId,
                            chapterId = currentChapterId,
                            pageIndex = viewModel.getCurrentPageIndex(),
                            returnDirection = direction.oppositeSwipeDirection(),
                        ),
                    )
                }
                val intent = ReaderActivity.newIntent(
                    this@ReaderActivity,
                    target.first,
                    target.second,
                    swipeJump = true,
                )
                intent.putExtra(SWIPE_DIRECTION_EXTRA, direction)
                startActivity(intent)
            } else {
                randomJumping = false
                swipeJumpAnim = ANIM_NONE
                Toast.makeText(
                    this@ReaderActivity,
                    if (direction == "right" || direction == "down") {
                        stringResource(MR.strings.good_doujin_list_empty)
                    } else {
                        stringResource(MR.strings.information_no_entries_found)
                    },
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun navigateToRandomHistory(entry: RandomReaderHistory.Entry) {
        randomJumping = true
        val intent = ReaderActivity.newIntent(
            context = this,
            mangaId = entry.mangaId,
            chapterId = entry.chapterId,
            swipeJump = true,
            pageIndex = entry.pageIndex,
        ).apply {
            putExtra(SWIPE_DIRECTION_EXTRA, entry.returnDirection)
        }
        startActivity(intent)
    }

    private fun String.oppositeSwipeDirection(): String = when (this) {
        "left" -> "right"
        "right" -> "left"
        "up" -> "down"
        "down" -> "up"
        else -> this
    }

    /**
     * Called when the activity is destroyed. Cleans up the viewer, configuration and any view.
     */
    override fun onDestroy() {
        super.onDestroy()
        viewModel.state.value.viewer?.destroy()
        config = null
        menuToggleToast?.cancel()
        // Relaunch the reader for an onNewIntent-delivered jump only after this instance has
        // fully finished, so singleTask routes the intent to a brand-new activity instead of
        // re-delivering it to this finishing instance (which would swallow it and leave the
        // wrong chapter open).
        val jumpIntent = pendingJumpIntent
        pendingJumpIntent = null
        if (jumpIntent != null) {
            Handler(Looper.getMainLooper()).post {
                startActivity(jumpIntent)
            }
        }
    }

    override fun onPause() {
        lifecycleScope.launchNonCancellable {
            viewModel.updateHistory()
        }
        super.onPause()
    }

    /**
     * Set menu visibility again on activity resume to apply immersive mode again if needed.
     * Helps with rotations.
     */
    override fun onResume() {
        super.onResume()
        viewModel.restartReadTimer()
        setMenuVisibility(viewModel.state.value.menuVisible)
    }

    /**
     * Called when the window focus changes. It sets the menu visibility to the last known state
     * to apply immersive mode again if needed.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            setMenuVisibility(viewModel.state.value.menuVisible)
        }
    }

    override fun onProvideAssistContent(outContent: AssistContent) {
        super.onProvideAssistContent(outContent)
        assistUrl?.let { outContent.webUri = it.toUri() }
    }

    /**
     * Called when the user clicks the back key or the button on the toolbar. The call is
     * delegated to the presenter.
     */
    override fun finish() {
        viewModel.onActivityFinish()
        if (restartingForJump) {
            // A jump is relaunching the reader: the fresh instance's open transition drives
            // both directions (its exit anim moves this one out), so don't set a close
            // transition here. A second, opposite-direction close transition is what made
            // the swipe-in direction flip randomly.
            swipeJumpAnim = ANIM_NONE
        } else {
            // Returning from a random reader reverses the animation that opened it.
            val (closeEnter, closeExit) = when (swipeJumpAnim) {
                ANIM_PUSH_X -> R.anim.shared_axis_x_pop_enter to R.anim.shared_axis_x_pop_exit
                ANIM_POP_X -> R.anim.shared_axis_x_push_enter to R.anim.shared_axis_x_push_exit
                ANIM_PUSH_Y -> R.anim.shared_axis_y_pop_enter to R.anim.shared_axis_y_pop_exit
                ANIM_POP_Y -> R.anim.shared_axis_y_push_enter to R.anim.shared_axis_y_push_exit
                else -> R.anim.shared_axis_x_pop_enter to R.anim.shared_axis_x_pop_exit
            }
            swipeJumpAnim = ANIM_NONE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, closeEnter, closeExit)
            } else {
                @Suppress("DEPRECATION")
                overridePendingTransition(closeEnter, closeExit)
            }
        }
        super.finish()
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_N) {
            loadNextChapter()
            return true
        } else if (keyCode == KeyEvent.KEYCODE_P) {
            loadPreviousChapter()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    /**
     * Dispatches a key event. If the viewer doesn't handle it, call the default implementation.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val handled = viewModel.state.value.viewer?.handleKeyEvent(event) ?: false
        return handled || super.dispatchKeyEvent(event)
    }

    /**
     * Dispatches a generic motion event. If the viewer doesn't handle it, call the default
     * implementation.
     */
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val handled = viewModel.state.value.viewer?.handleGenericMotionEvent(event) ?: false
        return handled || super.dispatchGenericMotionEvent(event)
    }

    @Composable
    private fun ContentOverlay(state: ReaderViewModel.State) {
        val flashOnPageChange by readerPreferences.flashOnPageChange.collectAsState()

        val colorOverlayEnabled by readerPreferences.colorFilter.collectAsState()
        val colorOverlay by readerPreferences.colorFilterValue.collectAsState()
        val colorOverlayMode by readerPreferences.colorFilterMode.collectAsState()
        val colorOverlayBlendMode = remember(colorOverlayMode) {
            ReaderPreferences.ColorFilterMode.getOrNull(colorOverlayMode)?.second
        }

        ReaderContentOverlay(
            brightness = state.brightnessOverlayValue,
            color = colorOverlay.takeIf { colorOverlayEnabled },
            colorBlendMode = colorOverlayBlendMode,
        )

        if (flashOnPageChange) {
            DisplayRefreshHost(hostState = displayRefreshHost)
        }
    }

    @Composable
    fun AppBars(
        state: ReaderViewModel.State,
        audioAvailable: Boolean,
        audioVisible: Boolean,
        onToggleAudio: () -> Unit,
        audioControls: (@Composable () -> Unit)?,
    ) {
        if (!ifSourcesLoaded()) {
            return
        }

        val isHttpSource = viewModel.getSource() is HttpSource

        val cropBorderPaged by readerPreferences.cropBorders.collectAsState()
        val cropBorderWebtoon by readerPreferences.cropBordersWebtoon.collectAsState()
        val isPagerType = ReadingMode.isPagerType(viewModel.getMangaReadingMode())
        val cropEnabled = if (isPagerType) cropBorderPaged else cropBorderWebtoon

        val verticalNavigatorModes by readerPreferences.verticalNavigator.collectAsState()
        val verticalNavigator = verticalNavigatorModes.contains(
            ReadingMode.fromPreference(viewModel.getMangaReadingMode()),
        )
        val verticalNavigatorOnLeft by readerPreferences.verticalNavigatorOnLeft.collectAsState()
        val verticalNavigatorHeight by readerPreferences.verticalNavigatorHeight.collectAsState()

        ReaderAppBars(
            visible = state.menuVisible,
            audioControls = audioControls,

            mangaTitle = state.manga?.title,
            chapterTitle = state.currentChapter?.chapter?.name,
            navigateUp = onBackPressedDispatcher::onBackPressed,
            goodDoujinMarked = state.goodDoujinMarked,
            onToggleGoodDoujin = viewModel::toggleCurrentChapterGoodDoujin.takeIf { state.manga?.isLocal() == true },
            onOpenManga = ::openMangaScreen.takeIf { openedViaSwipeJump },
            onOpenInWebView = ::openChapterInWebView.takeIf { isHttpSource },
            onOpenInBrowser = ::openChapterInBrowser.takeIf { isHttpSource },
            onShare = ::shareChapter.takeIf { isHttpSource },

            chapterNavigatorType = if (!verticalNavigator) {
                if (state.viewer is R2LPagerViewer) {
                    ChapterNavigatorType.HORIZONTAL_RTL
                } else {
                    ChapterNavigatorType.HORIZONTAL_LTR
                }
            } else {
                if (verticalNavigatorOnLeft) {
                    ChapterNavigatorType.VERTICAL_LEFT
                } else {
                    ChapterNavigatorType.VERTICAL_RIGHT
                }
            },
            verticalNavigatorHeight = verticalNavigatorHeight / 100f,
            onNextChapter = ::loadNextChapter,
            enabledNext = state.viewerChapters?.nextChapter != null,
            onPreviousChapter = ::loadPreviousChapter,
            enabledPrevious = state.viewerChapters?.prevChapter != null,
            currentPage = state.currentPage,
            totalPages = state.totalPages,
            onPageIndexChange = {
                isScrollingThroughPages = true
                moveToPageIndex(it)
            },
            onPageIndexChangeFinished = {
                isScrollingThroughPages = false
            },

            readingMode = ReadingMode.fromPreference(
                viewModel.getMangaReadingMode(resolveDefault = false),
            ),
            onClickReadingMode = viewModel::openReadingModeSelectDialog,
            orientation = ReaderOrientation.fromPreference(
                viewModel.getMangaOrientation(resolveDefault = false),
            ),
            onClickOrientation = viewModel::openOrientationModeSelectDialog,
            cropEnabled = cropEnabled,
            onClickCropBorder = {
                val enabled = viewModel.toggleCropBorders()
                menuToggleToast?.cancel()
                menuToggleToast = toast(if (enabled) MR.strings.on else MR.strings.off)
            },
            audioAvailable = audioAvailable,
            audioVisible = audioVisible,
            onClickAudio = onToggleAudio,
            onClickSettings = viewModel::openSettingsDialog,
        )
    }

    /**
     * Sets the visibility of the menu according to [visible].
     */
    private fun setMenuVisibility(visible: Boolean) {
        viewModel.showMenus(visible)
        if (visible) {
            windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
        } else if (readerPreferences.fullscreen.get()) {
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    /**
     * Called from the presenter when a manga is ready. Used to instantiate the appropriate viewer.
     */
    private fun updateViewer() {
        val prevViewer = viewModel.state.value.viewer
        val newViewer = ReadingMode.toViewer(viewModel.getMangaReadingMode(), this)

        if (window.sharedElementEnterTransition is MaterialContainerTransform) {
            // Wait until transition is complete to avoid crash on API 26
            window.sharedElementEnterTransition.doOnEnd {
                setOrientation(viewModel.getMangaOrientation())
            }
        } else {
            setOrientation(viewModel.getMangaOrientation())
        }

        // Destroy previous viewer if there was one
        if (prevViewer != null) {
            prevViewer.destroy()
            binding.viewerContainer.removeAllViews()
        }
        removeLoadingIndicator()
        viewModel.onViewerLoaded(newViewer)
        updateViewerInset(readerPreferences.fullscreen.get(), readerPreferences.drawUnderCutout.get())
        binding.viewerContainer.addView(newViewer.getView())

        loadingIndicator = ReaderProgressIndicator(this)
        binding.readerContainer.addView(loadingIndicator)
    }

    private fun openMangaScreen() {
        viewModel.manga?.id?.let { id ->
            RandomReaderHistory.clear()
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    action = if (openedViaSwipeJump) {
                        Constants.SHOW_MANGA_PRESERVE_STACK
                    } else {
                        Constants.SHORTCUT_MANGA
                    }
                    putExtra(Constants.MANGA_EXTRA, id)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                },
            )
        }
    }

    private fun openChapterInWebView() {
        val manga = viewModel.manga ?: return
        val source = viewModel.getSource() ?: return
        assistUrl?.let {
            val intent = WebViewActivity.newIntent(this@ReaderActivity, it, source.id, manga.title)
            startActivity(intent)
        }
    }

    private fun openChapterInBrowser() {
        assistUrl?.let {
            openInBrowser(it.toUri(), forceDefaultBrowser = false)
        }
    }

    private fun shareChapter() {
        assistUrl?.let {
            val intent = it.toUri().toShareIntent(this, type = "text/plain")
            startActivity(intent)
        }
    }

    /**
     * Called from the presenter whenever a new [viewerChapters] have been set. It delegates the
     * method to the current viewer, but also set the subtitle on the toolbar, and
     * hides or disables the reader prev/next buttons if there's a prev or next chapter
     */
    @SuppressLint("RestrictedApi")
    private fun setChapters(viewerChapters: ViewerChapters) {
        viewModel.state.value.viewer?.let { viewer ->
            viewer.setChapters(viewerChapters)
            if (viewer !is WebtoonViewer) {
                onViewerContentReady()
            }
        }

        lifecycleScope.launchIO {
            viewModel.getChapterUrl()?.let { url ->
                assistUrl = url
            }
        }
    }

    /** Called by a viewer after its first visible frame is fully positioned. */
    fun onViewerContentReady() {
        val container = binding.readerContainer
        if (container.isInLayout) {
            container.post { releaseFirstReaderFrame(removeIndicator = true) }
        } else {
            releaseFirstReaderFrame(removeIndicator = true)
        }
    }

    fun onViewerLoadingDelayed() {
        releaseFirstReaderFrame(removeIndicator = false)
    }

    fun prepareViewerContentReveal() {
        loadingIndicator?.visibility = View.INVISIBLE
    }

    private fun holdFirstReaderFrame() {
        val listener = ViewTreeObserver.OnPreDrawListener {
            firstReaderFrameReady
        }
        firstReaderFrameGate = listener
        binding.root.viewTreeObserver.addOnPreDrawListener(listener)
    }

    private fun releaseFirstReaderFrame(removeIndicator: Boolean) {
        if (removeIndicator) removeLoadingIndicator()
        if (firstReaderFrameReady) return
        firstReaderFrameReady = true
        firstReaderFrameGate?.let { listener ->
            binding.root.viewTreeObserver
                .takeIf { it.isAlive }
                ?.removeOnPreDrawListener(listener)
        }
        firstReaderFrameGate = null
        binding.root.postInvalidateOnAnimation()
    }

    private fun removeLoadingIndicator() {
        val container = binding.readerContainer
        val indicator = loadingIndicator ?: return
        if (container.indexOfChild(indicator) != -1) {
            container.removeView(indicator)
        }
        loadingIndicator = null
    }

    /**
     * Called from the presenter if the initial load couldn't load the pages of the chapter. In
     * this case the activity is closed and a toast is shown to the user.
     */
    private fun setInitialChapterError(error: Throwable) {
        logcat(LogPriority.ERROR, error)
        finish()
        toast(error.message)
    }

    /**
     * Called from the presenter whenever it's loading the next or previous chapter. It shows or
     * dismisses a non-cancellable dialog to prevent user interaction according to the value of
     * [show]. This is only used when the next/previous buttons on the toolbar are clicked; the
     * other cases are handled with chapter transitions on the viewers and chapter preloading.
     */
    private fun setProgressDialog(show: Boolean) {
        if (show) {
            viewModel.showLoadingDialog()
        } else {
            viewModel.closeDialog()
        }
    }

    /**
     * Moves the viewer to the given page [index]. It does nothing if the viewer is null or the
     * page is not found.
     */
    private fun moveToPageIndex(index: Int) {
        val viewer = viewModel.state.value.viewer ?: return
        val currentChapter = viewModel.state.value.currentChapter ?: return
        val page = currentChapter.pages?.getOrNull(index) ?: return
        viewer.moveToPage(page)
    }

    /**
     * Tells the presenter to load the next chapter and mark it as active. The progress dialog
     * should be automatically shown.
     */
    private fun loadNextChapter() {
        lifecycleScope.launch {
            viewModel.loadNextChapter()
            moveToPageIndex(0)
        }
    }

    /**
     * Tells the presenter to load the previous chapter and mark it as active. The progress dialog
     * should be automatically shown.
     */
    private fun loadPreviousChapter() {
        lifecycleScope.launch {
            viewModel.loadPreviousChapter()
            moveToPageIndex(0)
        }
    }

    /**
     * Called from the viewer whenever a [page] is marked as active. It updates the values of the
     * bottom menu and delegates the change to the presenter.
     */
    fun onPageSelected(page: ReaderPage) {
        viewModel.onPageSelected(page)
    }

    /**
     * Called from the viewer once the first frame is aligned on [page]. Records the session
     * position without the display refresh a normal page selection triggers.
     */
    fun onInitialPageSelected(page: ReaderPage) {
        viewModel.onInitialPageSelected(page)
    }

    /**
     * Called from the viewer once the scroll has settled on [page] (webtoon) or a page has been
     * fully displayed (pager). The presenter persists the reading progress at this point.
     */
    fun onScrollSettled(page: ReaderPage) {
        viewModel.onScrollSettled(page)
    }

    /**
     * Called from the viewer whenever a [page] is long clicked. A bottom sheet with a list of
     * actions to perform is shown.
     */
    fun onPageLongTap(page: ReaderPage) {
        viewModel.openPageDialog(page)
    }

    /**
     * Called from the viewer when the given [chapter] should be preloaded. It should be called when
     * the viewer is reaching the beginning or end of a chapter or the transition page is active.
     */
    fun requestPreloadChapter(chapter: ReaderChapter) {
        lifecycleScope.launchIO { viewModel.preload(chapter) }
    }

    /**
     * Called from the viewer to toggle the visibility of the menu. It's implemented on the
     * viewer because each one implements its own touch and key events.
     */
    fun toggleMenu() {
        setMenuVisibility(!viewModel.state.value.menuVisible)
    }

    /**
     * Called from the viewer to show the menu.
     */
    fun showMenu() {
        if (!viewModel.state.value.menuVisible) {
            setMenuVisibility(true)
        }
    }

    /**
     * Called from the viewer to hide the menu.
     */
    fun hideMenu() {
        if (viewModel.state.value.menuVisible) {
            setMenuVisibility(false)
        }
    }

    /**
     * Called from the presenter when a page is ready to be shared. It shows Android's default
     * sharing tool.
     */
    private fun onShareImageResult(uri: Uri, page: ReaderPage) {
        val manga = viewModel.manga ?: return
        val chapter = page.chapter.chapter

        val intent = uri.toShareIntent(
            context = applicationContext,
            message = stringResource(MR.strings.share_page_info, manga.title, chapter.name, page.number),
        )
        startActivity(intent)
    }

    private fun onCopyImageResult(uri: Uri) {
        val clipboardManager = applicationContext.getSystemService<ClipboardManager>() ?: return
        val clipData = ClipData.newUri(applicationContext.contentResolver, "", uri)
        clipboardManager.setPrimaryClip(clipData)
    }

    /**
     * Called from the presenter when a page is saved or fails. It shows a message or logs the
     * event depending on the [result].
     */
    private fun onSaveImageResult(result: ReaderViewModel.SaveImageResult) {
        when (result) {
            is ReaderViewModel.SaveImageResult.Success -> {
                toast(MR.strings.picture_saved)
            }
            is ReaderViewModel.SaveImageResult.Error -> {
                logcat(LogPriority.ERROR, result.error)
            }
        }
    }

    /**
     * Called from the presenter when a page is set as cover or fails. It shows a different message
     * depending on the [result].
     */
    private fun onSetAsCoverResult(result: ReaderViewModel.SetAsCoverResult) {
        toast(
            when (result) {
                Success -> MR.strings.cover_updated
                AddToLibraryFirst -> MR.strings.notification_first_add_to_library
                Error -> MR.strings.notification_cover_update_failed
            },
        )
    }

    private fun onSetChapterCoverResult(result: ReaderViewModel.SetChapterCoverStatus) {
        toast(
            when (result) {
                ReaderViewModel.SetChapterCoverStatus.Success -> MR.strings.chapter_cover_updated
                ReaderViewModel.SetChapterCoverStatus.Error -> MR.strings.notification_cover_update_failed
            },
        )
    }

    /**
     * Forces the user preferred [orientation] on the activity.
     */
    private fun setOrientation(orientation: Int) {
        val newOrientation = ReaderOrientation.fromPreference(orientation)
        if (newOrientation.flag != requestedOrientation) {
            requestedOrientation = newOrientation.flag
        }
    }

    /**
     * Updates viewer inset depending on fullscreen reader preferences.
     */
    private fun updateViewerInset(fullscreen: Boolean, drawUnderCutout: Boolean) {
        if (!::binding.isInitialized) return
        val view = binding.viewerContainer

        view.applyInsetsPadding(ViewCompat.getRootWindowInsets(view), fullscreen, drawUnderCutout)
        ViewCompat.setOnApplyWindowInsetsListener(view) { view, windowInsets ->
            view.applyInsetsPadding(windowInsets, fullscreen, drawUnderCutout)
            windowInsets
        }
    }

    private fun View.applyInsetsPadding(
        windowInsets: WindowInsetsCompat?,
        fullscreen: Boolean,
        drawUnderCutout: Boolean,
    ) {
        val insets = when {
            !fullscreen -> windowInsets?.getInsets(WindowInsetsCompat.Type.systemBars())
            !drawUnderCutout -> windowInsets?.getInsets(WindowInsetsCompat.Type.displayCutout())
            else -> null
        }
            ?: Insets.NONE

        setPadding(insets.left, insets.top, insets.right, insets.bottom)
    }

    /**
     * Class that handles the user preferences of the reader.
     */
    private inner class ReaderConfig {

        private fun getCombinedPaint(grayscale: Boolean, invertedColors: Boolean): Paint {
            return Paint().apply {
                colorFilter = ColorMatrixColorFilter(
                    ColorMatrix().apply {
                        if (grayscale) {
                            setSaturation(0f)
                        }
                        if (invertedColors) {
                            postConcat(
                                ColorMatrix(
                                    floatArrayOf(
                                        -1f, 0f, 0f, 0f, 255f,
                                        0f, -1f, 0f, 0f, 255f,
                                        0f, 0f, -1f, 0f, 255f,
                                        0f, 0f, 0f, 1f, 0f,
                                    ),
                                ),
                            )
                        }
                    },
                )
            }
        }

        private val grayBackgroundColor = Color.rgb(0x20, 0x21, 0x25)

        private fun readerBackgroundColor(theme: Int): Int {
            return when (theme) {
                0 -> Color.WHITE
                2 -> grayBackgroundColor
                3 -> automaticBackgroundColor()
                else -> Color.BLACK
            }
        }

        /*
         * Initializes the reader subscriptions.
         */
        init {
            binding.readerContainer.setBackgroundColor(
                readerBackgroundColor(readerPreferences.readerTheme.get()),
            )
            readerPreferences.readerTheme.changes()
                .onEach { theme ->
                    binding.readerContainer.setBackgroundColor(readerBackgroundColor(theme))
                }
                .launchIn(lifecycleScope)

            preferences.displayProfile.changes()
                .onEach { setDisplayProfile(it) }
                .launchIn(lifecycleScope)

            readerPreferences.keepScreenOn.changes()
                .onEach(::setKeepScreenOn)
                .launchIn(lifecycleScope)

            readerPreferences.customBrightness.changes()
                .onEach(::setCustomBrightness)
                .launchIn(lifecycleScope)

            combine(
                readerPreferences.grayscale.changes(),
                readerPreferences.invertedColors.changes(),
            ) { grayscale, invertedColors -> grayscale to invertedColors }
                .onEach { (grayscale, invertedColors) ->
                    setLayerPaint(grayscale, invertedColors)
                }
                .launchIn(lifecycleScope)

            combine(
                readerPreferences.fullscreen.changes(),
                readerPreferences.drawUnderCutout.changes(),
            ) { fullscreen, drawUnderCutout -> fullscreen to drawUnderCutout }
                .onEach { (fullscreen, drawUnderCutout) ->
                    updateViewerInset(fullscreen, drawUnderCutout)
                }
                .launchIn(lifecycleScope)
        }

        /**
         * Picks background color for [ReaderActivity] based on light/dark theme preference
         */
        private fun automaticBackgroundColor(): Int {
            return if (baseContext.isNightMode()) {
                grayBackgroundColor
            } else {
                Color.WHITE
            }
        }

        /**
         * Sets the display profile to [path].
         */
        private fun setDisplayProfile(path: String) {
            val file = UniFile.fromUri(baseContext, path.toUri())
            if (file != null && file.exists()) {
                val inputStream = file.openInputStream()
                val outputStream = ByteArrayOutputStream()
                inputStream.use { input ->
                    outputStream.use { output ->
                        input.copyTo(output)
                    }
                }
                val data = outputStream.toByteArray()
                SubsamplingScaleImageView.setDisplayProfile(data)
                TachiyomiImageDecoder.displayProfile = data
            }
        }

        /**
         * Sets the keep screen on mode according to [enabled].
         */
        private fun setKeepScreenOn(enabled: Boolean) {
            if (enabled) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        /**
         * Sets the custom brightness overlay according to [enabled].
         */
        private fun setCustomBrightness(enabled: Boolean) {
            if (enabled) {
                readerPreferences.customBrightnessValue.changes()
                    .sample(0.1.seconds)
                    .onEach(::setCustomBrightnessValue)
                    .launchIn(lifecycleScope)
            } else {
                setCustomBrightnessValue(0)
            }
        }

        /**
         * Sets the brightness of the screen. Range is [-75, 100].
         * From -75 to -1 a semi-transparent black view is overlaid with the minimum brightness.
         * From 1 to 100 it sets that value as brightness.
         * 0 sets system brightness and hides the overlay.
         */
        private fun setCustomBrightnessValue(value: Int) {
            // Calculate and set reader brightness.
            val readerBrightness = when {
                value > 0 -> {
                    value / 100f
                }
                value < 0 -> {
                    0.01f
                }
                else -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
            window.attributes = window.attributes.apply { screenBrightness = readerBrightness }

            viewModel.setBrightnessOverlayValue(value)
        }
        private fun setLayerPaint(grayscale: Boolean, invertedColors: Boolean) {
            val paint = if (grayscale || invertedColors) getCombinedPaint(grayscale, invertedColors) else null
            binding.viewerContainer.setLayerType(LAYER_TYPE_HARDWARE, paint)
        }
    }
}
