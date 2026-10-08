package com.liftoff.app.ui

enum class Tab { Launchpad, Mission, Landed }

/** Immutable navigation state for the shell. */
data class ShellNav(
    val tab: Tab = Tab.Launchpad,
    val missionControlOpen: Boolean = false,
) {
    fun select(tab: Tab): ShellNav = copy(tab = tab, missionControlOpen = false)

    fun openMissionControl(): ShellNav = copy(missionControlOpen = true)

    /** Returns the next shell state after back. `null` means leave the app. */
    fun back(): ShellNav? {
        if (missionControlOpen) return copy(missionControlOpen = false)
        return if (tab != Tab.Launchpad) ShellNav() else null
    }

    fun encode(): String = buildString {
        append(tab.name)
        if (missionControlOpen) append("|1")
    }

    companion object {
        fun decode(s: String): ShellNav = runCatching {
            val parts = s.split("|")
            if (parts.size !in 1..2) throw IllegalArgumentException("bad format")
            val tab = Tab.valueOf(parts[0])
            if (parts.size == 2 && parts[1] != "1") throw IllegalArgumentException("bad mc flag")
            ShellNav(tab, parts.getOrElse(1) { "" } == "1")
        }.getOrElse { ShellNav() }
    }
}
