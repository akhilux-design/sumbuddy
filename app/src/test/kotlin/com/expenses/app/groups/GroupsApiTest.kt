package com.expenses.app.groups

import com.expenses.app.groups.api.ApiError
import com.expenses.app.groups.api.ApiJson
import com.expenses.app.groups.api.ApiResult
import com.expenses.app.groups.api.AuthResponse
import com.expenses.app.groups.api.DevSignInRequest
import com.expenses.app.groups.api.DeviceRequest
import com.expenses.app.groups.api.ExpenseInputDto
import com.expenses.app.groups.api.GoogleSignInRequest
import com.expenses.app.groups.api.GroupBodies
import com.expenses.app.groups.api.GroupsApi
import com.expenses.app.groups.api.LinkRequest
import com.expenses.app.groups.api.MemberBody
import com.expenses.app.groups.api.RefreshRequest
import com.expenses.app.groups.api.RemindRequest
import com.expenses.app.groups.api.RenameGroupRequest
import com.expenses.app.groups.api.SetTo
import com.expenses.app.groups.api.SettlementInputDto
import com.expenses.app.groups.api.ShareDto
import com.expenses.app.groups.api.UserDto
import com.expenses.app.groups.api.errorOrNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupsApiTest {
    private val fake = FakeServer()
    private val server = MockWebServer().apply {
        dispatcher = fake
        start()
    }
    private val session = FakeSession()
    private val api = GroupsApi(server.url("/").toString(), OkHttpClient(), { session })

    @After fun close() {
        runCatching { server.shutdown() }
    }

    @Test fun ourErrorsAreReadWithTheirField() = runTest {
        fake.on("PUT", "/v1/groups/$G1/expenses/$M_ME", json(400, """{"error":{"code":"bad_request","message":"x","field":"shares[2].input"}}"""))
        val error = api.send("PUT", "/v1/groups/$G1/expenses/$M_ME", "{}", "k").errorOrNull()!!
        assertEquals(ApiError(400, "bad_request", "shares[2].input", fromServer = true), error)
        assertFalse(error.isTransient)
    }

    @Test fun anAnswerThatIsNotOursIsTransient() = runTest {
        // Cloudflare's own page when the day's free limit is reached: a 429 without our JSON.
        fake.on("GET", "/v1/me/groups", MockResponse().setResponseCode(429).setBody("<html>limit</html>"))
        val error = api.meGroups().errorOrNull()!!
        assertTrue(error.isTransient)
        assertFalse(error.isRateLimited)
        assertEquals(ApiError.INTERNAL, error.code)
    }

    @Test fun ourRateLimitCarriesRetryAfter() = runTest {
        fake.on("GET", "/v1/me/groups", error(429, "rate_limited").setHeader("Retry-After", "60"))
        val error = api.meGroups().errorOrNull()!!
        assertTrue(error.isRateLimited)
        assertEquals(60L, error.retryAfterSeconds)
    }

    @Test fun noAnswerIsOffline() = runTest {
        server.shutdown()
        assertTrue(api.meGroups().errorOrNull()!!.isOffline)
    }

    @Test fun signedOutSendsNothing() = runTest {
        session.token = null
        assertEquals(ApiError.signedOut, api.meGroups().errorOrNull())
        assertTrue(fake.requests.isEmpty())
    }

    @Test fun mutationsCarryTheirKeyAndSignInRoutesNoBearer() = runTest {
        fake.on("POST", "/v1/auth/nonce", json(200, """{"nonce":"n"}"""))
        fake.on("DELETE", "/v1/groups/$G1/expenses/$M_ME", json(200, """{"version":3}""").setHeader("Idempotent-Replay", "true"))
        api.nonce()
        val deleted = api.deleteExpense(G1, M_ME, "key-1")
        val (nonce, delete) = fake.requests.toList()
        assertNull(nonce.getHeader("Authorization"))
        assertNull(nonce.getHeader("Idempotency-Key"))
        assertEquals("Bearer access-1", delete.getHeader("Authorization"))
        assertEquals("key-1", delete.getHeader("Idempotency-Key"))
        assertTrue((deleted as ApiResult.Ok).replay)
        assertEquals(3L, deleted.value.version)
    }

    @Test fun profileChangesSendOnlyWhatChanged() = runTest {
        fake.on("PATCH", "/v1/me", json(200, UserDto.serializer(), UserDto(ME_USER, "Arjun")))
        api.updateMe(name = SetTo("Arjun"), idempotencyKey = "a")
        api.updateMe(phone = SetTo(null), idempotencyKey = "b")
        val bodies = fake.requests.map { ApiJson.parseToJsonElement(it.body.readUtf8()).jsonObject }
        assertEquals(setOf("name"), bodies[0].keys)
        assertEquals(JsonObject(mapOf("phone" to JsonNull)), bodies[1])
    }
}

