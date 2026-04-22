package com.example.relapse_watch.data.repository

import com.example.relapse_watch.data.local.dao.ActivityRecordDao
import com.example.relapse_watch.data.local.entity.ActivityRecordEntity
import com.example.relapse_watch.domain.model.ActivityRecord
import com.example.relapse_watch.domain.repository.ActivityRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ActivityRepositoryImpl @Inject constructor(
    private val dao: ActivityRecordDao
) : ActivityRepository {

    private val json = Json

    override suspend fun insertRecord(record: ActivityRecord) {
        dao.insert(record.toEntity())
    }

    override suspend fun insertBatch(records: List<ActivityRecord>) {
        dao.insertAll(records.map { it.toEntity() })
    }

    override fun getRecordsByDateRange(start: Long, end: Long): Flow<List<ActivityRecord>> {
        return dao.getRecordsByDateRange(start, end).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun getLatestRecord(): Flow<ActivityRecord?> {
        return dao.getLatestRecord().map { it?.toDomain() }
    }

    override suspend fun getPendingUpload(): List<ActivityRecord> {
        return dao.getPendingUpload().map { it.toDomain() }
    }

    override suspend fun markUploaded(ids: List<String>) {
        dao.markUploaded(ids)
    }

    override suspend fun deleteOlderThan(days: Int) {
        val cutoff = System.currentTimeMillis() - (days * 24 * 60 * 60 * 1000L)
        dao.deleteOlderThan(cutoff)
    }

    private fun ActivityRecord.toEntity(): ActivityRecordEntity {
        return ActivityRecordEntity(
            id = id,
            patientId = patientId,
            timestamp = timestamp,
            latitude = latitude,
            longitude = longitude,
            eventType = eventType,
            metadataJson = metadata?.let { encodeMetadata(it) },
            uploaded = uploaded
        )
    }

    private fun ActivityRecordEntity.toDomain(): ActivityRecord {
        return ActivityRecord(
            id = id,
            patientId = patientId,
            timestamp = timestamp,
            latitude = latitude,
            longitude = longitude,
            eventType = eventType,
            metadata = metadataJson?.let {
                try {
                    decodeMetadata(it)
                } catch (_: Exception) {
                    null
                }
            },
            uploaded = uploaded
        )
    }

    private fun encodeMetadata(metadata: Map<String, Any>): String {
        val asJsonElements = metadata.mapValues { (_, value) -> value.toJsonElement() }
        return json.encodeToString(
            MapSerializer(String.serializer(), JsonElement.serializer()),
            asJsonElements
        )
    }

    private fun decodeMetadata(raw: String): Map<String, Any> {
        val decoded = json.decodeFromString(
            MapSerializer(String.serializer(), JsonElement.serializer()),
            raw
        )
        return decoded.mapValues { (_, element) -> element.toNativeValue() }
    }

    private fun JsonElement.toNativeValue(): Any {
        return when (this) {
            is JsonObject -> this.mapValues { (_, value) -> value.toNativeValue() }
            is JsonArray -> this.map { it.toNativeValue() }
            is JsonPrimitive -> {
                when {
                    isString -> content
                    content.equals("true", ignoreCase = true) -> true
                    content.equals("false", ignoreCase = true) -> false
                    content.toLongOrNull() != null -> content.toLong()
                    content.toDoubleOrNull() != null -> content.toDouble()
                    else -> content
                }
            }
        }
    }

    private fun Any.toJsonElement(): JsonElement {
        return when (this) {
            is JsonElement -> this
            is String -> JsonPrimitive(this)
            is Number -> JsonPrimitive(this)
            is Boolean -> JsonPrimitive(this)
            is Map<*, *> -> {
                buildJsonObject {
                    this@toJsonElement.forEach { (key, value) ->
                        if (key is String && value != null) {
                            put(key, value.toJsonElement())
                        }
                    }
                }
            }
            is List<*> -> {
                buildJsonArray {
                    this@toJsonElement.forEach { item ->
                        if (item != null) add(item.toJsonElement())
                    }
                }
            }
            else -> JsonPrimitive(this.toString())
        }
    }
}
