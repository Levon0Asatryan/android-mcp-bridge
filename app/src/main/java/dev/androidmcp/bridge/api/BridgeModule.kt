package dev.androidmcp.bridge.api

import dev.androidmcp.bridge.security.ClientPermission

/** A capability area the phone exposes to AI clients (health today; notifications, calendar and others later). */
interface BridgeModule {
    /** Scope prefix and tool prefix, e.g. "health". */
    val id: String
    val title: String
    /** Operations this module handles, keyed by operation name, with the grant each one needs (null: none). */
    val operations: Map<String, ClientPermission?>
}

/** Permissions of a module are the grants whose scope starts with "<id>.". */
fun BridgeModule.grants(): List<ClientPermission> = ClientPermission.entries.filter { it.scope.startsWith("$id.") }
