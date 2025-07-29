package eu.torvian.chatbot.app.main

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import eu.torvian.chatbot.app.compose.AppShell
import eu.torvian.chatbot.app.koin.appModule
import org.koin.compose.KoinApplication

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            KoinApplication(application = {
                modules(appModule("http://localhost:8080"))
            }) {
                AppShell()
            }
        }
    }
}