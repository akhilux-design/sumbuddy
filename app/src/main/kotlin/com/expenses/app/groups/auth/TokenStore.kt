package com.expenses.app.groups.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Encrypts the values of the session store. [KeystoreCipher] is the real one; tests pass a fake. */
interface ValueCipher {
    /** [plain] encrypted, as text that can be stored. */
    fun encrypt(plain: String): String

    /** [stored] decrypted, or null when it cannot be read (the key is gone). */
    fun decrypt(stored: String): String?
}

/**
 * AES-256-GCM with a key that never leaves the Android Keystore (alias [ALIAS]). Each value gets its own random IV,
 * stored in front of the ciphertext. Not EncryptedSharedPreferences, which is deprecated (docs/server.md 9.2).
 */
class KeystoreCipher : ValueCipher {
    override fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    override fun decrypt(stored: String): String? = runCatching {
        val sealed = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
        String(cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES), Charsets.UTF_8)
    }.getOrNull()

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    /** Key settings. */
    companion object {
        /** The Keystore alias of the session key. */
        const val ALIAS: String = "groups_auth_v1"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_BITS = 256
        private const val TAG_BITS = 128
        private const val IV_BYTES = 12
    }
}

/**
 * The stored session and profile.
 *
 * @param userId the signed-in user, or null when signed out.
 * @param name the user's name on the server.
 * @param phone the user's phone (E.164), or null.
 * @param upiId the user's UPI ID, or null.
 * @param refreshToken the refresh token, or null when signed out (also after the server refused it).
 * @param refreshExpiresAt when the refresh token stops working, epoch millis.
 * @param lastUserId the user who signed in last, kept after sign-out so a different account is noticed (9.2).
 * @param accessToken the last access token, kept so a new process (app reopened, a push, a periodic sync) does not
 * refresh before its first request: each refresh writes the session row on the server (docs/server.md 8).
 * @param accessExpiresAt when [accessToken] stops working, epoch millis.
 * @param signedOutByUser true after the person signed out (Account), until the next sign-in: Google's automatic
 * sheet then waits for a tap. A refused refresh token does not set it.
 * @param profileCheckedAt when the profile was last asked of the server or given by it (a sign-in, a save), epoch
 * millis, or null when never: the app's start reads it again only 10 minutes after that.
 */
data class StoredSession(
    val userId: String?,
    val name: String?,
    val phone: String?,
    val upiId: String?,
    val refreshToken: String?,
    val refreshExpiresAt: Long?,
    val lastUserId: String?,
    val accessToken: String? = null,
    val accessExpiresAt: Long? = null,
    val signedOutByUser: Boolean = false,
    val profileCheckedAt: Long? = null,
) {
    /** True while a refresh token is kept. */
    val signedIn: Boolean get() = userId != null && refreshToken != null
}

/**
 * The push token the server last accepted for this install.
 *
 * @param userId the user it was sent for.
 * @param token the token.
 * @param sentAt when it was sent, epoch millis.
 */
data class SentPushToken(val userId: String, val token: String, val sentAt: Long)

/**
 * The session on this phone: DataStore Preferences file `auth`, every value encrypted with [cipher] except the
 * install id. The access token is stored too (encrypted, like the refresh token, which can do more), so it is reused
 * across processes until it is about to expire; the server checks the session on every request, so sign-out still
 * ends it at once. Backups are off for every domain, so nothing here leaves the phone.
 */
class TokenStore(private val store: DataStore<Preferences>, private val cipher: ValueCipher) {
    /** The stored session; emits again on every change. */
    val session: Flow<StoredSession> = store.data.map { prefs ->
        StoredSession(
            userId = prefs.read(USER_ID),
            name = prefs.read(NAME),
            phone = prefs.read(PHONE),
            upiId = prefs.read(UPI_ID),
            refreshToken = prefs.read(REFRESH_TOKEN),
            refreshExpiresAt = prefs.read(REFRESH_EXPIRES_AT)?.toLongOrNull(),
            lastUserId = prefs.read(LAST_USER_ID),
            accessToken = prefs.read(ACCESS_TOKEN),
            accessExpiresAt = prefs.read(ACCESS_EXPIRES_AT)?.toLongOrNull(),
            signedOutByUser = prefs.read(SIGNED_OUT_BY_USER) != null,
            profileCheckedAt = prefs.read(PROFILE_CHECKED_AT)?.toLongOrNull(),
        )
    }.distinctUntilChanged()

    /** The stored session now. */
    suspend fun current(): StoredSession = session.first()

    /** This install's id: a random UUID made the first time it is asked for. */
    suspend fun installationId(): String {
        store.data.first()[INSTALLATION_ID]?.let { return it }
        var id = ""
        store.edit { prefs -> id = prefs[INSTALLATION_ID] ?: UUID.randomUUID().toString().also { prefs[INSTALLATION_ID] = it } }
        return id
    }

    /**
     * Stores a new session after a sign-in.
     *
     * @param profileCheckedAt when the server gave this profile, or null to leave the time as it is.
     */
    suspend fun saveSignIn(
        userId: String,
        name: String,
        phone: String?,
        upiId: String?,
        tokens: Tokens,
        profileCheckedAt: Long? = null,
    ) {
        store.edit { prefs ->
            prefs.write(USER_ID, userId)
            prefs.write(LAST_USER_ID, userId)
            prefs.write(NAME, name)
            prefs.write(PHONE, phone)
            prefs.write(UPI_ID, upiId)
            prefs.writeTokens(tokens)
            prefs.remove(SIGNED_OUT_BY_USER)
            if (profileCheckedAt != null) prefs.write(PROFILE_CHECKED_AT, profileCheckedAt.toString())
        }
    }

