package com.expenses.app.groups

import android.app.Activity
import androidx.credentials.Credential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.ClearCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.expenses.app.groups.auth.CredentialCalls
import com.expenses.app.groups.auth.GoogleResult
import com.expenses.app.groups.auth.GoogleSignIn
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import java.util.Base64
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * Credential Manager as the test sets it: each request gets the next answer, a credential or an exception to throw.
 * Every request is kept.
 */
private class FakeCredentials(vararg answers: Any) : CredentialCalls {
    private val answers = ArrayDeque(answers.toList())

    /** Every request, in order. */
    val requests = mutableListOf<GetCredentialRequest>()

    /** What [clear] does: nothing by default. */
    var onClear: suspend () -> Unit = {}

    var clears = 0

    override suspend fun get(activity: Activity, request: GetCredentialRequest): Credential {
        requests += request
        when (val answer = answers.removeFirst()) {
            is Credential -> return answer
            is Exception -> throw answer
            else -> error("not an answer: $answer")
        }
    }

    override suspend fun clear() {
        clears++
        onClear()
    }
}

@RunWith(RobolectricTestRunner::class)
class GoogleSignInTest {
    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).get()

    /** Shaped like a JWT, which the Google library checks; the server checks the rest. */
    private val idToken = listOf("""{"alg":"RS256","typ":"JWT"}""", """{"sub":"1","email":"alice@example.com"}""", "signature")
        .joinToString(".") { Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray()) }
    private val token = GoogleIdTokenCredential.Builder().setId("alice@example.com").setIdToken(idToken).build()

    private fun GetCredentialRequest.googleId(): GetGoogleIdOption = credentialOptions.single() as GetGoogleIdOption

    @Test fun theAutomaticSheetFirstOffersAccountsThatSignedInBefore() = runTest {
        val credentials = FakeCredentials(token)
        val google = GoogleSignIn(CLIENT_ID, credentials)

        assertEquals(GoogleResult.Token(idToken), google.idToken(activity, "n-1", auto = true, autoSelect = true))

        val option = credentials.requests.single().googleId()
        assertTrue(option.filterByAuthorizedAccounts)
        assertTrue(option.autoSelectEnabled)
        assertEquals("n-1", option.nonce)
        assertEquals(CLIENT_ID, option.serverClientId)
    }

    @Test fun afterASignOutTheAutomaticSheetDoesNotPickByItself() = runTest {
        val credentials = FakeCredentials(token)
        val google = GoogleSignIn(CLIENT_ID, credentials)

        google.idToken(activity, "n-1", auto = true, autoSelect = false)

        val option = credentials.requests.single().googleId()
        assertTrue(option.filterByAuthorizedAccounts)
        assertFalse(option.autoSelectEnabled)
    }

    @Test fun withNoAccountUsedBeforeItOffersEveryAccountWithTheSameNonce() = runTest {
        val credentials = FakeCredentials(NoCredentialException(), token)
        val google = GoogleSignIn(CLIENT_ID, credentials)

        assertEquals(GoogleResult.Token(idToken), google.idToken(activity, "n-1", auto = true, autoSelect = true))

        val (first, second) = credentials.requests.map { it.googleId() }
        assertTrue(first.filterByAuthorizedAccounts && first.autoSelectEnabled)
        assertFalse(second.filterByAuthorizedAccounts)
        assertFalse(second.autoSelectEnabled)
        assertEquals("n-1", second.nonce)
    }

    @Test fun aPhoneWithNoGoogleAccountGivesCancelledSilently() = runTest {
        val credentials = FakeCredentials(NoCredentialException(), NoCredentialException())
        val google = GoogleSignIn(CLIENT_ID, credentials)

        assertEquals(GoogleResult.Cancelled, google.idToken(activity, "n-1", auto = true, autoSelect = true))
        assertEquals(2, credentials.requests.size)
    }

    @Test fun closingTheFirstSheetDoesNotAskAgain() = runTest {
        val credentials = FakeCredentials(GetCredentialCancellationException())
        val google = GoogleSignIn(CLIENT_ID, credentials)

        assertEquals(GoogleResult.Cancelled, google.idToken(activity, "n-1", auto = true, autoSelect = true))
        assertEquals(1, credentials.requests.size)
    }

    @Test fun theButtonAsksOnceWithSignInWithGoogle() = runTest {
        val credentials = FakeCredentials(token, NoCredentialException())
        val google = GoogleSignIn(CLIENT_ID, credentials)

        assertEquals(GoogleResult.Token(idToken), google.idToken(activity, "n-1", auto = false, autoSelect = false))
        val option = credentials.requests.single().credentialOptions.single() as GetSignInWithGoogleOption
        assertEquals("n-1", option.nonce)
        assertEquals(CLIENT_ID, option.serverClientId)

        // No account for the button is a failure the screen shows, and it is not asked again.
        assertEquals(GoogleResult.Failed, google.idToken(activity, "n-2", auto = false, autoSelect = false))
        assertEquals(2, credentials.requests.size)
    }

    @Test fun withoutAClientIdNothingIsAsked() = runTest {
        val credentials = FakeCredentials()
        val google = GoogleSignIn("", credentials)

        assertEquals(GoogleResult.Failed, google.idToken(activity, "n-1", auto = true, autoSelect = true))
        assertTrue(credentials.requests.isEmpty())
    }

    @Test fun clearingTheStateNeverFails() = runTest {
        val credentials = FakeCredentials()
        val google = GoogleSignIn(CLIENT_ID, credentials)

        google.clearState()
        credentials.onClear = { throw ClearCredentialUnknownException() }
        google.clearState()
        // A provider that never answers does not hold up the sign-out.
        credentials.onClear = { awaitCancellation() }
        google.clearState()

        assertEquals(3, credentials.clears)
    }

    private companion object {
        const val CLIENT_ID = "1234-web.apps.googleusercontent.com"
    }
}
