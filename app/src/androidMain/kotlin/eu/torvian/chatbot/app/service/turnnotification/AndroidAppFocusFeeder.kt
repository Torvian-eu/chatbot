package eu.torvian.chatbot.app.service.turnnotification

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle

/**
 * Android feeder for [AppFocusState] driven by activity lifecycle callbacks.
 *
 * Compose-driven focus state on Android is frame-bound, so a composition collector can stall exactly
 * when the activity stops drawing — the case the alert feature depends on. These callbacks are
 * delivered independently of frames, so the state always flips.
 *
 * Registration happens in [init], which runs when the app's dependency graph starts. On Android that
 * is after the hosting activity has already been resumed, because the graph is only built once the
 * startup configuration has loaded. This feeder therefore carries the transitions that take the app
 * out of sight — where a frame-bound focus feed can stall — and reports "unfocused" as its first
 * state, while the focused report comes from the composed shell's window-focus feed. Both write the
 * same shared state, and the most recent report is the one that is read.
 *
 * @param context Context used to reach the [Application] instance.
 * @property focusState State the observed lifecycle transitions are written to.
 */
class AndroidAppFocusFeeder(
    context: Context,
    private val focusState: AppFocusState
) : Application.ActivityLifecycleCallbacks {

    private val application: Application = context.applicationContext as Application

    init {
        application.registerActivityLifecycleCallbacks(this)
        // A process can host several activities but only one is visible; starting unfocused until
        // the first resume keeps a backgrounded app from suppressing its own alerts.
        focusState.setFocused(false)
    }

    override fun onActivityResumed(activity: Activity) {
        focusState.setFocused(true)
    }

    override fun onActivityPaused(activity: Activity) {
        focusState.setFocused(false)
    }

    override fun onActivityStopped(activity: Activity) {
        focusState.setFocused(false)
    }

    // Visibility starts before interactivity: the resume callback is the attention boundary, so the
    // started transition leaves the state untouched instead of briefly reporting focus.
    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}
