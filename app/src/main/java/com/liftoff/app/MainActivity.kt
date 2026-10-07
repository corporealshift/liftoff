package com.liftoff.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.liftoff.app.ui.theme.Cream
import com.liftoff.app.ui.theme.LiftoffTheme
import com.liftoff.app.ui.theme.TriStripe
import com.liftoff.app.ui.theme.Wordmark

class MainActivity : ComponentActivity() {
    // M1 replaces this with the navigation shell.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(0, 0),
            navigationBarStyle = SystemBarStyle.light(0, 0),
        )
        setContent {
            LiftoffTheme {
                Box(modifier = Modifier.fillMaxSize().background(Cream)) {
                    Column(
                        modifier = Modifier.systemBarsPadding(),
                    ) {
                        TriStripe()
                        Box(
                            modifier = Modifier.padding(top = 16.dp, start = 20.dp, end = 20.dp),
                        ) {
                            Wordmark()
                        }
                    }
                }
            }
        }
    }
}