/**
 * Nothing that stays on the phone may appear in a request (docs/server.md 10): every request body's field names are
 * checked against what the contract allows, and against names of personal data.
 */
class DtoPrivacyTest {
    private val forbidden = listOf("sms", "message", "sender", "note", "account", "card", "bank", "paidWith", "reference", "vpa", "email", "time", "body")

    private fun fieldNames(element: JsonElement): Set<String> = when (element) {
        is JsonObject -> element.keys + element.values.flatMap(::fieldNames)
        is JsonArray -> element.flatMap(::fieldNames).toSet()
        else -> emptySet()
    }

    private fun <T> fields(serializer: kotlinx.serialization.KSerializer<T>, value: T) = fieldNames(ApiJson.encodeToJsonElement(serializer, value))

    @Test fun requestBodiesCarryOnlyTheContractsFields() {
        val bodies = mapOf(
            "expense" to fields(
                ExpenseInputDto.serializer(),
                ExpenseInputDto("Swiggy", "FOOD", 45_000, "2026-11-16", M_ME, "EQUAL", false, listOf(ShareDto(M_ME, 15_000, null)), null),
            ),
            "settlement" to fields(SettlementInputDto.serializer(), SettlementInputDto(M_ME, M_RAHUL, 100, "UPI", true, "2026-11-16")),
            "google" to fields(GoogleSignInRequest.serializer(), GoogleSignInRequest("t", "n", "i")),
            "dev" to fields(DevSignInRequest.serializer(), DevSignInRequest("alice", "Alice", "i")),
            "refresh" to fields(RefreshRequest.serializer(), RefreshRequest("r")),
            "device" to fields(DeviceRequest.serializer(), DeviceRequest("t")),
            "link" to fields(LinkRequest.serializer(), LinkRequest("t")),
            "remind" to fields(RemindRequest.serializer(), RemindRequest(listOf(M_ME))),
            "rename" to fields(RenameGroupRequest.serializer(), RenameGroupRequest("Flat")),
            "group" to fieldNames(GroupBodies.create(G1, "group", "Flat", M_ME, listOf(MemberBody.User(M_RAHUL, RAHUL_USER), MemberBody.Friend(M_PRIYA, "Priya", "+919000000001")))),
            "friend" to fieldNames(GroupBodies.editFriend("Priya", null)),
        )
        val allowed = setOf(
            "description", "category", "amountPaise", "occurredOn", "paidByMemberId", "splitMethod", "auto", "shares", "memberId",
            "sharePaise", "input", "baseVersion", "fromMemberId", "toMemberId", "method", "matchedFromSms", "idToken", "nonce",
            "installationId", "devUser", "name", "refreshToken", "fcmToken", "token", "memberIds", "id", "kind", "myMemberId",
            "members", "userId", "phone",
        )
        for ((route, names) in bodies) {
            assertTrue("$route sends ${names - allowed}", allowed.containsAll(names))
            // matchedFromSms is a yes/no the contract asks for (03/02); the SMS itself never goes.
            val checked = names - setOf("matchedFromSms")
            for (name in checked) assertTrue("$route sends $name", forbidden.none { name.contains(it, ignoreCase = true) })
        }
    }

    @Test fun signInAnswersAreRead() {
        val text = """{"user":{"id":"u","name":"Arjun Mehta","phone":null,"upiId":null},"isNew":true,"accessToken":"a","accessExpiresAt":1,"refreshToken":"r","refreshExpiresAt":2,"extra":"ignored"}"""
        val answer = ApiJson.decodeFromString(AuthResponse.serializer(), text)
        assertEquals("Arjun Mehta", answer.user.name)
        assertTrue(answer.isNew)
    }
}
