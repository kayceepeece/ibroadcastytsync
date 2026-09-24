package ibytsync.android

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ibytsync.core.settings.SettingsStore
import ibytsync.core.storage.DiskStore
import ibytsync.core.upload.DeviceCode
import ibytsync.core.upload.IBroadcastOAuth
import ibytsync.core.upload.PollResult
import ibytsync.core.upload.Tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Holds the device-code sign-in across rotation. The whole flow is a multi-minute
 * poll, so it must not live in Activity-scoped state that a rotation would cancel.
 */
class LoginViewModel(
    private val context: Context,
    private val settings: SettingsStore,
    private val hooks: AndroidPipelineHooks,
    private val onAccountSwitch: () -> Unit,
    private val onLibraryUpdated: () -> Unit
) : ViewModel() {

    private val _isDialogOpen = MutableStateFlow(false)
    val isDialogOpen: StateFlow<Boolean> = _isDialogOpen

    private val _deviceCode = MutableStateFlow<DeviceCode?>(null)
    val deviceCode: StateFlow<DeviceCode?> = _deviceCode

    private val _statusMessage = MutableStateFlow("")
    val statusMessage: StateFlow<String> = _statusMessage

    private val _secondsLeft = MutableStateFlow<Int?>(null)
    val secondsLeft: StateFlow<Int?> = _secondsLeft

    /** One-shot message for the host UI, e.g. a sign-in that succeeded but couldn't load the library. */
    private val _warning = MutableStateFlow<String?>(null)
    val warning: StateFlow<String?> = _warning

    fun consumeWarning() { _warning.value = null }

    private var pollJob: Job? = null

    val isWaiting: Boolean
        get() = pollJob?.isActive == true

    fun start() {
        _isDialogOpen.value = true
        if (pollJob?.isActive == true) return
        _deviceCode.value = null
        _secondsLeft.value = null
        _statusMessage.value = "Getting a sign-in code…"
        pollJob = viewModelScope.launch {
            val dc = try {
                withContext(Dispatchers.IO) { IBroadcastOAuth.deviceCode(settings.getClientId()) }
            } catch (e: Exception) {
                _statusMessage.value = "Couldn't start sign-in: ${e.message?.take(120)}"
                return@launch
            }
            _deviceCode.value = dc
            _statusMessage.value = "Now approve this code in your browser."
            runPollLoop(dc)
        }
    }

    fun dismiss() {
        pollJob?.cancel()
        pollJob = null
        _isDialogOpen.value = false
        _secondsLeft.value = null
    }

    private suspend fun runPollLoop(dc: DeviceCode) {
        var interval = dc.intervalSec.coerceAtLeast(1)
        val deadline = System.currentTimeMillis() + dc.expiresInSec * 1000L
        delay(interval * 1000L)
        while (viewModelScope.isActive) {
            if (System.currentTimeMillis() >= deadline) {
                _statusMessage.value = "That code expired — sign in again"
                _secondsLeft.value = null
                return
            }
            _secondsLeft.value = (((deadline - System.currentTimeMillis()) + 999) / 1000).toInt()
            when (val r = withContext(Dispatchers.IO) {
                IBroadcastOAuth.pollOnce(settings.getClientId(), dc.deviceCode, interval)
            }) {
                PollResult.Pending -> {}
                is PollResult.SlowDown -> interval = r.nextIntervalSec
                is PollResult.Success -> {
                    completeSignIn(r.tokens)
                    return
                }
                is PollResult.Terminal -> {
                    _statusMessage.value = listOfNotNull(r.headline, r.serverDetail)
                        .joinToString(" — ")
                    _secondsLeft.value = null
                    return
                }
            }
            delay(interval * 1000L)
        }
    }

    private suspend fun completeSignIn(tokens: Tokens) {
        settings.setTokens(tokens.accessToken, tokens.refreshToken, tokens.expiresIn)
        _statusMessage.value = "Signed in — syncing your library…"
        _secondsLeft.value = null
        try {
            val status = withContext(Dispatchers.IO) { IBroadcastOAuth.fetchStatus(tokens.accessToken) }
            val previousAccount = settings.getAccountId()
            if (status != null && previousAccount.isNotEmpty() && status.accountId != previousAccount) {
                onAccountSwitch()
                DiskStore.clearLibrary(context)
            }
            status?.let { settings.setAccountId(it.accountId) }
            // Drop the previous snapshot before fetching: if the fetch fails we must not run
            // duplicate detection against the wrong account's library.
            hooks.updateLibraryCache(null)
            val snapshot = withContext(Dispatchers.IO) { IBroadcastOAuth.fetchLibrary(tokens.accessToken) }
            if (snapshot != null) {
                hooks.updateLibraryCache(snapshot)
                DiskStore.saveLibrary(context, snapshot)
                onLibraryUpdated()
            }
        } catch (e: Exception) {
            Log.e("LoginViewModel", "Post-login library sync failed: ${e.message}", e)
            _warning.value = "Signed in, but your library didn't load — duplicates may be missed"
            return
        }
        delay(600)
        _isDialogOpen.value = false
    }
}
