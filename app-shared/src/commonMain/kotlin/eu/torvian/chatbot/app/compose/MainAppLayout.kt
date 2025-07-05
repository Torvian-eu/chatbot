package eu.torvian.chatbot.app.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import eu.torvian.chatbot.server.main.ServerInstanceInfo
import io.ktor.client.*
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.koin.compose.koinInject

@Composable
@Preview
fun MainAppLayout(serverInstanceInfo: ServerInstanceInfo) {
    var text by remember { mutableStateOf("Hello, World!") }

    MaterialTheme {
        Column {
            Button(onClick = {
                text = "Hello, Desktop!"
            }) {
                Text(text)
            }
            Text("Server URI: ${serverInstanceInfo.baseUri}")
            Test()
        }
    }
}


@Composable
fun Test(httpClient: HttpClient = koinInject()) {
    Text("HttpClient: $httpClient")
}