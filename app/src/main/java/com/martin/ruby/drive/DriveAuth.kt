package com.martin.ruby.drive

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Gets permission to use the user's Google Drive, through Google Play services.
 *
 * Ruby asks for one scope only, `drive.appdata`: a hidden folder that only Ruby can see,
 * not the user's other files. The first time, Google shows an account picker and a
 * consent screen ([Result.NeedsConsent]); after that, a token comes back silently, which
 * is what lets scheduled backups run without the user.
 *
 * Google recognizes Ruby by its package name and signing certificate (SHA-1), set up as
 * an Android client in Google Cloud. There's no secret in the app.
 */
object DriveAuth {

    const val SCOPE = "https://www.googleapis.com/auth/drive.appdata"

    sealed interface Result {
        class Token(val accessToken: String) : Result
        /** The user has to pick an account and agree; launch this, then use [tokenFromIntent]. */
        class NeedsConsent(val pendingIntent: PendingIntent) : Result
    }

    /** Thrown with a plain-English reason when Google won't authorize Ruby. */
    class Failed(message: String, cause: Throwable? = null) : Exception(message, cause)

    private fun request() = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(SCOPE)))
        .build()

    suspend fun authorize(context: Context): Result = try {
        val result = Identity.getAuthorizationClient(context).authorize(request()).await()
        val pending = result.pendingIntent
        val token = result.accessToken
        when {
            result.hasResolution() && pending != null -> Result.NeedsConsent(pending)
            token != null -> Result.Token(token)
            else -> throw Failed("Google didn't return access to Drive.")
        }
    } catch (e: ApiException) {
        throw explain(e)
    }

    /** The token after the user went through the consent screen. */
    fun tokenFromIntent(context: Context, data: Intent?): String = try {
        Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data).accessToken
            ?: throw Failed("Google didn't return access to Drive.")
    } catch (e: ApiException) {
        throw explain(e)
    }

    /**
     * After Drive says a token is no longer valid: forgets it and asks for a new one,
     * silently. Throws [Failed] if the user has to agree again (e.g. access was removed,
     * or, while the Google project is in testing mode, about a week has passed).
     */
    suspend fun refresh(context: Context, oldToken: String): String {
        withContext(Dispatchers.IO) { runCatching { GoogleAuthUtil.clearToken(context, oldToken) } }
        return when (val result = authorize(context)) {
            is Result.Token -> result.accessToken
            is Result.NeedsConsent -> throw Failed("Google Drive access needs approving again. Open Backup and tap a Drive button.")
        }
    }

    private fun explain(e: ApiException): Failed = Failed(
        when (e.statusCode) {
            CommonStatusCodes.DEVELOPER_ERROR ->
                "Google didn't recognize Ruby (error 10). In Google Cloud, check the Android client's " +
                    "package name (com.martin.ruby) and SHA-1, and that you're a test user."
            CommonStatusCodes.NETWORK_ERROR -> "No internet connection."
            CommonStatusCodes.CANCELED -> "Google Drive access wasn't allowed."
            else -> "Google sign-in failed (error ${e.statusCode}): ${e.message}"
        },
        e,
    )
}

/** Waits for a Play services task without blocking a thread. */
internal suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
