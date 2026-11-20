package com.expenses.app.groups.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A person to put in a group, as the API takes them: an app user the person already knows (`{ id, userId }`) or a
 * friend without the app (`{ id, name, phone? }`).
 */
sealed interface MemberBody {
    /** The member id this phone made. */
    val id: String

    /** A known app user. */
    data class User(override val id: String, val userId: String) : MemberBody

    /** A friend without the app; [phone] is E.164 or null. */
    data class Friend(override val id: String, val name: String, val phone: String?) : MemberBody
}

/** Request bodies whose fields depend on the kind of member, so they are written out field by field. */
object GroupBodies {
    /** `POST /v1/groups`: a named group ([name] set) or a direct split ([name] null, exactly one member). */
    fun create(id: String, kind: String, name: String?, myMemberId: String, members: List<MemberBody>): JsonObject =
        buildJsonObject {
            put("id", id)
            put("kind", kind)
            if (name != null) put("name", name)
            put("myMemberId", myMemberId)
            put("members", JsonArray(members.map(::member)))
        }

    /**
     * `POST /v1/groups` for a family (docs/family.md 3.1): the person as [myMemberId] and exactly one placeholder
     * member [placeholderId] for the partner, who joins through its link. The placeholder's name is the fixed
     * [PLACEHOLDER_NAME]; it is never shown.
     */
    fun createFamily(id: String, name: String, myMemberId: String, placeholderId: String): JsonObject =
        create(id, "family", name, myMemberId, listOf(MemberBody.Friend(placeholderId, PLACEHOLDER_NAME, null)))

    /** `POST /v1/groups/{gid}/members` on a family (docs/family.md 3.6): a new placeholder for the partner. */
    fun placeholder(id: String): JsonObject = member(MemberBody.Friend(id, PLACEHOLDER_NAME, null))

    /**
     * The name the phone gives a family's placeholder member: fixed English text, not a string resource, because it is
     * never shown (the partner's own name replaces it when they join).
     */
    const val PLACEHOLDER_NAME: String = "Partner"

    /** One member, for `POST /v1/groups` and `POST /v1/groups/{gid}/members`. */
    fun member(member: MemberBody): JsonObject = buildJsonObject {
        put("id", member.id)
        when (member) {
            is MemberBody.User -> put("userId", member.userId)
            is MemberBody.Friend -> {
                put("name", member.name)
                if (member.phone != null) put("phone", member.phone)
            }
        }
    }

    /** `PATCH /v1/groups/{gid}/members/{mid}` for a friend without the app. */
    fun editFriend(name: String, phone: String?): JsonObject = buildJsonObject {
        put("name", name)
        put("phone", phone?.let(::JsonPrimitive) ?: JsonNull)
    }
}
