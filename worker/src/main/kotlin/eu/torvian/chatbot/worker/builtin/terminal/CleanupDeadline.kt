package eu.torvian.chatbot.worker.builtin.terminal

import eu.torvian.chatbot.worker.builtin.command.deadlineAfterNanos
import java.util.concurrent.TimeUnit

/**
 * Time one cleanup step is entitled to, even when the budget it inherited is already spent.
 *
 * Chosen to cover one TERM-then-KILL relay plus one helper call, which is what the longest cleanup
 * step does; a step that waits this long is the pathological case, not the normal one.
 */
internal const val CLEANUP_GRACE_MILLIS = 2_000L

/**
 * Returns the later of [inherited] and a fresh cleanup grace.
 *
 * A cleanup step runs after the work that shares its deadline - a slow preparation, the relay for the
 * previous target, or the sessions closed before this one - so passing an expired deadline through
 * unchanged would start a helper only to kill it before it can signal a process or remove a secret
 * tree. Each step derives its own deadline, so no step can be starved by the ones before it while
 * every step stays individually bounded.
 *
 * @param inherited Deadline the caller shares with the work it did before this cleanup step.
 * @return A deadline at least [CLEANUP_GRACE_MILLIS] away, measured from the current monotonic time.
 */
internal fun withCleanupGrace(inherited: Long): Long =
    maxOf(inherited, deadlineAfterNanos(TimeUnit.MILLISECONDS.toNanos(CLEANUP_GRACE_MILLIS)))
