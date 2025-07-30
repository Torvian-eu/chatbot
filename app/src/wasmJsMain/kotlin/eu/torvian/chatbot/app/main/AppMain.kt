package eu.torvian.chatbot.app.main

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import eu.torvian.chatbot.app.compose.AppShell
import eu.torvian.chatbot.app.koin.appModule
import kotlinx.browser.document
import org.koin.compose.KoinApplication

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport(document.body!!) {
        KoinApplication(application = {
            modules(appModule("http://localhost:9999"))
        }) {
            AppShell()
        }
    }
}