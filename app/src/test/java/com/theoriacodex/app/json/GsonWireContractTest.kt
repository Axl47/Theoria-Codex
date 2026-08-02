package com.theoriacodex.app.json

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GsonWireContractTest {
    private val gson = GsonBuilder().serializeNulls().create()
    private val contracts = loadContracts()

    @Test
    fun `every durable Gson field has an explicit stable name and no arg constructor`() {
        contracts.forEach { contract ->
            val type = Class.forName(contract.className)
            assertNotNull(
                "${contract.className} must retain a no-arg constructor for reflection",
                type.getDeclaredConstructor(),
            )
            val serializedFields = type.declaredFields
                .filterNot { field ->
                    field.isSynthetic || Modifier.isStatic(field.modifiers) || Modifier.isTransient(field.modifiers)
                }
            assertEquals(
                "${contract.className} contract manifest is stale",
                contract.fields,
                serializedFields.mapTo(linkedSetOf()) { field -> field.name },
            )
            serializedFields.forEach { field ->
                val annotation = field.getAnnotation(SerializedName::class.java)
                assertNotNull("${contract.className}.${field.name} needs @SerializedName", annotation)
                assertEquals(field.name, annotation?.value)
            }
        }
    }

    @Test
    fun `empty contract instances emit the exact golden key sets`() {
        contracts.forEach { contract ->
            val type = Class.forName(contract.className)
            val constructor = type.getDeclaredConstructor().apply { isAccessible = true }
            val encoded = gson.toJsonTree(constructor.newInstance()).asJsonObject

            assertEquals(
                "${contract.className} emitted a renamed, missing, or unexpected JSON field",
                contract.fields,
                encoded.keySet(),
            )
        }
    }

    @Test
    fun `representative legacy fixtures still decode through every storage family`() {
        val fixtures = listOf(
            LegacyFixture(
                "com.theoriacodex.data.storage.PostStorageRecord",
                """{"source":"PIXIV","sourcePostId":"legacy-post","previewUrl":"https://example.test/p.jpg"}""",
                "sourcePostId",
                "legacy-post",
            ),
            LegacyFixture(
                "com.theoriacodex.data.repository.SettingsDataStoreFile",
                """{"schemaVersion":2,"settings":{"lastSelectedTabRoute":"recents"},"legacyImports":[]}""",
                "schemaVersion",
                2,
            ),
            LegacyFixture(
                "com.theoriacodex.data.repository.QueryStoreFile",
                """{"queries":{},"scrollOffsets":{"legacy-query":7}}""",
                "queries",
                emptyMap<String, Any>(),
            ),
            LegacyFixture(
                "com.theoriacodex.data.storage.LegacyRecentsStoreFile",
                """{"watchedPosts":[],"searches":[]}""",
                "watchedPosts",
                emptyList<Any>(),
            ),
            LegacyFixture(
                "com.theoriacodex.data.android.room.LegacyCodexStoreFile",
                """{"codices":[],"items":{},"posts":[]}""",
                "items",
                emptyMap<String, Any>(),
            ),
            LegacyFixture(
                "com.theoriacodex.app.update.UpdateStateSnapshot",
                """{"lastSeenReleaseId":701,"ignoredReleaseId":702}""",
                "ignoredReleaseId",
                702L,
            ),
            LegacyFixture(
                "com.theoriacodex.app.search.TagStoreSnapshot",
                """{"sources":{"GELBOORU":[{"text":"legacy","type":"trending","count":9}]}}""",
                "sources",
                mapOf(
                    "GELBOORU" to listOf(
                        mapOf(
                            "text" to "legacy",
                            "facet" to null,
                            "sourceNamespace" to null,
                            "type" to "trending",
                            "count" to 9,
                        )
                    )
                ),
            ),
            LegacyFixture(
                "com.theoriacodex.app.codex.CodexShareFile",
                """{"version":1,"title":"Legacy","posts":[{"source":"PIXIV","sourcePostId":"42"}]}""",
                "version",
                1,
            ),
            LegacyFixture(
                "com.theoriacodex.app.sourceauth.CredentialEnvelopeRecord",
                """{"formatVersion":1,"keyVersion":1,"iv":"aXY=","ciphertext":"Y3Q="}""",
                "formatVersion",
                1,
            ),
            LegacyFixture(
                "com.theoriacodex.sources.hitomi.HitomiSourceAdapter\$HitomiPageToken",
                """{"version":2,"queryHash":"legacy-query","primaryKey":"all","primaryOffset":12}""",
                "primaryOffset",
                12L,
            ),
        )

        fixtures.forEach { fixture ->
            val type = Class.forName(fixture.className)
            val decoded = gson.fromJson(fixture.json, type)
            val field = type.getDeclaredField(fixture.fieldName).apply { isAccessible = true }
            val actual = field.get(decoded)
            if (actual is Map<*, *> || actual is List<*>) {
                assertEquals(
                    JsonParser.parseString(gson.toJson(fixture.expected)),
                    JsonParser.parseString(gson.toJson(actual)),
                )
            } else {
                assertEquals(fixture.expected, actual)
            }
        }
    }
}

private data class JsonContract(
    val className: String,
    val fields: Set<String>,
)

private data class LegacyFixture(
    val className: String,
    val json: String,
    val fieldName: String,
    val expected: Any,
)

private fun loadContracts(): List<JsonContract> {
    val stream = checkNotNull(GsonWireContractTest::class.java.classLoader?.getResourceAsStream(CONTRACT_RESOURCE)) {
        "$CONTRACT_RESOURCE is missing"
    }
    return stream.bufferedReader().useLines { lines ->
        lines
            .map(String::trim)
            .filter { line -> line.isNotEmpty() && !line.startsWith("#") }
            .map { line ->
                val parts = line.split('|', limit = 2)
                require(parts.size == 2) { "Malformed JSON contract line: $line" }
                JsonContract(
                    className = parts[0],
                    fields = parts[1].split(',').mapTo(linkedSetOf(), String::trim),
                )
            }
            .toList()
    }
}

private const val CONTRACT_RESOURCE = "r8-json-contracts.txt"
