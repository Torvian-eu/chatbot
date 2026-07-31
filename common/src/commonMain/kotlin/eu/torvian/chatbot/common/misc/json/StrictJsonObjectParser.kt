package eu.torvian.chatbot.common.misc.json

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Parses JSON objects after enforcing the JSON string-literal control-character rule that must
 * hold before a serializer is allowed to inspect the payload.
 *
 * The serializer remains responsible for all other JSON syntax and object validation. This
 * pre-scan exists because callers may receive raw text from tools or models and need literal
 * control characters to be rejected consistently across execution paths.
 *
 * @param json Serializer configuration used for the actual JSON parsing.
 * @param raw Raw JSON text to validate and parse.
 * @return Parsed JSON object.
 * @throws IllegalArgumentException If an unescaped U+0000..U+001F character occurs in a string.
 * @throws Exception If [json] rejects the text or the parsed value is not an object.
 */
fun parseStrictJsonObject(json: Json, raw: String): JsonObject {
    var insideString = false
    var escaped = false

    raw.forEachIndexed { index, character ->
        if (insideString) {
            if (escaped) {
                // The following character is consumed as the escaped character; Json validates
                // whether the escape itself is one of JSON's permitted escape forms.
                escaped = false
            } else {
                when {
                    character == '\\' -> escaped = true
                    character == '"' -> insideString = false
                    character.code <= 0x1F -> throw IllegalArgumentException(
                        "Unescaped control character U+${character.code.toString(16).padStart(4, '0')} " +
                            "inside JSON string at index $index"
                    )
                }
            }
        } else if (character == '"') {
            insideString = true
        }
    }

    return json.parseToJsonElement(raw).jsonObject
}
