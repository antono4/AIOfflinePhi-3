package com.phi3chat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.phi3chat.ui.Phi3ChatNavHost
import com.phi3chat.ui.theme.Phi3ChatTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as Phi3ChatApp

        setContent {
            val settings by app.settingsRepository.settings.collectAsState(
                initial = com.phi3chat.data.Settings()
            )

            Phi3ChatTheme(useDynamicColor = settings.useDynamicColor) {
                val viewModel: ChatViewModel = viewModel(
                    factory = ChatViewModel.factory(app)
                )
                Phi3ChatNavHost(viewModel = viewModel)
            }
        }
    }
}
