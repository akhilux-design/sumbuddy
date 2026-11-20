package com.expenses.app.groups.auth

import com.expenses.app.groups.api.ApiError
import com.expenses.app.groups.api.ApiResult
import com.expenses.app.groups.api.AuthResponse
import com.expenses.app.groups.api.GroupsApi

/** Release builds: no development sign-in, and no code that calls `/v1/auth/dev` (docs/server.md 9.2). */
@Suppress("UNUSED_PARAMETER")
fun devSignIn(api: GroupsApi): DevSignIn = NoDevSignIn

/** The development sign-in of release builds: never available. */
object NoDevSignIn : DevSignIn {
    override val available: Boolean = false

    override suspend fun signIn(devUser: String, name: String, installationId: String): ApiResult<AuthResponse> =
        ApiResult.Error(ApiError(status = 404, code = "not_found"))
}
