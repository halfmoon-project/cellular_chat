package com.cellularchat.app.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.cellularchat.app.DirectionView
import com.cellularchat.app.FindController
import com.cellularchat.app.FindUiState
import com.cellularchat.app.R
import com.cellularchat.app.core.protocol.FindState
import com.cellularchat.app.identity.PairRecord
import com.cellularchat.app.identity.PairStore
import com.cellularchat.app.pairing.BlePairing
import com.cellularchat.app.pairing.InvitationFactory
import com.cellularchat.app.pairing.PairingCoordinator
import com.cellularchat.app.pairing.PairingHandle
import com.cellularchat.app.ranging.ProximityBand
import com.cellularchat.app.ranging.RssiTrend
import com.cellularchat.app.ranging.TrendConfidence
import com.cellularchat.app.transport.AndroidCapabilityProvider
import com.google.zxing.integration.android.IntentIntegrator

/**
 * Single-activity finder UI (classic Views, no Compose; IMPLEMENTATION_PLAN.md
 * §8). Screens: People (paired list, add, revoke), Pair (show QR / scan-paste),
 * Find (transport-independent state with reason, DirectionView only on a fresh
 * angle sample, proximity band otherwise). Session lifetime lives in
 * [FindController]/the foreground service; this Activity only observes it.
 */
class MainActivity : Activity() {

    private lateinit var store: PairStore
    private lateinit var container: LinearLayout
    private var pairingHandle: PairingHandle? = null

