package com.martin.ruby.backup

import android.content.Context
import com.martin.ruby.drive.DriveAuth
import com.martin.ruby.drive.DriveClient
import com.martin.ruby.log.SensingLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Runs a restore for the whole app, not for the Backup screen, so leaving the screen (or
 * the screen rotating) doesn't stop it. The screen shows [state], including the result
 * after coming back.
 */
object RestoreSession {

    sealed interface State {
        data object Idle : State

        /** Replacing memory with a backup of [messageCount] messages. */
        data class Running(val messageCount: Int) : State

        data class Done(val count: Int, val finishedMillis: Long) : State

        data class Failed(val why: String) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Starts restoring [unlocked]. Returns false (and does nothing) if a restore is already running. */
    fun start(context: Context, unlocked: BackupManager.Unlocked): Boolean {
        if (_state.value is State.Running) return false
        val manager = BackupManager(context.applicationContext)
        _state.value = State.Running(unlocked.messageCount)
        scope.launch {
            _state.value = try {
                State.Done(manager.restore(unlocked), System.currentTimeMillis())
            } catch (e: Exception) {
                val why = backupFailureText(e)
                SensingLog.add("Restore didn't finish: $why")
                State.Failed(why)
            }
        }
        return true
    }

    /** Clears a finished result from the screen. */
    fun dismiss() {
        if (_state.value !is State.Running) _state.value = State.Idle
    }
}

/** The message shown (and kept in the backup history) when a backup or restore action fails. */
fun backupFailureText(e: Exception): String = when (e) {
    is BackupCrypto.CantOpen, is DriveAuth.Failed, is DriveClient.Error -> e.message ?: e.javaClass.simpleName
    is java.io.IOException -> "Couldn't reach Google Drive: ${e.message ?: "check the internet connection"}."
    else -> "Something went wrong: ${e.message ?: e.javaClass.simpleName}"
}
