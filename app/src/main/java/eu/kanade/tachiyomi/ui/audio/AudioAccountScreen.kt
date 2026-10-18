package eu.kanade.tachiyomi.ui.audio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.base.BasePreferences
import eu.kanade.presentation.audio.AudioAccountContent
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.audio.AudioAccountSync
import eu.kanade.tachiyomi.data.audio.AudioAuthException
import eu.kanade.tachiyomi.data.audio.AudioBackend
import eu.kanade.tachiyomi.data.audio.KikoeruApi
import eu.kanade.tachiyomi.util.system.openInBrowser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * The account page: who is signed in, which email the account holds, and the two things the app
 * can change about it.
 *
 * Binding an email is deliberately not here. The backend's flow is "copy an address and a code,
 * send a mail from your own mailbox, come back and wait for the poll" — three steps across two
 * apps, for something the website already does in a browser where the mailbox and the password
 * manager live. [onOpenWebSettings] hands that half to the browser and keeps this page to the two
 * actions that are a single request each.
 */
class AudioAccountScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val viewModel = viewModel<AudioAccountViewModel>()
        val state by viewModel.state.collectAsState()

        LaunchedEffect(Unit) {
            viewModel.load()
        }

        AudioAccountContent(
            state = state,
            bottomBar = { AudioMiniPlayerNavigationBar() },
            navigateUp = navigator::pop,
            onRetry = viewModel::load,
            onChangePassword = viewModel::changePassword,
            onUnlinkEmail = viewModel::unlinkEmail,
            onOpenWebSettings = { context.openInBrowser(viewModel.webSettingsUrl) },
            onLogout = {
                viewModel.logout()
                navigator.pop()
            },
            onDismissPasswordResult = viewModel::clearPasswordResult,
            onDismissActionError = viewModel::clearActionError,
        )
    }
}

data class AudioAccountState(
    val username: String = "",
    /** The bound address, or null when the account has none. */
    val email: String? = null,
    /** True while the profile request is in flight; the rows above it still render. */
    val loading: Boolean = true,
    /** The profile request failed. Only affects the email row: the rest is already known. */
    val loadFailed: Boolean = false,
    /** The backend refuses a password change and an email link on a guest account. */
    val guest: Boolean = false,
    val changingPassword: Boolean = false,
    /** The last password change succeeded; the dialog switches to its done state. */
    val passwordChanged: Boolean = false,
    val unlinking: Boolean = false,
    /** Refusal line for the last action; empty means "failed, but the backend said nothing". */
    val actionError: String? = null,
    /** Whether [actionError] is the backend's own sentence or a transport failure. */
    val actionErrorFromServer: Boolean = false,
)

class AudioAccountViewModel(
    private val api: KikoeruApi = Injekt.get(),
    private val basePreferences: BasePreferences = Injekt.get(),
    private val accountSync: AudioAccountSync = Injekt.get(),
) : ViewModel() {

    private val _state = MutableStateFlow(
        AudioAccountState(username = basePreferences.audioUsername.get()),
    )
    val state: StateFlow<AudioAccountState> = _state.asStateFlow()

    /**
     * Guards [load] against a second tap on retry. Kept apart from [AudioAccountState.loading]
     * because that one starts true — the email is unknown until the request lands, and the row
     * must not read "未绑定" for the frame before it does.
     */
    private var profileInFlight = false

    /**
     * The website's settings page on whichever host the section is pointed at, so a user who
     * picked a mirror is not sent to a different site to manage the same account.
     */
    val webSettingsUrl: String
        get() {
            val siteHost = AudioBackend.fromUrl(basePreferences.audioBackendUrl.get())?.siteHost
                ?: AudioBackend.DEFAULT.siteHost
            return "https://$siteHost/settings"
        }

    /**
     * Reads the account as the backend holds it.
     *
     * The name is already stored, so the page renders signed in before this lands; what the
     * request actually answers is the email and the group, neither of which is kept on disk —
     * the website can bind an address while the app is closed, and a stale copy would then
     * disagree with the one place the user manages it.
     */
    fun load() {
        if (profileInFlight) return
        profileInFlight = true
        _state.update { it.copy(loading = true, loadFailed = false) }
        viewModelScope.launchIO {
            try {
                val user = api.fetchProfile()
                _state.update {
                    it.copy(
                        username = user.name,
                        email = user.email?.takeIf { email -> email.isNotBlank() },
                        // The website treats the account named "guest" as the guest account; the
                        // group field is the same thing stated twice, so either one counts.
                        guest = user.group.equals("guest", ignoreCase = true) ||
                            user.name.equals("guest", ignoreCase = true),
                        loading = false,
                        loadFailed = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Audio account profile failed" }
                // A 401 is already handled by the client, which drops the stored session. Reading
                // the name back from the preference keeps this page from showing an account that
                // no longer has a token behind it.
                _state.update {
                    it.copy(
                        username = basePreferences.audioUsername.get(),
                        loading = false,
                        loadFailed = true,
                    )
                }
            } finally {
                profileInFlight = false
            }
        }
    }

    fun changePassword(newPassword: String) {
        if (_state.value.changingPassword) return
        _state.update {
            it.copy(
                changingPassword = true,
                passwordChanged = false,
                actionError = null,
                actionErrorFromServer = false,
            )
        }
        viewModelScope.launchIO {
            try {
                api.changePassword(newPassword)
                _state.update { it.copy(changingPassword = false, passwordChanged = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Audio password change failed" }
                val refusal = e as? AudioAuthException
                _state.update {
                    it.copy(
                        changingPassword = false,
                        actionError = refusal?.message ?: "",
                        actionErrorFromServer = refusal?.message != null,
                    )
                }
            }
        }
    }

    fun unlinkEmail() {
        if (_state.value.unlinking) return
        _state.update { it.copy(unlinking = true, actionError = null, actionErrorFromServer = false) }
        viewModelScope.launchIO {
            try {
                api.unlinkEmail()
                _state.update { it.copy(unlinking = false, email = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Audio email unlink failed" }
                val refusal = e as? AudioAuthException
                _state.update {
                    it.copy(
                        unlinking = false,
                        actionError = refusal?.message ?: "",
                        actionErrorFromServer = refusal?.message != null,
                    )
                }
            }
        }
    }

    fun logout() {
        accountSync.signOut()
    }

    /** The dialog is reopened for another change, so the previous outcome must not linger. */
    fun clearPasswordResult() {
        _state.update {
            it.copy(passwordChanged = false, actionError = null, actionErrorFromServer = false)
        }
    }

    fun clearActionError() {
        _state.update { it.copy(actionError = null, actionErrorFromServer = false) }
    }
}
