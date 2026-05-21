package dev.sortes.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import dev.sortes.app.ui.PapersScreen
import dev.sortes.app.ui.theme.SortesTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SortesTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    PapersScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}
