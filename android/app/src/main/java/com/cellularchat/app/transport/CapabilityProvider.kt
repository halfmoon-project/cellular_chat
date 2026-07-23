package com.cellularchat.app.transport

import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.aware.WifiAwareManager
import android.os.Build
import com.cellularchat.app.core.protocol.CapabilitySet
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

/**
 * Supplies this device's authenticated [CapabilitySet] (PROTOCOL_V2.md §11).
 * Injectable so every fallback path can be unit-tested with a fake profile
 * (IMPLEMENTATION_PLAN.md Phase 2) rather than real hardware.
 */
fun interface CapabilityProvider {
    fun capabilities(): CapabilitySet
}

/** Runtime capability checks — never OS-version guesses (§11). */
class AndroidCapabilityProvider(
    private val context: Context,
    private val appVersion: String,
) : CapabilityProvider {
    override fun capabilities(): CapabilitySet {
        val pm = context.packageManager
        val hasAware = pm.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE) &&
            (context.getSystemService(WifiAwareManager::class.java)?.isAvailable == true)
        val hasBle = pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        val hasUwb = pm.hasSystemFeature(PackageManager.FEATURE_UWB)
        val hasGms = GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
        return CapabilitySet(
            os = CapabilitySet.OS_ANDROID,
            osVersion = Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString(),
            appVersion = appVersion,
            wifiAware = hasAware,
            nearbyConnections = hasGms,
            bleCentral = hasBle,
            blePeripheral = hasBle,
            uwbPresent = hasUwb,
            // Azimuth/elevation are re-read per ranging attempt from
            // RangingCapabilities; advertised conservatively here.
            uwbAzimuth = false,
            uwbElevation = false,
            appleInteropUwb = hasUwb,
            niEdm = false,
            wifiRtt = false,
            backgroundRanging = false,
        )
    }
}
