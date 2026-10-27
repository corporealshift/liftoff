package com.liftoff.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.liftoff.app.LiftoffApplication
import com.liftoff.app.ui.LiftoffShell
import com.liftoff.app.ui.theme.LiftoffTheme
import com.liftoff.app.work.AppOpenSweep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(0, 0),
            navigationBarStyle = SystemBarStyle.light(0, 0),
        )
        val container = (applicationContext as LiftoffApplication).container
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val mission = container.missionManager.onAppOpen()
                // On the activity's scope, not this block's: a repair that has started finishes even if
                // the user switches away before it ends.
                lifecycleScope.launch(Dispatchers.IO) {
                    AppOpenSweep(
                        db = container.database,
                        // Lambdas, not bound references: the container asks WorkManager for itself, only
                        // for a row it has something to do about.
                        enqueue = { id -> container.generationEnqueuer().enqueue(id) },
                        cancel = { id -> container.generationCanceller().cancel(id) },
                    ).run(mission.weekStart)
                }
            }
        }
        setContent {
            LiftoffTheme {
                LiftoffShell()
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun MainActivityPreview() {
    if (LocalInspectionMode.current) {
        LiftoffTheme {
            LiftoffShell()
        }
    }
}
