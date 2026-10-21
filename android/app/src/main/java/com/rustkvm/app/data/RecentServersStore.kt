package com.rustkvm.app.data

import android.content.Context

class RecentServersStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(): List<String> =
        prefs.getString(KEY_SERVERS, null)
            ?.lineSequence()
            ?.filter(String::isNotBlank)
            ?.take(MAX_RECENT_SERVERS)
            ?.toList()
            .orEmpty()

    fun add(server: String): List<String> =
        recentServersAfterAdding(get(), server).also { servers ->
            prefs.edit().putString(KEY_SERVERS, servers.joinToString("\n")).apply()
        }

    private companion object {
        const val PREFS_NAME = "rust_kvm_recent_servers"
        const val KEY_SERVERS = "servers"
    }
}

internal const val MAX_RECENT_SERVERS = 8

internal fun recentServersAfterAdding(current: List<String>, server: String): List<String> =
    buildList {
        add(server)
        current.filterTo(this) { !it.equals(server, ignoreCase = true) }
    }.take(MAX_RECENT_SERVERS)