    /** Stores the tokens a refresh gave. */
    suspend fun saveTokens(tokens: Tokens) {
        store.edit { prefs -> prefs.writeTokens(tokens) }
    }

    /**
     * Stores the profile as the server has it, only while [userId] is still the one signed in: an answer that comes
     * after a sign-out, or after another account signed in, is dropped.
     *
     * @param checkedAt when the server gave it, epoch millis.
     * @return true when it was stored.
     */
    suspend fun saveProfile(userId: String, name: String, phone: String?, upiId: String?, checkedAt: Long): Boolean {
        var stored = false
        store.edit { prefs ->
            if (prefs[REFRESH_TOKEN] == null || prefs.read(USER_ID) != userId) return@edit
            prefs.write(NAME, name)
            prefs.write(PHONE, phone)
            prefs.write(UPI_ID, upiId)
            prefs.write(PROFILE_CHECKED_AT, checkedAt.toString())
            stored = true
        }
        return stored
    }

    /** Notes that the profile was asked of the server at [at], epoch millis (the app's start asks once in 10 minutes). */
    suspend fun markProfileChecked(at: Long) {
        store.edit { prefs -> prefs.write(PROFILE_CHECKED_AT, at.toString()) }
    }

    /**
     * Forgets the tokens only (the server refused the refresh token). The user id and profile stay, so signing in
     * again as the same person resumes where they were.
     */
    suspend fun forgetTokens() {
        store.edit { prefs -> TOKEN_KEYS.forEach(prefs::remove) }
    }

    /**
     * Forgets the session and profile (the person signed out or deleted the account), and the push token the server
     * had: it drops this install's device row on sign-out. The install id and the last user id stay, and the sign-out
     * is remembered ([StoredSession.signedOutByUser]).
     */
    suspend fun clearSession() {
        store.edit { prefs ->
            (listOf(USER_ID, NAME, PHONE, UPI_ID, PROFILE_CHECKED_AT) + TOKEN_KEYS + PUSH_KEYS).forEach(prefs::remove)
            prefs.write(SIGNED_OUT_BY_USER, "1")
        }
    }

    /** The push token the server last accepted for this install, or null when none is known (or its time is not). */
    suspend fun sentPushToken(): SentPushToken? {
        val prefs = store.data.first()
        val userId = prefs.read(PUSH_USER_ID) ?: return null
        val token = prefs.read(PUSH_TOKEN) ?: return null
        val sentAt = prefs.read(PUSH_SENT_AT)?.toLongOrNull() ?: return null
        return SentPushToken(userId, token, sentAt)
    }

    /** Stores [sent] after the server accepted it. */
    suspend fun saveSentPushToken(sent: SentPushToken) {
        store.edit { prefs ->
            prefs.write(PUSH_USER_ID, sent.userId)
            prefs.write(PUSH_TOKEN, sent.token)
            prefs.write(PUSH_SENT_AT, sent.sentAt.toString())
        }
    }

    /** Forgets the push token the server last accepted, so the next one is sent. */
    suspend fun forgetSentPushToken() {
        store.edit { prefs -> PUSH_KEYS.forEach(prefs::remove) }
    }

    private fun androidx.datastore.preferences.core.MutablePreferences.writeTokens(tokens: Tokens) {
        write(REFRESH_TOKEN, tokens.refreshToken)
        write(REFRESH_EXPIRES_AT, tokens.refreshExpiresAt.toString())
        write(ACCESS_TOKEN, tokens.accessToken)
        write(ACCESS_EXPIRES_AT, tokens.accessExpiresAt.toString())
    }

    private fun Preferences.read(key: Preferences.Key<String>): String? = this[key]?.let(cipher::decrypt)

    private fun androidx.datastore.preferences.core.MutablePreferences.write(key: Preferences.Key<String>, value: String?) {
        if (value == null) remove(key) else this[key] = cipher.encrypt(value)
    }

    private companion object {
        val INSTALLATION_ID = stringPreferencesKey("installation_id")
        val USER_ID = stringPreferencesKey("user_id")
        val LAST_USER_ID = stringPreferencesKey("last_user_id")
        val NAME = stringPreferencesKey("name")
        val PHONE = stringPreferencesKey("phone")
        val UPI_ID = stringPreferencesKey("upi_id")
        val REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        val REFRESH_EXPIRES_AT = stringPreferencesKey("refresh_expires_at")
        val ACCESS_TOKEN = stringPreferencesKey("access_token")
        val ACCESS_EXPIRES_AT = stringPreferencesKey("access_expires_at")
        val SIGNED_OUT_BY_USER = stringPreferencesKey("signed_out_by_user")
        val PROFILE_CHECKED_AT = stringPreferencesKey("profile_checked_at")
        val PUSH_USER_ID = stringPreferencesKey("push_user_id")
        val PUSH_TOKEN = stringPreferencesKey("push_token")
        val PUSH_SENT_AT = stringPreferencesKey("push_sent_at")
        val TOKEN_KEYS = listOf(REFRESH_TOKEN, REFRESH_EXPIRES_AT, ACCESS_TOKEN, ACCESS_EXPIRES_AT)
        val PUSH_KEYS = listOf(PUSH_USER_ID, PUSH_TOKEN, PUSH_SENT_AT)
    }
}

/** The tokens a sign-in or a refresh gives (docs/server.md 4.4). Times are epoch millis. */
data class Tokens(
    val accessToken: String,
    val accessExpiresAt: Long,
    val refreshToken: String,
    val refreshExpiresAt: Long,
)
