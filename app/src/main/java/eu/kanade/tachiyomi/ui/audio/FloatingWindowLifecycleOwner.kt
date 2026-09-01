package eu.kanade.tachiyomi.ui.audio

import android.view.View
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Supplies the owners a ComposeView looks up when it is attached.
 *
 * A window added through [android.view.WindowManager] has no activity behind it, so nothing
 * provides these and the composition cannot start. This owner is driven by hand instead: [start]
 * before the view is attached, [stop] after it is removed.
 *
 * There is no saved state to restore across process death, so the registry starts empty and the
 * overlay must keep its own state outside the composition rather than in `rememberSaveable`.
 */
internal class FloatingWindowLifecycleOwner :
    LifecycleOwner,
    SavedStateRegistryOwner,
    ViewModelStoreOwner,
    OnBackPressedDispatcherOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    override val viewModelStore: ViewModelStore = ViewModelStore()

    // The window holds nothing that reacts to back, but the dispatcher is still provided so no
    // component reading LocalOnBackPressedDispatcherOwner has to fall back to nothing.
    override val onBackPressedDispatcher = OnBackPressedDispatcher()

    /**
     * Publishes this owner to [view]'s tree. It has to happen before the view is attached, so the
     * composition that runs on the first frame already finds everything it looks up.
     */
    fun attachTo(view: View) {
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeViewModelStoreOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
        view.setViewTreeOnBackPressedDispatcherOwner(this)
    }

    /** Reaches the resumed state, which is what a visible window has to be in. */
    fun start() {
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    /** Reaches the destroyed state and drops the store, so nothing outlives the window. */
    fun stop() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        viewModelStore.clear()
    }
}
