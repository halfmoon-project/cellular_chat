package com.cellularchat.app.identity

import android.content.Context
import java.util.UUID

/**
 * The local app-install identity (IMPLEMENTATION_PLAN.md §5). Used only for
 * local record ownership and as a RangingDevice UUID; it is never advertised as
 * a global radio identity and is never a security identity. Plain prefs are
 * acceptable here precisely because this value carries no security weight.
 */
class DeviceIdentity private constructor(val installId: UUID) {
    companion object {
        private const val PREFS = "device_identity"
        private const val KEY = "install_id"

        fun load(context: Context): DeviceIdentity {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val existing = prefs.getString(KEY, null)
                ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (existing != null) return DeviceIdentity(existing)
            val created = UUID.randomUUID()
            prefs.edit().putString(KEY, created.toString()).apply()
            return DeviceIdentity(created)
        }
    }
}
