package com.expenses.app.ui.screens.signin

import android.app.Activity
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.expenses.app.groups.auth.AuthRepository
import com.expenses.app.groups.auth.SignInResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/** What Sign in asks its route to do. */
sealed interface SignInEffect {
    /** Signed in; [isNew] opens "Your name (after sign-in)". */
    data class SignedIn(val isNew: Boolean) : SignInEffect

    /** Not now, or Back: nothing changed. */
    data object Left : SignInEffect
}

/**
 * Sign in. The account sheet is offered once by itself when the screen first opens (its errors are silent); the
 * button asks again. A failure shows "Could not sign in" and changes nothing on the phone.
 *
 * Google's sheet needs the activity, which is passed in for the one call and not kept.
 *
 * @param forJoin true when a friend's link opened Sign in: the screen reads "Join a group" (02-everyday/49).
 * @param forFamily true from Set up Family or Family settings: the screen reads "Share a family month" (06-family/08).
 */
class SignInViewModel(
    private val auth: AuthRepository,
    private val savedState: SavedStateHandle,
    forJoin: Boolean = false,
    forFamily: Boolean = false,
) : ViewModel() {
    private val mutableState =
        MutableStateFlow(SignInUiState(devSignIn = auth.devSignInAvailable, forJoin = forJoin, forFamily = forFamily && !forJoin))
    private val effects = Channel<SignInEffect>(Channel.BUFFERED)

    /** What the screen shows. */
    val state: StateFlow<SignInUiState> = mutableState.asStateFlow()

    /** One-off results for the route. */
    val effect: Flow<SignInEffect> = effects.receiveAsFlow()

    /** The screen opened: offer Google's sheet by itself, once per visit. */
    fun offerAutomatically(activity: Activity) {
        if (savedState.get<Boolean>(AUTO_OFFERED) == true || !auth.googleConfigured) return
        savedState[AUTO_OFFERED] = true
        run { auth.signInWithGoogle(activity, auto = true) }
    }

    /** "Continue with Google". */
    fun continueWithGoogle(activity: Activity) = run { auth.signInWithGoogle(activity, auto = false) }

    /** Debug builds: "Dev sign-in" as [name]. */
    fun devSignIn(name: String) {
        val devUser = name.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }.take(MAX_DEV_USER).ifEmpty { "dev" }
        run { auth.signInDev(devUser, name) }
    }

    /** "Not now", or Back. */
    fun notNow() {
        effects.trySend(SignInEffect.Left)
    }

    private fun run(signIn: suspend () -> SignInResult) {
        if (mutableState.value.busy) return
        mutableState.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = signIn()
            mutableState.update { state ->
                when (result) {
                    is SignInResult.Success -> state.copy(busy = false, problem = null)
                    SignInResult.Cancelled -> state.copy(busy = false)
                    SignInResult.Failed -> state.copy(busy = false, problem = SignInProblem.FAILED)
                    SignInResult.NotConfigured -> state.copy(busy = false, problem = SignInProblem.NOT_CONFIGURED)
                }
            }
            if (result is SignInResult.Success) effects.send(SignInEffect.SignedIn(result.isNew))
        }
    }

    private companion object {
        const val AUTO_OFFERED = "auto_offered"
        const val MAX_DEV_USER = 20
    }
}
