package com.rustkvm.app.data

import android.content.Context
import com.rustkvm.client.hid.Shortcuts

class CustomShortcutsStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(): List<Shortcuts.Shortcut> = Shortcuts.decodeCustom(prefs.getString(KEY_SHORTCUTS, null))

    fun set(list: List<Shortcuts.Shortcut>): List<Shortcuts.Shortcut> {
        val normalized = Shortcuts.normalizeCustomList(list)
        prefs.edit().putString(KEY_SHORTCUTS, Shortcuts.encodeCustom(normalized)).apply()
        return normalized
    }

    private companion object {
        const val PREFS_NAME = "rust_kvm_shortcuts"
        const val KEY_SHORTCUTS = "custom"
    }
}
