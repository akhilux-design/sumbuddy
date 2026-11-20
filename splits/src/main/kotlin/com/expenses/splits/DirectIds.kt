package com.expenses.splits

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/**
 * The ids of a direct split between two app users (docs/server.md 4.6). Both phones make the same ids offline, so a
 * pair of people never gets two direct groups. They are RFC 4122 version 5 UUIDs (SHA-1) in the namespace [NAMESPACE].
 * `java.util.UUID.nameUUIDFromBytes` makes version 3 (MD5) ids, which the server refuses, so v5 is written out here.
 * Checked against `shared/vectors/direct_ids.json`.
 */
object DirectIds {
    /** The namespace of every direct id. */
    val NAMESPACE: UUID = UUID.fromString("6f1c2a4e-3b8d-4c1e-9a57-2d0e8b6f4a13")

    /** The direct group of users [userA] and [userB], in either order. */
    fun groupId(userA: String, userB: String): String {
        val (low, high) = if (userA <= userB) userA to userB else userB to userA
        return uuidV5(NAMESPACE, "direct:$low:$high").toString()
    }

    /** The member id of user [userId] in the direct group [groupId]. */
    fun memberId(groupId: String, userId: String): String = uuidV5(NAMESPACE, "member:$groupId:$userId").toString()

    /** A version 5 UUID of [name] (UTF-8) in [namespace]. */
    fun uuidV5(namespace: UUID, name: String): UUID {
        val sha1 = MessageDigest.getInstance("SHA-1")
        sha1.update(ByteBuffer.allocate(16).putLong(namespace.mostSignificantBits).putLong(namespace.leastSignificantBits).array())
        sha1.update(name.toByteArray(Charsets.UTF_8))
        val hash = sha1.digest()
        hash[6] = ((hash[6].toInt() and 0x0f) or 0x50).toByte()
        hash[8] = ((hash[8].toInt() and 0x3f) or 0x80).toByte()
        val buffer = ByteBuffer.wrap(hash, 0, 16)
        return UUID(buffer.long, buffer.long)
    }
}
