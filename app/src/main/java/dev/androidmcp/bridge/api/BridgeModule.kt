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

class ModuleException(val category: String, message: String) : Exception(message)

/** A module that runs on the phone itself (no Health Connect). The dispatcher authorizes the call before [run]. */
interface ActionModule : BridgeModule {
    /** Operations whose retries must not repeat the effect; they need an idempotency_key argument. */
    val idempotent: Set<String>
    suspend fun run(operation: String, args: kotlinx.serialization.json.JsonObject): kotlinx.serialization.json.JsonObject
}

/** Permissions of a module are the grants whose scope starts with "<id>.". */
fun BridgeModule.grants(): List<ClientPermission> = ClientPermission.entries.filter { it.scope.startsWith("$id.") }
