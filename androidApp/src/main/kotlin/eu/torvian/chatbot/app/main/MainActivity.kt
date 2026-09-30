package eu.torvian.chatbot.app.main

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import eu.torvian.chatbot.app.compose.startup.CommonAppLifecycleManager
import eu.torvian.chatbot.app.config.FileSystemClientConfigLoader
import eu.torvian.chatbot.app.koin.androidModule
import eu.torvian.chatbot.app.koin.appModule
import eu.torvian.chatbot.app.service.turnnotification.AndroidTurnNotificationClickBus
import eu.torvian.chatbot.app.service.turnnotification.AndroidTurnNotificationPermissionBus
import eu.torvian.chatbot.app.utils.misc.createKmpLogger
import org.koin.android.ext.koin.androidContext

private val logger = createKmpLogger("AndroidMainActivity")

class MainActivity : ComponentActivity() {

    /**
     * Launcher for the runtime notification permission, registered before the activity can be
     * started as the API requires.
     *
     * The answer is forwarded to the permission bus, where the request that raised the prompt is
     * waiting; a recreated activity re-registers its own launcher, so an answer given during a
     * configuration change still reaches the waiting settings surface.
     */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            AndroidTurnNotificationPermissionBus.publishAnswer(granted)
        }

    /**
     * Launcher this instance offers to the permission bus while it lives.
     *
     * Held as one stable reference so this instance withdraws only its own launcher on destroy, and a
     * newer instance that already installed its launcher keeps it.
     */
    private val permissionPromptLauncher: (String) -> Unit = { permission ->
        notificationPermissionLauncher.launch(permission)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // A permission prompt needs a started activity, so the launcher is offered for as long as
        // this one lives.
        AndroidTurnNotificationPermissionBus.installPromptLauncher(permissionPromptLauncher)

        // A cold start from a turn notification carries the session to select; the click is consumed
        // so a later recreation of this activity, which re-delivers the same launch intent, cannot
        // replay it.
        if (savedInstanceState == null) {
            AndroidTurnNotificationClickBus.publishFromIntent(intent)
        }

        // Config files live in the app's private files directory, e.g.:
        // /data/data/eu.torvian.chatbot/files/config/
        val configDir = "${applicationContext.filesDir.absolutePath}/config"

        setContent {
            CommonAppLifecycleManager(
                configDir = configDir,
                configLoader = FileSystemClientConfigLoader(),
                onExit = {
                    logger.info("User requested application exit from error screen.")
                    finish()
                },
                koinApp = { config ->
                    androidContext(this@MainActivity)
                    modules(
                        androidModule(config),
                        appModule(config)
                    )
                }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Keep the latest intent as the activity's current one so a re-delivered click is not lost.
        setIntent(intent)
        AndroidTurnNotificationClickBus.publishFromIntent(intent)
    }

    override fun onDestroy() {
        // Withdrawing only this instance's launcher keeps a newer activity able to raise a prompt; the
        // launcher itself belongs to this instance and is dead once it is destroyed.
        AndroidTurnNotificationPermissionBus.withdrawPromptLauncher(permissionPromptLauncher)
        super.onDestroy()
    }
}