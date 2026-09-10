package eu.torvian.chatbot.app.testutils.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll

/**
 * Runs [trigger] and waits until the coroutine it launched on this scope (and, transitively, its
 * children) has completed.
 *
 * ViewModels that load several catalogs use Arrow's `parZip` without a context, which runs its
 * branches on `Dispatchers.Default` — threads that live outside a `runTest` scheduler. Asserting on
 * those branch calls (or on their outcome, e.g. a failure notification) straight after the trigger
 * would therefore race the dispatcher pool and fail intermittently under load. Joining the job the
 * trigger launched makes such assertions deterministic through structured concurrency: a job
 * completes only after its children (the `parZip` branches) are done, so the following verifications
 * observe completed calls without sleeps, timeouts or production changes.
 *
 * Only the children started by [trigger] are awaited, because a scope's long-lived children (such as
 * the `stateIn(..., SharingStarted.WhileSubscribed())` streams of the same ViewModel) never complete.
 * A trigger that launches nothing leaves nothing to await, so a genuine "not loaded" regression still
 * fails in the subsequent verification instead of hanging here.
 *
 * @receiver The ViewModel scope whose newly launched coroutine is awaited.
 * @param trigger Starts the work to await (typically a ViewModel `load…()` call).
 */
suspend fun CoroutineScope.awaitLaunchedBy(trigger: () -> Unit) {
    val existing = coroutineContext.job.children.toSet()
    trigger()
    // `children` lists only the jobs that are still active: a load already finished before this
    // snapshot has nothing to await (its calls are recorded anyway), while one still suspended in
    // `parZip` is awaited until its branches have run.
    coroutineContext.job.children.filterNot { it in existing }.toList().joinAll()
}
