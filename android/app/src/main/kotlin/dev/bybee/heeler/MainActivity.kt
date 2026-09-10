package dev.bybee.heeler

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.bybee.heeler.ui.HeelerApp
import dev.bybee.heeler.ui.HeelerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as HeelerApplication).container
        setContent {
            HeelerTheme {
                HeelerApp(container)
            }
        }
    }
}
