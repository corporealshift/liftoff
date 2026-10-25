package com.liftoff.app.domain

/**
 * Whether the owner has pointed the app at Mission Control: a host and a token, each non-empty once trimmed.
 * [com.liftoff.app.AppContainer] trims both before it builds a client or a connection check, and a daemon check
 * with a blank token is a setup step left unfinished rather than a request to try without one. The same rule is
 * what decides whether Confirm can ask for an outline and what the draft says while it waits, so a host typed
 * with only spaces in it is not configured in one place and configured in another.
 */
fun coachIsConfigured(daemonHost: String, daemonToken: String): Boolean =
    daemonHost.trim().isNotEmpty() && daemonToken.trim().isNotEmpty()
