package com.expenses.app.groups.splitwise

import com.expenses.app.groups.api.ApiJson
import com.expenses.app.groups.api.ExpenseInputDto
import com.expenses.app.groups.data.EntityKind
import com.expenses.app.groups.data.SettlementEntity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * One item of an import part, as its outbox body names it: an expense or a settlement with its id.
 *
 * @param kind [EntityKind.EXPENSE] or [EntityKind.SETTLEMENT].
 * @param id the row's id.
 */
data class ImportItemRef(val kind: EntityKind, val id: String)

/**
 * The bodies of `POST /v1/groups/{gid}/import` (docs/server.md 4.17), written out field by field like
 * [com.expenses.app.groups.api.GroupBodies]: an item's fields depend on its kind, and kotlinx polymorphism would add
 * a type key the server does not know. Compact JSON ([ApiJson]), as the outbox stores and sends it.
 */
object ImportBodies {
    private const val KIND = "kind"
    private const val ID = "id"
    private const val ITEMS = "items"
    private const val EXPENSE = "expense"
    private const val SETTLEMENT = "settlement"

    /** An expense item: `kind`, `id` and the fields of an `ExpenseInput` (4.7; `auto` false, `baseVersion` null). */
    fun expenseItem(id: String, input: ExpenseInputDto): JsonObject = buildJsonObject {
        put(KIND, EXPENSE)
        put(ID, id)
        ApiJson.encodeToJsonElement(ExpenseInputDto.serializer(), input).jsonObject.forEach { (key, value) -> put(key, value) }
    }

    /** A settlement item: `kind`, `id`, `fromMemberId`, `toMemberId`, `amountPaise` and `occurredOn` only. */
    fun settlementItem(settlement: SettlementEntity): JsonObject = buildJsonObject {
        put(KIND, SETTLEMENT)
        put(ID, settlement.id)
        put("fromMemberId", settlement.fromMemberId)
        put("toMemberId", settlement.toMemberId)
        put("amountPaise", settlement.amountMinor)
        put("occurredOn", settlement.occurredOn.toString())
    }

    /** The body of one part: `{"items":[…]}`. */
    fun part(items: List<JsonObject>): JsonObject = buildJsonObject { put(ITEMS, JsonArray(items)) }

    /** The UTF-8 size of [item] in a body, for [com.expenses.splitwise.ImportParts.chunk]. */
    fun itemBytes(item: JsonObject): Int = ApiJson.encodeToString(JsonElement.serializer(), item).encodeToByteArray().size

    /**
     * The items a part's stored body names, in body order (item `i` is written at `firstVersion + i`); empty when the
     * body cannot be read. Only kinds and ids are read: nothing else of the body is looked at.
     */
    fun itemsOf(bodyJson: String?): List<ImportItemRef> {
        if (bodyJson == null) return emptyList()
        return runCatching {
            ApiJson.parseToJsonElement(bodyJson).jsonObject.getValue(ITEMS).jsonArray.mapNotNull { element ->
                val item = element.jsonObject
                val id = item[ID]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                when (item[KIND]?.jsonPrimitive?.contentOrNull) {
                    EXPENSE -> ImportItemRef(EntityKind.EXPENSE, id)
                    SETTLEMENT -> ImportItemRef(EntityKind.SETTLEMENT, id)
                    else -> null
                }
            }
        }.getOrDefault(emptyList())
    }
}
