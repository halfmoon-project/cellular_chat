package com.cellularchat.app.identity

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.cellularchat.app.core.protocol.CapabilitySet

/**
 * This device's self-declared display name (PROTOCOL_V2.md §11 `deviceName`),
 * shown to the peer so an unnamed pair labels itself. Defaults to the
 * user-visible OS device name; the user can override it in the people list.
 */
object LocalDeviceName {
    private const val PREFS = "local_device_name"
    private const val KEY = "name"

    fun get(context: Context): String {
        val stored = prefs(context).getString(KEY, null)?.trim()
        return if (stored.isNullOrEmpty()) osName(context) else stored
    }

    // A blank value is stored as blank and falls back on read, so clearing the
    // field while typing does not snap back to the OS name mid-edit.
    fun set(context: Context, name: String) {
        prefs(context).edit()
            .putString(KEY, name.take(CapabilitySet.MAX_DEVICE_NAME_LENGTH))
            .apply()
    }

    private fun osName(context: Context): String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: Build.MODEL

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
