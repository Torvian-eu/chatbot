package eu.torvian.chatbot.common.models.tool.specs

import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.AUTOMATIC_COMPACTION_ENABLED_PROPERTY
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.COMPACTION_THRESHOLD_TOKENS_PROPERTY
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.CREATE_MODEL_PRESET_NAME
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.DELETE_MODEL_PRESET_NAME
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.DESCRIPTION_PROPERTY
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.DISPLAY_NAME_PROPERTY
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.LIST_MODEL_PRESETS_NAME
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.MODEL_ID_PROPERTY
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.MODEL_PRESET_ID_PROPERTY
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.MODEL_SETTINGS_ID_PROPERTY
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.NAME_PROPERTY
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.READ_MODEL_PRESET_NAME
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.ServerBuiltInToolSpec
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.UPDATE_MODEL_PRESET_NAME
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Model preset tool specifications, in stable catalog order.
 *
 * The order is part of the contract: it defines the seeding order and the order in which
 * the tools appear in listings. Do not reorder entries.
 */
internal val modelPresetToolSpecs: List<ServerBuiltInToolSpec> = listOf(
    ServerBuiltInToolSpec(
        name = LIST_MODEL_PRESETS_NAME,
        description = "Lists all model presets owned by the current user, ordered by id, " +
            "returning each preset's id, name, display name, description, referenced model " +
            "id, referenced settings profile id (both null when unset), and its creation and " +
            "update timestamps. Each preset also reports its compaction configuration: the " +
            "automatic compaction flag and the optional per-preset token threshold (null when " +
            "the preset defers to the user preference threshold). A preset bundles one model " +
            "with one settings profile and is " +
            "the sole source of the LLM configuration of every agent role bound to it. Use " +
            "read_model_preset with a preset id to inspect a single preset.",
        inputSchema = emptyObjectSchema()
    ),
    ServerBuiltInToolSpec(
        name = READ_MODEL_PRESET_NAME,
        description = "Reads one model preset owned by the current user by its id, returning " +
            "the full preset with its name, display name, description, the referenced model " +
            "id and settings profile id (null when unset), its automatic compaction flag and " +
            "optional per-preset token threshold (null when the preset defers to the user " +
            "preference threshold), and its creation and update " +
            "timestamps.",
        inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put(
                    MODEL_PRESET_ID_PROPERTY,
                    integerProperty("Id of the model preset to read. The preset must be owned by the current user.")
                )
            })
            put("required", buildJsonArray {
                add(MODEL_PRESET_ID_PROPERTY)
            })
        }
    ),
    ServerBuiltInToolSpec(
        name = CREATE_MODEL_PRESET_NAME,
        description = "Creates a new model preset owned by the current user. A model preset is " +
            "a named bundle of one model plus one settings profile and is the sole source of " +
            "the LLM configuration of every agent role bound to it. The name must be unique " +
            "among the current user's presets; the server trims it and rejects a blank or " +
            "longer-than-255-character value. Both references are optional and must be " +
            "accessible by the current user; when both are given, the settings profile must " +
            "belong to the given model. The preset layer imposes no model-type restriction, " +
            "so an embedding model is accepted. Automatic (threshold-triggered) compaction of " +
            "the preset's sessions is controlled by this preset: it is enabled by default and " +
            "additionally requires the user's own compaction preference to be enabled, while an " +
            "omitted compaction_threshold_tokens uses the user preference's threshold (a supplied " +
            "threshold must be at least 1). User-requested compaction is always available and is " +
            "not affected by this flag. Returns the created preset's full JSON " +
            "including its server-generated id and timestamps.",
        inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put(
                    NAME_PROPERTY,
                    stringProperty("Unique (per user) model preset name, non-blank and at most 255 characters.")
                )
                put(DISPLAY_NAME_PROPERTY, stringProperty("Optional human-friendly display name."))
                put(DESCRIPTION_PROPERTY, stringProperty("Free-form description of the preset's purpose."))
                put(
                    MODEL_ID_PROPERTY,
                    integerProperty(
                        "Optional id of the model the preset bundles. The model must be " +
                            "accessible by the current user; the preset layer allows any " +
                            "model type (chat, embedding, ...)."
                    )
                )
                put(
                    MODEL_SETTINGS_ID_PROPERTY,
                    integerProperty(
                        "Optional id of the settings profile the preset bundles. The " +
                            "profile must be accessible by the current user and, when " +
                            "model_id is also given, must belong to that model."
                    )
                )
                put(
                    AUTOMATIC_COMPACTION_ENABLED_PROPERTY,
                    booleanProperty(
                        "Whether automatic (threshold-triggered) compaction is enabled for turns " +
                            "running on this preset. Defaults to true; false disables it for the " +
                            "preset's sessions even when the user's compaction preference is " +
                            "enabled. It never restricts user-requested compaction."
                    )
                )
                put(
                    COMPACTION_THRESHOLD_TOKENS_PROPERTY,
                    integerProperty(
                        "Optional compaction threshold in input tokens for this preset. " +
                            "Omit or pass null to use the user's compaction preference " +
                            "threshold (100000 by default); a supplied value must be at " +
                            "least 1."
                    )
                )
            })
            put("required", buildJsonArray {
                add(NAME_PROPERTY)
            })
        }
    ),
    ServerBuiltInToolSpec(
        name = UPDATE_MODEL_PRESET_NAME,
        description = "Updates one model preset owned by the current user (patch semantics): " +
            "provide only the fields to change; every omitted or null field keeps its " +
            "persisted value. Pass an explicit empty string to clear description or " +
            "display_name, or pass 0 for model_id or model_settings_id to clear that " +
            "reference (0 is never a valid id). A name cannot be cleared (it must stay " +
            "non-blank and " +
            "unique per user). Both references must be accessible by the current user and, " +
            "when both are set, the settings profile must belong to the model, so re-point " +
            "both together. Compaction is patched the same way: an omitted or null " +
            "automatic_compaction_enabled keeps the persisted flag, an explicit boolean sets it, an " +
            "omitted or null compaction_threshold_tokens keeps the persisted threshold, and a " +
            "compaction_threshold_tokens of 0 " +
            "clears it back to the user preference's threshold (0 is never a valid stored " +
            "threshold, so any value of at least 1 sets an override). Returns a concise " +
            "one-line summary of the operation.",
        inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put(
                    MODEL_PRESET_ID_PROPERTY,
                    integerProperty("Id of the model preset to update. The preset must be owned by the current user.")
                )
                put(
                    NAME_PROPERTY,
                    stringProperty("New unique (per user) preset name; a name cannot be cleared.")
                )
                put(
                    DISPLAY_NAME_PROPERTY,
                    stringProperty(
                        "New optional human-friendly display name; an explicit empty " +
                            "string clears it, an omitted or null value keeps the persisted one."
                    )
                )
                put(
                    DESCRIPTION_PROPERTY,
                    stringProperty(
                        "New free-form description; an explicit empty string clears it, an " +
                            "omitted or null value keeps the persisted one."
                    )
                )
                put(
                    MODEL_ID_PROPERTY,
                    integerProperty(
                        "New id of the model the preset bundles. Omit or pass null to keep " +
                            "the persisted model; pass 0 to clear the reference (0 is never a " +
                            "valid model id). The model must be accessible by the current user."
                    )
                )
                put(
                    MODEL_SETTINGS_ID_PROPERTY,
                    integerProperty(
                        "New id of the settings profile the preset bundles. Omit or pass " +
                            "null to keep the persisted profile; pass 0 to clear the reference " +
                            "(0 is never a valid settings id). The profile must be accessible " +
                            "by the current user and, when model_id is also set, must belong " +
                            "to that model."
                    )
                )
                put(
                    AUTOMATIC_COMPACTION_ENABLED_PROPERTY,
                    booleanProperty(
                        "New automatic-compaction flag for the preset's turns. Omit or pass " +
                            "null to keep the persisted flag; true enables threshold-triggered " +
                            "compaction (still subject to the user's compaction preference), " +
                            "false disables it for the preset's sessions. User-requested " +
                            "compaction is unaffected either way."
                    )
                )
                put(
                    COMPACTION_THRESHOLD_TOKENS_PROPERTY,
                    integerProperty(
                        "New compaction threshold in input tokens. Omit or pass null to " +
                            "keep the persisted threshold; pass 0 to clear it so the user's " +
                            "compaction preference threshold applies again (0 is never a " +
                            "valid stored threshold); a value of at least 1 sets an " +
                            "override."
                    )
                )
            })
            put("required", buildJsonArray {
                add(MODEL_PRESET_ID_PROPERTY)
            })
        }
    ),
    ServerBuiltInToolSpec(
        name = DELETE_MODEL_PRESET_NAME,
        description = "Deletes one model preset owned by the current user by its id. Agent " +
            "roles bound to the preset are not deleted: they lose the reference and become " +
            "non-sendable until another preset is attached. Returns a concise one-line " +
            "summary of the operation.",
        inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put(
                    MODEL_PRESET_ID_PROPERTY,
                    integerProperty("Id of the model preset to delete. The preset must be owned by the current user.")
                )
            })
            put("required", buildJsonArray {
                add(MODEL_PRESET_ID_PROPERTY)
            })
        }
    ),
)
