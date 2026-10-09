package com.liftoff.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.liftoff.app.R
import com.liftoff.app.ui.control.MissionControlScreen
import com.liftoff.app.ui.landed.LandedScreen
import com.liftoff.app.ui.launchpad.LaunchpadScreen
import com.liftoff.app.ui.mission.MissionScreen
import com.liftoff.app.ui.theme.ButtonShape
import com.liftoff.app.ui.theme.Cream
import com.liftoff.app.ui.theme.Ink
import com.liftoff.app.ui.theme.LiftoffIcons
import com.liftoff.app.ui.theme.LiftoffTheme
import com.liftoff.app.ui.theme.Mustard
import com.liftoff.app.ui.theme.Rule
import com.liftoff.app.ui.theme.TriStripe
import com.liftoff.app.ui.theme.Wordmark

@Composable
fun LiftoffShell() {
    var nav by rememberSaveable(
        stateSaver = Saver(
            save = { it.encode() },
            restore = { ShellNav.decode(it) },
        ),
    ) { mutableStateOf(ShellNav()) }

    val shellScope = rememberCoroutineScope()

    BackHandler(enabled = nav.back() != null) {
        nav.back()?.let { nav = it }
    }

    // Switch nav-bar icon contrast: light icons over ink on tabs, dark on Mission Control cream.
    val view = LocalView.current
    SideEffect {
        (view.context as? android.app.Activity)?.window?.let {
            WindowCompat.getInsetsController(it, view).isAppearanceLightNavigationBars = nav.missionControlOpen
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Cream),
    ) {
        TriStripe(modifier = Modifier.statusBarsPadding())

        if (nav.missionControlOpen) {
            val store = (LocalContext.current.applicationContext as com.liftoff.app.LiftoffApplication).container.settingsStore
            MissionControlScreen(
                settingsStore = store,
                scope = shellScope,
                onBack = { nav = nav.back() ?: nav },
            )
        } else {
            Column(Modifier.weight(1f)) {
                // Top bar: wordmark + sliders button.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp, start = 20.dp, end = 20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Wordmark()

                    OutlinedButton(
                        onClick = { nav = nav.openMissionControl() },
                        modifier = Modifier.size(44.dp),
                        shape = ButtonShape,
                        border = BorderStroke(2.dp, Ink),
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Icon(
                            painter = LiftoffIcons.sliders(),
                            contentDescription = "Mission Control",
                            modifier = Modifier.size(22.dp),
                            tint = Ink,
                        )
                    }
                }

                // Content area.
                Box(Modifier.weight(1f)) {
                    when (nav.tab) {
                        Tab.Launchpad -> LaunchpadScreen()
                        Tab.Mission -> MissionScreen()
                        Tab.Landed -> LandedScreen()
                    }
                }

                // Bottom navigation bar.
                Row(
                    modifier = Modifier
                        .background(Ink)
                        .navigationBarsPadding()
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 14.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NavigationBarItem(
                        tab = Tab.Launchpad,
                        iconRes = R.drawable.ic_rocket,
                        label = "Launchpad",
                        selected = nav.tab == Tab.Launchpad,
                        onClick = { nav = nav.select(Tab.Launchpad) },
                    )
                    NavigationBarItem(
                        tab = Tab.Mission,
                        iconRes = R.drawable.ic_planet,
                        label = "Mission",
                        selected = nav.tab == Tab.Mission,
                        onClick = { nav = nav.select(Tab.Mission) },
                    )
                    NavigationBarItem(
                        tab = Tab.Landed,
                        iconRes = R.drawable.ic_flag,
                        label = "Landed",
                        selected = nav.tab == Tab.Landed,
                        onClick = { nav = nav.select(Tab.Landed) },
                    )
                }
            }
        }
    }
}

@Composable
private fun NavigationBarItem(
    tab: Tab,
    iconRes: Int,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .selectable(
                selected = selected,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = androidx.compose.ui.res.painterResource(iconRes),
            contentDescription = label,
            modifier = Modifier.size(24.dp),
            tint = if (selected) Mustard else Rule,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) Mustard else Rule,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun LiftoffShellPreview() {
    LiftoffTheme {
        LiftoffShell()
    }
}
