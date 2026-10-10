package com.liftoff.app.ui

enum class Tab { Launchpad, Mission, Landed }

/** Immutable navigation state for the shell. */
data class ShellNav(
    val tab: Tab = Tab.Launchpad,
    val missionControlOpen: Boolean = false,
    val inFlightSortieId: Long? = null,
) {
    fun select(tab: Tab): ShellNav = copy(tab = tab, missionControlOpen = false, inFlightSortieId = null)

    fun openMissionControl(): ShellNav = copy(missionControlOpen = true)

    fun openInFlight(id: Long): ShellNav = copy(inFlightSortieId = id)

    /** Returns the next shell state after back. `null` means leave the app. */
    fun back(): ShellNav? {
        if (missionControlOpen) return copy(missionControlOpen = false)
        if (inFlightSortieId != null) return copy(tab = Tab.Launchpad, inFlightSortieId = null)
        return if (tab != Tab.Launchpad) ShellNav() else null
    }

    fun encode(): String = buildString {
        append(tab.name)
        if (missionControlOpen) append("|1")
        inFlightSortieId?.let { append("|2:").append(it) }
    }

    companion object {
        fun decode(s: String): ShellNav = runCatching {
            val parts = s.split("|")
            val tab = Tab.valueOf(parts[0])
            var rest = parts.drop(1)
            val mc = rest.firstOrNull() == "1"
            if (mc) rest = rest.drop(1)
            val inFlight = rest.firstOrNull()?.let {
                require(it.startsWith("2:"))
                it.removePrefix("2:").toLong()
            }
            require(rest.size <= 1)
            ShellNav(tab, mc, inFlight)
        }.getOrElse { ShellNav() }
    }
}
