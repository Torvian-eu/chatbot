package eu.torvian.chatbot.app.viewmodel.chat

import eu.torvian.chatbot.app.utils.misc.LruCache

/**
 * Long-lived manager that maps chat sessions to a bounded pool of [ChatViewModel] slots.
 *
 * The mapping must outlive the `ChatScreen` composition: navigating to another destination (e.g.
 * Settings) disposes `ChatScreen`, and a composable-local allocator would be recreated on return,
 * remapping sessions to different slots and spuriously reloading their ViewModels. By living in the
 * Koin graph for the authenticated session, the same slot is reused for the same session across
 * navigation, so a session's [ChatViewModel] (and its in-flight stream) is preserved.
 *
 * @property maxSlots Maximum number of ViewModel slots kept alive at the same time. When exceeded,
 *   the least recently used slot is reused for a newly selected session.
 */
class ChatViewModelSlotManager(
    private val maxSlots: Int
) {
    companion object {
        /**
         * Default number of chat ViewModel slots kept alive at the same time.
         *
         * Slots are reused using LRU order when this limit is reached.
         */
        const val DEFAULT_MAX_SLOTS = 20
    }

    /** Session -> slot mapping with access-order tracking. */
    private val sessionToSlot = LruCache<Long, Int>(maxSlots)

    /** Pool of slots that have never been assigned yet. */
    private val freeSlots = (0 until maxSlots).toMutableSet()

    /**
     * Resolves the stable Koin key for the given session.
     *
     * The same session always resolves to the same slot while it is resident, so its
     * [ChatViewModel] instance is reused across navigation. When the pool is full, the slot of the
     * least recently used session is reused.
     *
     * @param sessionId The session to resolve a slot for, or null when no session is selected.
     * @return A stable slot key (`chat_slot_<n>`) or `chat_none` when [sessionId] is null.
     */
    fun resolveViewModelKey(sessionId: Long?): String {
        if (sessionId == null) return "chat_none"

        val assignedSlot = sessionToSlot.getOrPut(sessionId) {
            // Prefer an unused slot first.
            freeSlots.firstOrNull()
                .also { freeSlots.remove(it) }
                // If none are free, reuse the slot that belongs to the least recently used session.
                ?: sessionToSlot.leastRecentlyUsedValue
                ?: error("Expected an LRU session when no free slots are available")
        }
        return slotKey(assignedSlot)
    }

    private fun slotKey(slot: Int): String = "chat_slot_$slot"
}
