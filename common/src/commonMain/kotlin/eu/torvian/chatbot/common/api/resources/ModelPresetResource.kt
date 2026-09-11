package eu.torvian.chatbot.common.api.resources

import io.ktor.resources.*

/**
 * Resource definitions for user-owned model-preset endpoints.
 *
 * This resource defines the URL structure for model-preset CRUD operations:
 * - GET /api/v1/model-presets - List model presets owned by the user
 * - POST /api/v1/model-presets - Create a new model preset (the caller becomes the owner)
 * - GET /api/v1/model-presets/{presetId} - Get a specific model preset
 * - PUT /api/v1/model-presets/{presetId} - Update a specific model preset (full replacement)
 * - DELETE /api/v1/model-presets/{presetId} - Delete a specific model preset (bound roles survive,
 *   become preset-less and therefore non-sendable)
 */
@Resource("model-presets")
class ModelPresetResource(val parent: Api = Api()) {
    /**
     * Resource for operations on a specific model preset by ID.
     *
     * @property parent The parent [ModelPresetResource].
     * @property presetId The unique identifier of the model preset.
     */
    @Resource("{presetId}")
    class ById(val parent: ModelPresetResource = ModelPresetResource(), val presetId: Long)
}
