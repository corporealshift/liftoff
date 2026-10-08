package com.liftoff.app.ui.theme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.liftoff.app.R

object LiftoffIcons {
    @Composable fun rocket() = painterResource(R.drawable.ic_rocket)
    @Composable fun planet() = painterResource(R.drawable.ic_planet)
    @Composable fun flag() = painterResource(R.drawable.ic_flag)
    @Composable fun sliders() = painterResource(R.drawable.ic_sliders)
    @Composable fun check() = painterResource(R.drawable.ic_check)
}

@Preview
@Composable
private fun IconsPreview() {
    if (LocalInspectionMode.current) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(LiftoffIcons.rocket(), contentDescription = "rocket", modifier = Modifier.width(24.dp).height(24.dp))
            Icon(LiftoffIcons.planet(), contentDescription = "planet", modifier = Modifier.width(24.dp).height(24.dp))
            Icon(LiftoffIcons.flag(), contentDescription = "flag", modifier = Modifier.width(24.dp).height(24.dp))
            Icon(LiftoffIcons.sliders(), contentDescription = "sliders", modifier = Modifier.width(24.dp).height(24.dp))
            Icon(LiftoffIcons.check(), contentDescription = "check", modifier = Modifier.width(24.dp).height(24.dp))
        }
    }
}
