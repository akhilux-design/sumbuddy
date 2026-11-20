package com.expenses.app.groups.auth

import android.app.Activity
import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.Credential
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialOption
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.expenses.app.groups.api.ApiResult
import com.expenses.app.groups.api.AuthResponse
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import kotlinx.coroutines.withTimeoutOrNull

/** What asking Google for an ID token gave. */
sealed interface GoogleResult {
    /** The person chose an account; [idToken] goes to `/v1/auth/google`. */
    data class Token(val idToken: String) : GoogleResult

    /** The person closed the sheet, or (for the automatic sheet) the phone has no account to offer. */
    data object Cancelled : GoogleResult

    /** Credential Manager failed. Nothing about the error is kept. */
    data object Failed : GoogleResult
}

/** Gets a Google ID token for the app's web client id. [GoogleSignIn] is the real one; tests pass a fake. */
interface GoogleIdTokens {
    /** False while no web client id is set (no Google project yet): sign-in fails at once. */
    val configured: Boolean

    /**
     * Shows Google's account sheet over [activity] and returns the chosen account's ID token, bound to [nonce].
     *
     * @param auto true for the sheet that may appear by itself when Sign in opens; false for the "Continue with
     * Google" button.
     * @param autoSelect for the automatic sheet only: true lets Google sign in with the one account that signed in to
     * the app before, without a tap. False after the person signed out.
     */
    suspend fun idToken(activity: Activity, nonce: String, auto: Boolean, autoSelect: Boolean): GoogleResult

    /**
     * The person signed out or deleted the account: tells every credential provider to forget this app's session, so
     * Google's sheet does not pick the account by itself next time. Never fails; an error is dropped and not logged.
     */
    suspend fun clearState()
}

/** The two Credential Manager calls of [GoogleSignIn]. [AndroidCredentials] is the real one; tests pass a fake. */
interface CredentialCalls {
    /** Shows the sheet for [request] over [activity] and returns the credential chosen. */
    suspend fun get(activity: Activity, request: GetCredentialRequest): Credential

    /** `clearCredentialState`: every provider forgets this app's session. */
    suspend fun clear()
}

/** [CredentialCalls] through Android's Credential Manager. Only the application context is kept. */
class AndroidCredentials(context: Context) : CredentialCalls {
    private val appContext: Context = context.applicationContext

    override suspend fun get(activity: Activity, request: GetCredentialRequest): Credential =
        CredentialManager.create(activity).getCredential(activity, request).credential

    override suspend fun clear() {
        CredentialManager.create(appContext).clearCredentialState(ClearCredentialStateRequest())
    }
}

/**
 * Google sign-in through Android's Credential Manager (docs/server.md 9.2), as Android's guide describes it. The
 * automatic sheet asks twice with [GetGoogleIdOption]: first only for accounts that signed in to the app before (one
 * of them is picked without a tap unless the person signed out), then, when there is none, for every Google account
 * on the phone. The button uses [GetSignInWithGoogleOption], which also works on phones where the automatic sheet has
 * nothing to show.
 *
 * @param webClientId the OAuth web client id (`BuildConfig.GOOGLE_WEB_CLIENT_ID`); blank turns sign-in off.
 */
class GoogleSignIn(private val webClientId: String, private val credentials: CredentialCalls) : GoogleIdTokens {
    override val configured: Boolean get() = webClientId.isNotBlank()

    override suspend fun idToken(activity: Activity, nonce: String, auto: Boolean, autoSelect: Boolean): GoogleResult {
        if (!configured) return GoogleResult.Failed
        if (!auto) return ask(activity, GetSignInWithGoogleOption.Builder(webClientId).setNonce(nonce).build()) ?: GoogleResult.Failed
        // The same nonce for both: the server's nonce is good for 5 minutes and used up only by a sign-in.
        return ask(activity, googleIdOption(nonce, authorisedOnly = true, autoSelect = autoSelect))
            ?: ask(activity, googleIdOption(nonce, authorisedOnly = false, autoSelect = false))
            ?: GoogleResult.Cancelled
    }

    override suspend fun clearState() {
        try {
            withTimeoutOrNull(CLEAR_TIMEOUT_MS) { credentials.clear() }
        } catch (_: ClearCredentialException) {
            // A provider that cannot clear (no Play services, for one) must not stop the sign-out.
        }
    }

    private fun googleIdOption(nonce: String, authorisedOnly: Boolean, autoSelect: Boolean): GetGoogleIdOption =
        GetGoogleIdOption.Builder()
            .setServerClientId(webClientId)
            .setFilterByAuthorizedAccounts(authorisedOnly)
            .setAutoSelectEnabled(autoSelect)
            .setNonce(nonce)
            .build()

    /** One request. Null when the phone has no account to offer for it, so the caller can ask more widely. */
    private suspend fun ask(activity: Activity, option: CredentialOption): GoogleResult? {
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        return try {
            val credential = credentials.get(activity, request)
            if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                GoogleResult.Token(GoogleIdTokenCredential.createFrom(credential.data).idToken)
            } else {
                GoogleResult.Failed
            }
        } catch (_: GetCredentialCancellationException) {
            GoogleResult.Cancelled
        } catch (_: NoCredentialException) {
            null
        } catch (_: GetCredentialException) {
            GoogleResult.Failed
        } catch (_: GoogleIdTokenParsingException) {
            GoogleResult.Failed
        }
    }

    private companion object {
        /** Sign-out and account deletion wait at most this long for the providers. */
        const val CLEAR_TIMEOUT_MS = 5_000L
    }
}

/**
 * Sign-in against the local development server without Google (docs/server.md 4.4 `/v1/auth/dev`). The real one
 * lives in `src/debug`; release builds get one that is never [available] and contain no code that calls the route.
 */
interface DevSignIn {
    /** True in debug builds. */
    val available: Boolean

    /** Signs in as the development user [devUser] (`^[a-z0-9]{1,20}$`), made with [name] the first time. */
    suspend fun signIn(devUser: String, name: String, installationId: String): ApiResult<AuthResponse>
}