    private val findObserver = FindController.Observer { state -> runOnUiThread { onFindState(state) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = PairStore.default(filesDir)
        val scroll = ScrollView(this).apply {
            setBackgroundColor(color(R.color.hm_bg_subtle))
            isFillViewport = true
        }
        container = column().apply { setPadding(pad(4), pad(6), pad(4), pad(6)) }
        scroll.addView(container)
        setContentView(scroll)
        showPeople()
    }

    // Haptics are a foreground UI affordance; don't keep pulsing while the UI is
    // not visible. They resume on the next fresh measurement after returning.
    override fun onStop() {
        haptics?.stop()
        super.onStop()
    }

    override fun onDestroy() {
        haptics?.stop()
        FindController.removeObserver(findObserver)
        pairingHandle?.let { runCatching { it.close() } }
        super.onDestroy()
    }

    // --- People screen ---

    private fun showPeople() {
        haptics?.stop()
        FindController.removeObserver(findObserver)
        cancelPairing()
        container.removeAllViews()
        container.addView(title(getString(R.string.people_title)))

        val records = store.active()
        if (records.isEmpty()) {
            container.addView(muted(getString(R.string.people_empty)))
        } else {
            records.forEach { container.addView(personRow(it)) }
        }
        container.addView(spacer())
        container.addView(primaryButton(getString(R.string.people_add)) { showPair() })
    }

    private fun personRow(record: PairRecord): View {
        val row = card()
        row.addView(
            TextView(this).apply {
                text = record.alias
                setTextColor(color(R.color.hm_fg_default))
                textSize = 17f
                setTypeface(typeface, Typeface.BOLD)
            },
        )
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, pad(2), 0, 0)
        }
        actions.addView(
            primaryButton(getString(R.string.people_find)) { promptFindDuration(record) }
                .also { (it.layoutParams as LinearLayout.LayoutParams).rightMargin = pad(2) },
        )
        actions.addView(
            plainButton(getString(R.string.people_settings)) { showPairSettings(record) },
        )
        row.addView(actions)
        return row
    }

    private fun confirmRevoke(record: PairRecord) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.people_revoke_confirm))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.people_revoke) { _, _ ->
                store.revoke(record.pairId)
                showPeople()
            }
            .show()
    }

    // --- Pair settings screen ---

    private fun showPairSettings(record: PairRecord) {
        container.removeAllViews()
        container.addView(backTitle(getString(R.string.pair_settings_title, record.alias)) { showPeople() })

        container.addView(muted(getString(R.string.pair_settings_alias)))
        val aliasInput = editText(getString(R.string.pair_alias_hint)).apply { setText(record.alias) }
        container.addView(aliasInput)
        container.addView(
            primaryButton(getString(R.string.pair_settings_save)) {
                val alias = aliasText(aliasInput)
                if (alias.isNotEmpty()) {
                    store.upsert(record.copy(alias = alias))
                    toast(getString(R.string.pair_settings_saved))
                    showPeople()
                }
            },
        )
        container.addView(spacer())

        container.addView(muted(getString(R.string.pair_settings_fingerprint, PairFingerprint.display(record))))
        container.addView(muted(getString(R.string.pair_settings_created, formatDate(record.createdAt))))
        container.addView(spacer())

        container.addView(plainButton(getString(R.string.people_revoke)) { confirmRevoke(record) })
    }

    private fun formatDate(unixSeconds: Long): String =
        java.text.DateFormat.getDateInstance().format(java.util.Date(unixSeconds * 1000L))

    // --- Pair screen ---

    private fun showPair() {
        container.removeAllViews()
        container.addView(backTitle(getString(R.string.pair_title)) { showPeople() })
        container.addView(primaryButton(getString(R.string.pair_create)) { showInviter() })
        container.addView(spacer())
        container.addView(plainButton(getString(R.string.pair_join)) { showJoiner() })
    }

    private fun showInviter() {
        cancelPairing()
        container.removeAllViews()
        container.addView(backTitle(getString(R.string.pair_title)) { showPeople() })
        val aliasInput = editText(getString(R.string.pair_alias_hint))
        container.addView(aliasInput)

        val invitation = InvitationFactory.create()
        val text = InvitationFactory.text(invitation)
        val qr = QrCodes.bitmap(text, dp(240))
        if (qr != null) {
            container.addView(
                ImageView(this).apply {
                    setImageBitmap(qr)
                    contentDescription = getString(R.string.cd_qr)
                    layoutParams = LinearLayout.LayoutParams(dp(240), dp(240)).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                        topMargin = pad(4)
                    }
                },
            )
        }
        container.addView(muted(getString(R.string.pair_show_hint)))
        container.addView(plainButton(getString(R.string.pair_copy)) { copyToClipboard(text) })

        if (!ensurePairingPermissions()) return
        val events = pairingEvents()
        pairingHandle = runCatching {
            BlePairing.startInviter(this, store, invitation, aliasText(aliasInput), events)
        }.getOrNull()
    }

    private fun showJoiner() {
        cancelPairing()
        container.removeAllViews()
        container.addView(backTitle(getString(R.string.pair_title)) { showPeople() })
        val aliasInput = editText(getString(R.string.pair_alias_hint))
        container.addView(aliasInput)
        val pasteInput = editText(getString(R.string.pair_paste_hint))
        container.addView(pasteInput)
        container.addView(plainButton(getString(R.string.pair_scan)) { launchScan() })
        container.addView(
            primaryButton(getString(R.string.pair_start_join)) {
                startJoin(pasteInput.text.toString().trim(), aliasText(aliasInput))
            },
        )
    }

    private fun startJoin(invitationText: String, alias: String) {
        if (!invitationText.startsWith("CF2:")) {
            toast(getString(R.string.pair_paste_hint))
            return
        }
        if (!ensurePairingPermissions()) return
        pairingHandle = runCatching {
            BlePairing.startJoiner(this, store, invitationText, alias, pairingEvents())
        }.getOrNull()
    }

    private fun launchScan() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
            return
        }
        IntentIntegrator(this)
            .setOrientationLocked(true)
            .setPrompt(getString(R.string.pair_scan))
            .setBeepEnabled(false)
            .initiateScan()
    }

    private fun pairingEvents() = object : PairingCoordinator.Events {
        override fun onFingerprint(display: String) {
            runOnUiThread {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(getString(R.string.pair_fingerprint, display))
                    .setNegativeButton(android.R.string.cancel) { _, _ -> cancelPairing() }
                    .setPositiveButton(R.string.pair_confirm) { _, _ -> pairingHandle?.confirmFingerprint() }
                    .setCancelable(false)
                    .show()
            }
        }

        override fun onCommitted(record: PairRecord) {
            runOnUiThread {
                toast(getString(R.string.pair_committed, record.alias))
                showPeople()
            }
        }

        override fun onAborted(reason: Int, detail: String) {
            runOnUiThread {
                toast(getString(R.string.pair_aborted, detail))
                cancelPairing()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result?.contents != null) {
            (currentPasteInput())?.setText(result.contents)
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private fun currentPasteInput(): EditText? =
        container.findViewWithTag<EditText>("paste")

    private fun cancelPairing() {
        pairingHandle?.let {
            runCatching { it.cancel() }
            runCatching { it.close() }
        }
        pairingHandle = null
    }

    // --- Find screen ---

    private fun promptFindDuration(record: PairRecord) {
        val labels = FindDurations.minuteOptions.map { durationLabel(it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.find_duration_title)
            .setSingleChoiceItems(labels, FindDurations.defaultIndex) { dialog, which ->
                dialog.dismiss()
                startFind(record, FindDurations.millis(FindDurations.minuteOptions[which]))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun durationLabel(minutes: Int): String = when (minutes) {
        60 -> getString(R.string.find_duration_hour)
        120 -> getString(R.string.find_duration_two_hours)
        else -> getString(R.string.find_duration_minutes, minutes)
    }

    private fun startFind(record: PairRecord, durationMillis: Long) {
        if (!ensureFindPermissions()) {
            pendingFind = record
            pendingFindDuration = durationMillis
            return
        }
        showFind(record)
        FindController.arm(this, record, AndroidCapabilityProvider(this, "2.0.0"), durationMillis)
    }

    private lateinit var findStateLabel: TextView
    private lateinit var findReasonLabel: TextView
    private lateinit var findDirection: DirectionView
    private lateinit var findMeasurement: TextView
    private lateinit var findTrendLabel: TextView
    private lateinit var findTechLabel: TextView
    private var haptics: FindHaptics? = null

    private fun showFind(record: PairRecord) {
        container.removeAllViews()
        container.addView(backTitle(getString(R.string.find_title, record.alias)) { stopFindAndBack() })

        findStateLabel = title(getString(R.string.state_arming)).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        container.addView(findStateLabel)
        findReasonLabel = muted("")
        container.addView(findReasonLabel)

        findDirection = DirectionView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(220), dp(220)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = pad(4)
            }
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        container.addView(findDirection)

        findMeasurement = title("—")
        container.addView(findMeasurement)

        // Advisory RSSI trend (Feature C): plain text, never an arrow.
        findTrendLabel = muted("").apply { visibility = View.GONE }
        container.addView(findTrendLabel)

        findTechLabel = muted("").apply { visibility = View.GONE }
        container.addView(findTechLabel)

        container.addView(muted(getString(R.string.find_searching_note)))
        container.addView(muted(getString(R.string.find_honesty_note)))
        container.addView(spacer())
        container.addView(primaryButton(getString(R.string.find_stop)) { stopFindAndBack() })

        haptics?.stop()
        haptics = FindHaptics(this)
        FindController.addObserver(findObserver)
    }

    private fun stopFindAndBack() {
        haptics?.stop()
        FindController.stop(this)
        showPeople()
    }

    private fun onFindState(state: FindUiState) {
        if (!::findStateLabel.isInitialized) return
        findStateLabel.text = stateLabel(state.state)
        val measurement = state.measurement
        if (measurement?.azimuthDegrees != null) {
            findDirection.visibility = View.VISIBLE
            findDirection.setAzimuth(measurement.azimuthDegrees)
            findDirection.contentDescription =
                FindAccessibility.directionDescription(measurement.azimuthDegrees, measurement.distanceMeters)
        } else {
            findDirection.visibility = View.GONE
            findDirection.setAzimuth(null)
            findDirection.contentDescription = null
        }
        findMeasurement.text = when {
            measurement?.distanceMeters != null -> "%.2f m".format(measurement.distanceMeters)
            state.proximity != null -> proximityLabel(state.proximity)
            else -> "—"
        }
        // Advisory trend only on the RSSI proximity path and only at high
        // confidence; UWB measurements carry no trend, low confidence shows none.
        val trend = if (state.proximity != null && state.trendConfidence == TrendConfidence.HIGH) {
            trendLabel(state.trend)
        } else {
            null
        }
        if (trend != null) {
            findTrendLabel.visibility = View.VISIBLE
            findTrendLabel.text = trend
        } else {
            findTrendLabel.visibility = View.GONE
        }
        val tech = state.rangingTechnology?.let { techLabel(it) }
        if (tech != null) {
            findTechLabel.visibility = View.VISIBLE
            findTechLabel.text = getString(R.string.find_ranging_tech, tech)
        } else {
            findTechLabel.visibility = View.GONE
        }
        findReasonLabel.text = if (state.state == FindState.SEARCHING) {
            getString(R.string.find_searching_note)
        } else {
            ""
        }
        val interval = if (isMeasuring(state.state)) {
            FindHaptics.intervalMillis(measurement?.distanceMeters, state.proximity)
        } else {
            null
        }
        haptics?.update(interval)
    }

    private fun techLabel(technology: Int): String? = RangingTechnologyLabel.of(technology)?.let {
        getString(
            when (it) {
                RangingTechnologyLabel.Tech.UWB -> R.string.tech_uwb
                RangingTechnologyLabel.Tech.BLE_CS -> R.string.tech_ble_cs
                RangingTechnologyLabel.Tech.WIFI_NAN_RTT -> R.string.tech_wifi_rtt
                RangingTechnologyLabel.Tech.BLE_RSSI -> R.string.tech_ble_rssi
            },
        )
    }

    private fun isMeasuring(state: FindState): Boolean =
        state == FindState.DIRECTION_AVAILABLE ||
            state == FindState.DISTANCE_ONLY ||
            state == FindState.PROXIMITY_ONLY

    private fun stateLabel(state: FindState): String = getString(
        when (state) {
            FindState.IDLE -> R.string.state_idle
            FindState.ARMING -> R.string.state_arming
            FindState.SEARCHING -> R.string.state_searching
            FindState.P2P_CONNECTING -> R.string.state_p2p
            FindState.AUTHENTICATING -> R.string.state_authenticating
            FindState.CONNECTED -> R.string.state_connected
            FindState.RANGING_STARTING -> R.string.state_ranging
            FindState.DIRECTION_AVAILABLE -> R.string.state_direction
            FindState.DISTANCE_ONLY -> R.string.state_distance
            FindState.PROXIMITY_ONLY -> R.string.state_proximity
            FindState.CONNECTED_ONLY -> R.string.state_connected_only
            FindState.SIGNAL_LOST -> R.string.state_signal_lost
            FindState.RETRY_WAIT -> R.string.state_retry
            FindState.STOPPED -> R.string.state_stopped
            FindState.EXPIRED -> R.string.state_expired
            FindState.FAILED -> R.string.state_failed
        },
    )

    private fun proximityLabel(band: ProximityBand): String = getString(
        when (band) {
            ProximityBand.VERY_NEAR -> R.string.band_very_near
            ProximityBand.NEAR -> R.string.band_near
            ProximityBand.FAR -> R.string.band_far
            ProximityBand.UNKNOWN -> R.string.band_unknown
        },
    )

    private fun trendLabel(trend: RssiTrend): String = getString(
        when (trend) {
            RssiTrend.APPROACHING -> R.string.trend_approaching
            RssiTrend.RECEDING -> R.string.trend_receding
            RssiTrend.STEADY -> R.string.trend_steady
        },
    )

    // --- Permissions ---

    private var pendingFind: PairRecord? = null
    private var pendingFindDuration: Long = FindDurations.millis(FindDurations.DEFAULT_MINUTES)

    private fun ensurePairingPermissions(): Boolean =
        requestMissing(
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ),
            REQ_PAIR,
        )

    private fun ensureFindPermissions(): Boolean =
        requestMissing(
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.RANGING,
            ),
            REQ_FIND,
        )

    private fun requestMissing(permissions: Array<String>, requestCode: Int): Boolean {
        val missing = permissions.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) return true
        requestPermissions(missing.toTypedArray(), requestCode)
        return false
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val denied = grantResults.isEmpty() || grantResults.any { it != PackageManager.PERMISSION_GRANTED }
        when (requestCode) {
            REQ_FIND -> {
                val record = pendingFind
                pendingFind = null
                if (!denied && record != null) startFind(record, pendingFindDuration) else if (denied) offerSettings()
            }
            REQ_CAMERA -> if (!denied) launchScan() else offerSettings()
            REQ_PAIR -> if (denied) offerSettings()
        }
    }

    private fun offerSettings() {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.find_permissions))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.find_open_settings) { _, _ ->
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.fromParts("package", packageName, null)),
                )
            }
            .show()
    }

    // --- View helpers ---

    private fun copyToClipboard(text: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
            ?.setPrimaryClip(ClipData.newPlainText("invitation", text))
        toast(getString(R.string.pair_copy))
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(color(R.color.hm_fg_default))
        textSize = 22f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, pad(2), 0, pad(2))
    }

    private fun backTitle(text: String, onBack: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(plainButton("←") { onBack() }.apply { contentDescription = getString(R.string.cd_back) })
        row.addView(title(text))
        return row
    }

    private fun muted(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(color(R.color.hm_fg_muted))
        textSize = 13f
        setPadding(0, pad(1), 0, pad(2))
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(color(R.color.hm_bg_default))
        setPadding(pad(4), pad(4), pad(4), pad(4))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = pad(3) }
    }

    private fun editText(hint: String) = EditText(this).apply {
        this.hint = hint
        setTextColor(color(R.color.hm_fg_default))
        setHintTextColor(color(R.color.hm_fg_subtle))
        background = rounded(color(R.color.hm_bg_default))
        setPadding(pad(3), pad(3), pad(3), pad(3))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = pad(2); bottomMargin = pad(2) }
        if (hint.startsWith("CF2")) tag = "paste"
    }

    private fun primaryButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setTextColor(color(R.color.hm_action_primary_fg))
        background = rounded(color(R.color.hm_action_primary_bg))
        isAllCaps = false
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = pad(2) }
    }

    private fun plainButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setTextColor(color(R.color.hm_fg_default))
        background = rounded(color(R.color.hm_bg_muted))
        isAllCaps = false
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = pad(2) }
    }

    private fun spacer() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, pad(3))
    }

    private fun aliasText(input: EditText): String = input.text.toString().trim().take(40)

    private fun rounded(fill: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = resources.getDimension(R.dimen.hm_radius_lg)
    }

    private fun color(id: Int) = getColor(id)
    private fun pad(step: Int) = dp(step * 4)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_FIND = 201
        private const val REQ_PAIR = 202
        private const val REQ_CAMERA = 203
    }
}
