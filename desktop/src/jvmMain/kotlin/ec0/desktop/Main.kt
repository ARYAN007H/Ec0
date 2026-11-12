package ec0.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.material.MaterialTheme

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "ec0 — unified ecosystem",
        state = rememberWindowState(width = 720.dp, height = 480.dp),
    ) {
        MaterialTheme {
            Column(
                modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("ec0 desktop shell", style = MaterialTheme.typography.h4)
                Text(
                    "Synergy ports (computer profile): ${ec0.core.synergy.SynergyTransport.COMPUTER_PORT_LIST.joinToString()}",
                    style = MaterialTheme.typography.body1,
                )
            }
        }
    }
}
