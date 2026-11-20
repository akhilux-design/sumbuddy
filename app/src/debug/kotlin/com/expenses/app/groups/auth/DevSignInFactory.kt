package com.expenses.app.groups.auth

import com.expenses.app.BuildConfig
import com.expenses.app.groups.api.ApiJson
import com.expenses.app.groups.api.ApiResult
import com.expenses.app.groups.api.AuthResponse
import com.expenses.app.groups.api.DevSignInRequest
import com.expenses.app.groups.api.GroupsApi

/**
 * Debug builds: sign-in against the local development server (`npm run dev` in server/) without Google
 * (docs/server.md 4.4). The server answers this route only with `ENVIRONMENT = "development"` and a local host, and
 * release builds contain no code that calls it (their factory returns [NoDevSignIn]).
 */
fun devSignIn(api: GroupsApi): DevSignIn = LocalDevSignIn(api)

private class LocalDevSignIn(private val api: GroupsApi) : DevSignIn {
    override val available: Boolean get() = BuildConfig.DEV_SIGN_IN

    override suspend fun signIn(devUser: String, name: String, installationId: String): ApiResult<AuthResponse> =
        api.postPublic(
            "/v1/auth/dev",
            ApiJson.encodeToJsonElement(DevSignInRequest.serializer(), DevSignInRequest(devUser, name, installationId)),
            AuthResponse.serializer(),
        )
}
