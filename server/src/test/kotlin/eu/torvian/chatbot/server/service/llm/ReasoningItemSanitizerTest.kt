package eu.torvian.chatbot.server.service.llm

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies [sanitizeReasoningItems]/[sanitizeReasoningItem] reduce raw provider reasoning output items
 * to the Responses API `input` shape, dropping the output-only fields OpenAI rejects as unknown
 * parameters.
 */
class ReasoningItemSanitizerTest {

    @Test
    fun `strips output-only fields while preserving encrypted content`() {
        val item = buildJsonObject {
            put("id", "rs_tmp_1")
            put("type", "reasoning")
            put("status", "completed")
            put("format", "unknown")
            put("encrypted_content", "opaque")
        }

        val sanitized = sanitizeReasoningItem(item)

        assertEquals("reasoning", sanitized["type"]?.jsonPrimitive?.content)
        assertEquals("rs_tmp_1", sanitized["id"]?.jsonPrimitive?.content)
        assertNull(sanitized["status"])
        assertNull(sanitized["format"])
        // Encrypted (opaque) reasoning must survive the round-trip for stateless replay.
        assertEquals("opaque", sanitized["encrypted_content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `preserves a null encrypted_content value`() {
        val item = buildJsonObject {
            put("type", "reasoning")
            put("id", "rs_1")
            put("status", "completed")
            put("encrypted_content", null)
        }

        val sanitized = sanitizeReasoningItem(item)

        assertEquals("reasoning", sanitized["type"]?.jsonPrimitive?.content)
        assertNull(sanitized["status"])
        assertTrue(sanitized.containsKey("encrypted_content"), "encrypted_content key must be preserved even when null")
        assertTrue(sanitized["encrypted_content"] is JsonNull)
    }

    @Test
    fun `preserves summary and content parts while stripping unknown nested fields`() {
        val item = buildJsonObject {
            put("type", "reasoning")
            put("id", "rs_1")
            put("summary", buildJsonArray {
                add(buildJsonObject {
                    put("type", "summary_text")
                    put("text", "A summary.")
                    put("extra", "nested")
                })
            })
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", "reasoning_text")
                    put("text", "Chain of thought.")
                })
            })
        }

        val sanitized = sanitizeReasoningItem(item)

        val summaryPart = sanitized["summary"]?.jsonArray?.get(0)?.jsonObject
        assertEquals("summary_text", summaryPart?.get("type")?.jsonPrimitive?.content)
        assertEquals("A summary.", summaryPart?.get("text")?.jsonPrimitive?.content)
        assertNull(summaryPart?.get("extra"))

        val contentPart = sanitized["content"]?.jsonArray?.get(0)?.jsonObject
        assertEquals("reasoning_text", contentPart?.get("type")?.jsonPrimitive?.content)
        assertEquals("Chain of thought.", contentPart?.get("text")?.jsonPrimitive?.content)
    }

    @Test
    fun `sanitizes a realistic OpenRouter reasoning item like the one persisted in the database`() {
        val item = buildJsonObject {
            put("id", "rs_tmp_6er3482x13w")
            put("type", "reasoning")
            put("status", "completed")
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", "reasoning_text")
                    put("text", "The user wants me to test the search_files tool.")
                })
            })
            put("summary", buildJsonArray { })
            put("format", "unknown")
        }

        val sanitized = sanitizeReasoningItem(item)

        assertEquals("reasoning", sanitized["type"]?.jsonPrimitive?.content)
        assertEquals("rs_tmp_6er3482x13w", sanitized["id"]?.jsonPrimitive?.content)
        assertNull(sanitized["status"], "status is an output-only field and must be stripped")
        assertNull(sanitized["format"], "format is provider-specific and must be stripped")
        assertEquals("reasoning_text", sanitized["content"]?.jsonArray?.get(0)?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("The user wants me to test the search_files tool.", sanitized["content"]?.jsonArray?.get(0)?.jsonObject?.get("text")?.jsonPrimitive?.content)
        assertEquals(true, sanitized["summary"]?.jsonArray?.isEmpty())
    }

    @Test
    fun `empty input lists produce an empty sanitized list`() {
        assertTrue(sanitizeReasoningItems(emptyList()).isEmpty())
    }

    @Test
    fun `replay to encrypted same-model target keeps the encrypted payload`() {
        val item = buildJsonObject {
            put("type", "reasoning")
            put("id", "rs_1")
            put("summary", buildJsonArray { })
            // `content` and `encrypted_content` are mutually exclusive: encrypted items carry an
            // (empty) `content` array plus the opaque payload.
            put("content", buildJsonArray { })
            put("encrypted_content", "opaque")
        }

        val replayed = adaptReasoningItemForReplay(
            reasoningItem = item,
            reasoningEncrypted = true,
            sourceModelId = 1L,
            targetModelId = 1L
        )

        assertNotNull(replayed, "same-model encrypted items are always replayed")
        assertEquals("opaque", replayed["encrypted_content"]?.jsonPrimitive?.content)
        assertEquals("reasoning", replayed["type"]?.jsonPrimitive?.content)
        // The (empty) content array is harmless for encrypted-mode targets; the opaque payload is the payload.
        assertTrue(replayed["content"] == null || replayed["content"]?.jsonArray?.isEmpty() == true)
    }

    @Test
    fun `replay to encrypted different-model target skips the item entirely`() {
        val item = buildJsonObject {
            put("type", "reasoning")
            put("id", "rs_1")
            put("summary", buildJsonArray { })
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", "reasoning_text")
                    put("text", "Chain of thought.")
                })
            })
            put("encrypted_content", "opaque")
        }

        val replayed = adaptReasoningItemForReplay(
            reasoningItem = item,
            reasoningEncrypted = true,
            sourceModelId = 2L,
            targetModelId = 1L
        )

        assertNull(replayed, "a foreign encrypted item must not be replayed to an encrypted-mode target")
    }

    @Test
    fun `replay to encrypted target with unknown source skips the item entirely`() {
        val item = buildJsonObject {
            put("type", "reasoning")
            put("id", "rs_1")
            put("summary", buildJsonArray { })
            put("encrypted_content", "opaque")
        }

        val replayed = adaptReasoningItemForReplay(
            reasoningItem = item,
            reasoningEncrypted = true,
            sourceModelId = null,
            targetModelId = 1L
        )

        assertNull(replayed, "null source (e.g. deleted model) must not be replayed to an encrypted-mode target")
    }

    @Test
    fun `replay to encrypted different-model target skips even a non-empty summary`() {
        val item = buildJsonObject {
            put("type", "reasoning")
            put("id", "rs_1")
            put("summary", buildJsonArray {
                add(buildJsonObject {
                    put("type", "summary_text")
                    put("text", "Foreign reasoning summary.")
                })
            })
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", "reasoning_text")
                    put("text", "Chain of thought.")
                })
            })
            put("encrypted_content", "opaque")
        }

        val replayed = adaptReasoningItemForReplay(
            reasoningItem = item,
            reasoningEncrypted = true,
            sourceModelId = 2L,
            targetModelId = 1L
        )

        // Only the exact producing model may receive an encrypted payload; anything else is partial and
        // must not be replayed, even when a plaintext summary is present.
        assertNull(replayed, "a foreign encrypted item must never be replayed to an encrypted-mode target")
    }

    @Test
    fun `replay to encrypted unknown-source target skips even a non-empty summary`() {
        val item = buildJsonObject {
            put("type", "reasoning")
            put("id", "rs_1")
            put("summary", buildJsonArray {
                add(buildJsonObject {
                    put("type", "summary_text")
                    put("text", "Unknown-source summary.")
                })
            })
            put("encrypted_content", "opaque")
        }

        val replayed = adaptReasoningItemForReplay(
            reasoningItem = item,
            reasoningEncrypted = true,
            sourceModelId = null,
            targetModelId = 1L
        )

        assertNull(replayed, "unknown-source items are never replayed to an encrypted-mode target")
    }

    @Test
    fun `replay to encrypted same-model target skips an item without encrypted_content`() {
        val item = buildJsonObject {
            put("type", "reasoning")
            put("id", "rs_1")
            put("summary", buildJsonArray {
                add(buildJsonObject {
                    put("type", "summary_text")
                    put("text", "Summary only.")
                })
            })
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", "reasoning_text")
                    put("text", "Chain of thought.")
                })
            })
            put("encrypted_content", null)
        }

        val replayed = adaptReasoningItemForReplay(
            reasoningItem = item,
            reasoningEncrypted = true,
            sourceModelId = 1L,
            targetModelId = 1L
        )

        assertNull(replayed, "an encrypted-mode target needs the opaque payload; a shell without it is partial")
    }

    @Test
    fun `replay to plaintext target keeps the plaintext content`() {
        // A plaintext-origin item carries `content` and no `encrypted_content` (mutually exclusive).
        val item = buildJsonObject {
            put("type", "reasoning")
            put("id", "rs_1")
            put("summary", buildJsonArray { })
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", "reasoning_text")
                    put("text", "Chain of thought.")
                })
            })
        }

        val replayed = adaptReasoningItemForReplay(
            reasoningItem = item,
            reasoningEncrypted = false,
            sourceModelId = 1L,
            targetModelId = 1L
        )

        assertNotNull(replayed, "plaintext targets always replay the item")
        assertEquals("Chain of thought.", replayed["content"]?.jsonArray?.get(0)?.jsonObject?.get("text")?.jsonPrimitive?.content)
        assertNull(replayed["encrypted_content"], "plaintext items carry no encrypted_content")
    }

    @Test
    fun `replay to plaintext target replays an encrypted-origin shell with space content`() {
        // An item produced by an encrypted-mode model has an EMPTY `content` array and only the opaque
        // payload. Replayed to a plaintext target it is still sent (with space text) so the provider
        // sees a reasoning item — DeepSeek thinking mode requires one per assistant message.
        val item = buildJsonObject {
            put("type", "reasoning")
            put("id", "rs_1")
            put("summary", buildJsonArray { })
            put("content", buildJsonArray { })  // empty content array
            put("encrypted_content", "opaque")
        }

        val replayed = adaptReasoningItemForReplay(
            reasoningItem = item,
            reasoningEncrypted = false,
            sourceModelId = 1L,
            targetModelId = 1L
        )

        assertNotNull(replayed, "plaintext target replays the item so the provider sees a reasoning item")
        val contentArray = replayed["content"]?.jsonArray
        assertNotNull(contentArray)
        assertEquals(1, contentArray.size)
        assertEquals(" ", contentArray[0].jsonObject["text"]?.jsonPrimitive?.content)
        assertNull(replayed["encrypted_content"], "encrypted_content is dropped for plaintext targets")
    }

    @Test
    fun `replay to plaintext target replays an item with neither content nor encrypted_content`() {
        // Always replay so the provider sees a reasoning item; space content is added (DeepSeek fix).
        val item = buildJsonObject {
            put("type", "reasoning")
            put("id", "rs_1")
            put("summary", buildJsonArray { })
        }

        val replayed = adaptReasoningItemForReplay(
            reasoningItem = item,
            reasoningEncrypted = false,
            sourceModelId = 1L,
            targetModelId = 1L
        )

        assertNotNull(replayed, "plaintext target always replays the item")
        val contentArray = replayed["content"]?.jsonArray
        assertNotNull(contentArray)
        assertEquals(1, contentArray.size)
        assertEquals(" ", contentArray[0].jsonObject["text"]?.jsonPrimitive?.content)
        assertNull(replayed["encrypted_content"])
    }

    @Test
    fun `replay to unknown-mode target replays the item when capability is unknown`() {
        // When capability is unknown we replay as-is to preserve potentially useful reasoning.
        val item = buildJsonObject {
            put("type", "reasoning")
            put("id", "rs_1")
            put("summary", buildJsonArray { })
            put("encrypted_content", "opaque")
        }

        val replayed = adaptReasoningItemForReplay(
            reasoningItem = item,
            reasoningEncrypted = null,
            sourceModelId = 1L,
            targetModelId = 1L
        )

        assertNotNull(replayed, "unknown capability replays the item as-is")
        assertEquals("opaque", replayed["encrypted_content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `replay to unknown-mode target defaults to plaintext`() {
        // Unknown mode defaults to plaintext; a plaintext-origin item (no encrypted_content) replays.
        val item = buildJsonObject {
            put("type", "reasoning")
            put("id", "rs_1")
            put("summary", buildJsonArray { })
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", "reasoning_text")
                    put("text", "Chain of thought.")
                })
            })
        }

        val replayed = adaptReasoningItemForReplay(
            reasoningItem = item,
            reasoningEncrypted = null,
            sourceModelId = 1L,
            targetModelId = 1L
        )

        assertNotNull(replayed, "unknown mode defaults to plaintext and replays the item")
        assertEquals("Chain of thought.", replayed["content"]?.jsonArray?.get(0)?.jsonObject?.get("text")?.jsonPrimitive?.content)
        assertNull(replayed["encrypted_content"], "plaintext items carry no encrypted_content")
    }

    @Test
    fun `detectReasoningEncryption returns null for null or empty input`() {
        assertNull(detectReasoningEncryption(null))
        assertNull(detectReasoningEncryption(emptyList()))
    }

    @Test
    fun `detectReasoningEncryption returns true when encrypted_content is present`() {
        val items = listOf(
            buildJsonObject {
                put("type", "reasoning")
                put("id", "rs_1")
                put("encrypted_content", "opaque")
            }
        )

        assertEquals(true, detectReasoningEncryption(items))
    }

    @Test
    fun `detectReasoningEncryption ignores a null encrypted_content value`() {
        val items = listOf(
            buildJsonObject {
                put("type", "reasoning")
                put("id", "rs_1")
                put("encrypted_content", null)
            },
            buildJsonObject {
                put("type", "reasoning")
                put("id", "rs_2")
                put("content", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "reasoning_text")
                        put("text", "Chain of thought.")
                    })
                })
            }
        )

        assertEquals(false, detectReasoningEncryption(items))
    }

    @Test
    fun `detectReasoningEncryption returns false for plaintext content`() {
        val items = listOf(
            buildJsonObject {
                put("type", "reasoning")
                put("id", "rs_1")
                put("content", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "reasoning_text")
                        put("text", "Chain of thought.")
                    })
                })
            }
        )

        assertEquals(false, detectReasoningEncryption(items))
    }

    @Test
    fun `detectReasoningEncryption returns null for inconclusive items`() {
        val items = listOf(
            buildJsonObject {
                put("type", "reasoning")
                put("id", "rs_1")
                put("summary", buildJsonArray { })
            }
        )

        assertNull(detectReasoningEncryption(items))
    }

    @Test
    fun `withStreamedReasoningText appends one reasoning_text item for streamed text`() {
        val completed = buildJsonObject {
            put("type", "reasoning")
            put("id", "rs_1")
            put("encrypted_content", "opaque")
        }

        val result = withStreamedReasoningText(listOf(completed), "streamed thought")

        assertEquals(2, result.size)
        assertEquals(completed, result[0])
        val appended = result[1]
        assertEquals("reasoning", appended["type"]?.jsonPrimitive?.content)
        val part = appended["content"]?.jsonArray?.single()?.jsonObject
        assertEquals("reasoning_text", part?.get("type")?.jsonPrimitive?.content)
        assertEquals("streamed thought", part?.get("text")?.jsonPrimitive?.content)
        // No opaque payload is invented for the derived item.
        assertNull(appended["encrypted_content"])
    }

    @Test
    fun `withStreamedReasoningText appends to an empty item list`() {
        val result = withStreamedReasoningText(emptyList(), "only streamed")

        assertEquals(1, result.size)
        assertEquals(
            "only streamed",
            result[0]["content"]?.jsonArray?.single()?.jsonObject?.get("text")?.jsonPrimitive?.content
        )
    }

    @Test
    fun `withStreamedReasoningText keeps the items unchanged for blank text`() {
        val items = listOf(buildJsonObject { put("type", "reasoning") })

        assertEquals(items, withStreamedReasoningText(items, ""))
        assertEquals(items, withStreamedReasoningText(items, "   "))
    }

    @Test
    fun `withStreamedReasoningText keeps the items unchanged when one already carries plaintext content`() {
        val withContent = buildJsonObject {
            put("type", "reasoning")
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", "reasoning_text")
                    put("text", "Completed chain of thought.")
                })
            })
        }

        assertEquals(listOf(withContent), withStreamedReasoningText(listOf(withContent), "streamed thought"))
    }

    @Test
    fun `the item appended for streamed text looks like plaintext reasoning to mode detection`() {
        // Why the derived item must stay out of capability detection: on its own it looks like evidence that the
        // model delivers plaintext reasoning, while only the completed items describe the model's own payload.
        val appended = withStreamedReasoningText(emptyList(), "streamed thought")

        assertEquals(false, detectReasoningEncryption(appended))
    }

    @Test
    fun `withStreamedReasoningText tolerates a content value it cannot read as an array`() {
        // The sanitizer preserves whatever the provider sent for `content`, including a value that is not an array.
        // A throwing read here would fail the streaming finalization and turn a successfully generated answer into
        // an unexpected failure, so the helper must stay tolerant instead.
        val stringContent = buildJsonObject {
            put("type", "reasoning")
            put("content", "not an array")
        }
        val nullContent = buildJsonObject {
            put("type", "reasoning")
            put("content", null)
        }

        val fromStringContent = withStreamedReasoningText(listOf(stringContent), "streamed thought")
        assertEquals(2, fromStringContent.size)
        assertEquals(stringContent, fromStringContent[0])
        assertEquals("reasoning", fromStringContent[1]["type"]?.jsonPrimitive?.content)

        val fromNullContent = withStreamedReasoningText(listOf(nullContent), "streamed thought")
        assertEquals(2, fromNullContent.size)
        assertEquals(nullContent, fromNullContent[0])
    }
}
