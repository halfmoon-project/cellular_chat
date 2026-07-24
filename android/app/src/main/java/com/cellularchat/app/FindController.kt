package com.cellularchat.app

import android.annotation.TargetApi
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.aware.WifiAwareManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.cellularchat.app.background.FindForegroundService
import com.cellularchat.app.core.ProtocolException
import com.cellularchat.app.core.ReasonCodes
import com.cellularchat.app.core.cbor.CborBytes
import com.cellularchat.app.core.cbor.CborInt
import com.cellularchat.app.core.cbor.CborMap
import com.cellularchat.app.core.cbor.cborMapOf
import com.cellularchat.app.core.crypto.Derivations
import com.cellularchat.app.core.crypto.Discovery
import com.cellularchat.app.core.protocol.CapabilitySet
import com.cellularchat.app.core.protocol.CapabilityTranscript
import com.cellularchat.app.core.protocol.FindState
import com.cellularchat.app.core.protocol.SecureSession
import com.cellularchat.app.core.protocol.SessionEnvelope
import com.cellularchat.app.core.protocol.SessionMsgType
import com.cellularchat.app.identity.PairRecord
import com.cellularchat.app.ranging.AndroidOobController
import com.cellularchat.app.ranging.Measurement
import com.cellularchat.app.ranging.ProximityBand
import com.cellularchat.app.ranging.RangingCoordinator
import com.cellularchat.app.ranging.RawUwbController
import com.cellularchat.app.ranging.RssiTrend
import com.cellularchat.app.ranging.TrendConfidence
import com.cellularchat.app.transport.BleRoleSelector
import com.cellularchat.app.transport.CapabilityProvider
import com.cellularchat.app.transport.DuplicateConnectionResolver
import com.cellularchat.app.transport.PeerTransport
import com.cellularchat.app.transport.SecureSessionRunner
import com.cellularchat.app.transport.TransportCandidateFactory
import com.cellularchat.app.transport.TransportCoordinator
import com.cellularchat.app.transport.TransportUpgradeManager
import com.cellularchat.app.transport.aware.WifiAwareTransport
import com.cellularchat.app.transport.nearby.NearbyConnectionsTransport
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Process-scoped holder for the live Find session (IMPLEMENTATION_PLAN.md §5/§8).
 * It owns the [FindSessionCoordinator], transport arbitration, the Noise session
 * runner and the [RangingCoordinator]; the Activity only observes its state, so
 * UI lifetime is fully separated from session lifetime — the foreground service
 * keeps this alive.
 *
 * Radios cannot be exercised in the build environment, so a real peer will not
 * appear here; the session then simply stays `searching`, which is the honest
 * meaning of that state ("not yet in direct radio range").
 */
object FindController {
    fun interface Observer {
        fun onFindState(state: FindUiState)
    }

    private val observers = CopyOnWriteArrayList<Observer>()
    private val handler = Handler(Looper.getMainLooper())
    private val random = SecureRandom()

    @Volatile
    var state: FindUiState = FindUiState()
        private set

    private var coordinator: FindSessionCoordinator? = null
    private var transportCoordinator: TransportCoordinator? = null
    private var ranging: RangingCoordinator? = null
    // The runner that owns ranging/state I/O. During a transport upgrade the K
    // runner does NOT become active until both session_ready are exchanged (A.7).
    private var activeRunner: SecureSessionRunner? = null
    // A previous BLE transport retained authenticated + idle as the control
    // fallback after an upgrade (A.7). Never carries ranging while idle.
    private var bleControlRunner: SecureSessionRunner? = null
    private var activeTransportTag: String = "ble"
    private var record: PairRecord? = null
    private var capabilities: CapabilityProvider? = null
    private var deadlineMillis: Long = 0

    // Feature A/B state bound to the logical session.
    private var upgradeManager: TransportUpgradeManager? = null
    private var upgradeTimer: Runnable? = null
    private var boundPeerCaps: CapabilitySet? = null
    private var boundSid: ByteArray? = null

    private var appContext: Context? = null
    private var recovery: FindRecoveryLoop? = null
    private var retryRunnable: Runnable? = null
    // Noise-initiator static of the active authenticated connection; used to
    // deduplicate a second connection for the same pair (§10).
    private var activeInitiatorStatic: ByteArray? = null
    private var foregroundWatcher: ForegroundWatcher? = null

    fun addObserver(observer: Observer) {
        observers.add(observer)
        observer.onFindState(state)
    }

    fun removeObserver(observer: Observer) {
        observers.remove(observer)
    }

    fun isActive(): Boolean = coordinator != null

    @TargetApi(36)
    fun arm(
        context: Context,
        pair: PairRecord,
        capabilityProvider: CapabilityProvider,
        durationMillis: Long,
    ) {
        if (pair.revoked) return
        teardown()
        val app = context.applicationContext
        this.appContext = app
        this.record = pair
        this.capabilities = capabilityProvider
        this.deadlineMillis = System.currentTimeMillis() + durationMillis

        val fsc = FindSessionCoordinator { next -> publish(next) }
        coordinator = fsc
        ranging = buildRanging(app)
        recovery = FindRecoveryLoop(
            coordinator = fsc,
            teardownTransport = { teardownTransportKeepingSession() },
            beginSearch = { record?.let { pair -> appContext?.let { ctx -> beginSearch(ctx, pair) } } },
            scheduleRetry = { delay, action ->
                val runnable = Runnable { action() }
                retryRunnable = runnable
                handler.postDelayed(runnable, delay)
            },
            now = { System.currentTimeMillis() },
        ).also { it.armed(deadlineMillis) }
        registerForegroundWatcher(app)

        fsc.arm(deadlineMillis)
        FindForegroundService.start(app, pair.alias, deadlineMillis)
        beginSearch(app, pair)
    }

    fun stop(context: Context) {
        coordinator?.stop(ReasonCodes.USER_STOPPED)
        teardown()
        FindForegroundService.stop(context.applicationContext)
    }

    fun expire(context: Context) {
        coordinator?.expire()
        teardown()
    }

    private fun beginSearch(context: Context, pair: PairRecord) {
        coordinator?.onPeerFound() // enters p2pConnecting; a real link advances it further.
        val candidates = runCatching {
            TransportCandidateFactory.candidates(context, pair)
        }.getOrElse { emptyList() }
        if (candidates.isEmpty()) {
            recovery?.onArbitrationExhausted()
            return
        }
        val coord = TransportCoordinator(candidates)
        transportCoordinator = coord
        coord.arbitrate { result ->
            when (result) {
                is TransportCoordinator.Result.Won -> onTransportWon(pair, result)
                // No transport this pass: back off and re-enter arbitration (§10).
                TransportCoordinator.Result.Exhausted -> recovery?.onArbitrationExhausted()
            }
        }
    }

    private fun onTransportWon(pair: PairRecord, won: TransportCoordinator.Result.Won) {
        val localStatic = pair.localStaticPublic()
        val initiator = BleRoleSelector.localIsCentral(
            localStatic = localStatic,
            peerStatic = pair.peerStaticPublic,
            localIsIos = false,
            peerIsIos = false,
        )
        val newInitiatorStatic = if (initiator) localStatic else pair.peerStaticPublic

        // Duplicate-connection guard (§10): a second authenticated connection for
        // the already-active pair keeps the one whose Noise initiator has the
        // bytewise-smaller static key and closes the other with `duplicate`.
        val incumbent = activeInitiatorStatic
        if (activeRunner != null && incumbent != null) {
            if (DuplicateConnectionResolver.shouldKeep(incumbent, newInitiatorStatic)) {
                runCatching { won.transport.close() } // incumbent wins; drop the newcomer
                return
            }
            runCatching { activeRunner?.close() } // newcomer wins; drop the incumbent
            activeRunner = null
        }
        activeInitiatorStatic = newInitiatorStatic

        coordinator?.onTransportConnected()
        val session = if (initiator) {
            SecureSession.initiator(
                pairId = pair.pairId,
                transportTag = won.tag,
                localStaticPrivate = pair.localStaticPrivate,
                pinnedPeerStatic = pair.peerStaticPublic,
                pairRoot = pair.pairRoot,
                sid = ByteArray(16).also { random.nextBytes(it) },
            )
        } else {
            SecureSession.responder(
                pairId = pair.pairId,
                transportTag = won.tag,
                localStaticPrivate = pair.localStaticPrivate,
                pinnedPeerStatic = pair.peerStaticPublic,
                pairRoot = pair.pairRoot,
            )
        }
        val newRunner = SecureSessionRunner(
            transport = won.transport,
            session = session,
            isInitiator = initiator,
            sessionReadyBody = { sessionReadyBody() },
            events = sessionEvents(initiator, won.tag),
        )
        activeRunner = newRunner
        activeTransportTag = won.tag
        runCatching { newRunner.start() }
    }

    /** Events for the primary (arbitration-won) runner. [tag] gates ranging so a
     * retained, non-active runner never feeds/serves ranging (A.7). */
    private fun sessionEvents(initiator: Boolean, tag: String) = object : SecureSessionRunner.Events {
        override fun onAuthenticated(peerSessionReady: SessionEnvelope) {
            recovery?.onAuthenticated() // fresh link established; restart backoff.
            coordinator?.onAuthenticated()
            val peerCaps = runCatching {
                (peerSessionReady.body[1L] as? CborMap)?.let { CapabilitySet.fromCbor(it) }
            }.getOrNull() ?: return
            boundPeerCaps = peerCaps
            boundSid = peerSessionReady.sid
            val local = capabilities?.capabilities() ?: return
            ranging?.select(local, peerCaps)
            coordinator?.onRangingStarting()
            val peerUuid = UUID.nameUUIDFromBytes(record?.peerStaticPublic ?: ByteArray(0))
            runCatching { ranging?.start(peerUuid, oobInitiator = initiator) }
            // Create/own the upgrade manager on the handler thread only (see
            // onTransportControl): this authenticated callback runs off-main.
            handler.post { setupUpgrade(initiator, peerCaps) }
        }

        override fun onSessionMessage(envelope: SessionEnvelope) {
            if (tag != activeTransportTag) return // idle/retained runner: never feed ranging.
            routeActiveSessionMessage(envelope)
        }

        override fun onTransportControl(envelope: SessionEnvelope) {
            // Marshal onto the main thread: all TransportUpgradeManager state (the 5 s
            // evaluate() timer, the 10 s timeout, switchover) lives on the handler, so
            // this off-main binder callback must not touch it directly.
            handler.post {
                if (tag != activeTransportTag) return@post // control only on the working transport.
                try {
                    upgradeManager?.onControl(envelope) // a malformed upgrade throws -> §14 teardown.
                } catch (e: ProtocolException) {
                    runCatching { activeRunner?.close() }
                    handleRunnerClosed(tag, e.reason)
                }
            }
        }

        override fun onClosed(reason: Int) {
            handler.post { handleRunnerClosed(tag, reason) }
        }
    }

    /**
     * Feature B: capability-transcript drift on the active transport. A later
     * `session_ready`/`capabilities` whose decoded CapabilitySet differs from the
     * peer's first bound set is a `capabilityMismatch` disconnect (B.2.1); every
     * other message goes to ranging (which enforces B.2.2–B.2.4).
     */
    private fun routeActiveSessionMessage(envelope: SessionEnvelope) {
        when (envelope.msgType) {
            SessionMsgType.SESSION_READY -> {
                val caps = runCatching {
                    (envelope.body[1L] as? CborMap)?.let { CapabilitySet.fromCbor(it) }
                }.getOrNull()
                if (isCapabilityDrift(caps)) onCapabilityMismatchDetected()
                return
            }
            SessionMsgType.CAPABILITIES -> {
                val caps = runCatching { CapabilitySet.fromCbor(envelope.body) }.getOrNull()
                if (isCapabilityDrift(caps)) onCapabilityMismatchDetected()
                return
            }
        }
        ranging?.onSessionMessage(envelope.msgType, envelope.body)
    }

    private fun isCapabilityDrift(caps: CapabilitySet?): Boolean {
        val bound = boundPeerCaps ?: return false
        return caps != null && CapabilityTranscript.isReannouncementDrift(bound, caps)
    }

    /**
     * Feature B.3: a hard `capabilityMismatch` failure. Disconnect the active
     * transport, fail the logical session to `failed` (FATAL, never a retry), and
     * tear down every transport including a retained BLE control fallback.
     */
    private fun onCapabilityMismatchDetected(sendDisconnect: Boolean = true) {
        if (sendDisconnect) {
            runCatching {
                activeRunner?.sendMessage(
                    SessionMsgType.DISCONNECT,
                    cborMapOf(1L to CborInt(ReasonCodes.CAPABILITY_MISMATCH.toLong())),
                )
            }
        }
        coordinator?.fail(ReasonCodes.CAPABILITY_MISMATCH)
        stopUpgradeTimer()
        upgradeManager = null
        boundPeerCaps = null
        boundSid = null
        runCatching { transportCoordinator?.cancel() }
        runCatching { activeRunner?.close() }
        runCatching { bleControlRunner?.close() }
        runCatching { ranging?.stop() }
        transportCoordinator = null
        activeRunner = null
        bleControlRunner = null
        activeTransportTag = "ble"
        activeInitiatorStatic = null
    }

    private fun handleRunnerClosed(tag: String, reason: Int) {
        if (reason == ReasonCodes.CAPABILITY_MISMATCH) {
            onCapabilityMismatchDetected(sendDisconnect = false)
            return
        }
        if (tag != activeTransportTag) {
            if (tag == "ble") bleControlRunner = null // lost the idle control fallback.
            return
        }
        val ble = bleControlRunner
        if (ble != null && tag != "ble") {
            handleActiveTransportLost(reason)
        } else {
            recovery?.onLinkClosed(reason)
        }
    }

    // --- Feature A: transport upgrade wiring ---

    private fun setupUpgrade(initiator: Boolean, peerCaps: CapabilitySet) {
        if (upgradeManager != null) return
        val sid = boundSid ?: return
        upgradeManager = TransportUpgradeManager(
            sid = sid,
            isDriver = initiator,
            peerCaps = peerCaps,
            deps = upgradeDeps(),
        )
        startUpgradeTimer()
    }

    private fun upgradeDeps() = object : TransportUpgradeManager.Deps {
        override fun activeTag(): String = activeTransportTag
        override fun availableTargets(): Set<String> = availableUpgradeTargets()
        override fun sendControl(msgType: Long, body: CborMap) {
            activeRunner?.sendMessage(msgType, body)
        }

        override fun openTransport(
            tag: String,
            initiator: Boolean,
            onReady: (PeerTransport) -> Unit,
            onFailed: () -> Unit,
        ) = openUpgradeTransport(tag, initiator, onReady, onFailed)

        override fun buildRunner(
            tag: String,
            transport: PeerTransport,
            initiator: Boolean,
            sid: ByteArray,
            events: SecureSessionRunner.Events,
        ): SecureSessionRunner = buildUpgradeRunner(tag, transport, initiator, sid, events)

        override fun switchover(newRunner: SecureSessionRunner, newTag: String, attemptId: Long) =
            performSwitchover(newRunner, newTag)

        override fun schedule(delayMillis: Long, action: () -> Unit): TransportUpgradeManager.Cancel {
            val runnable = Runnable { action() }
            handler.postDelayed(runnable, delayMillis)
            return TransportUpgradeManager.Cancel { handler.removeCallbacks(runnable) }
        }

        override fun onActiveSessionMessage(envelope: SessionEnvelope) = routeActiveSessionMessage(envelope)
        // The K runner's onClosed fires on its transport's off-main callback thread;
        // the revert touches upgradeManager, so marshal it onto the handler.
        override fun onActiveTransportLost(reason: Int) { handler.post { handleActiveTransportLost(reason) } }
    }

    @TargetApi(36)
    private fun availableUpgradeTargets(): Set<String> {
        val ctx = appContext ?: return emptySet()
        val out = mutableSetOf<String>()
        val hasAware = ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE) &&
            (ctx.getSystemService(WifiAwareManager::class.java)?.isAvailable == true)
        if (hasAware) out.add("aware")
        val hasNearby = GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(ctx) == ConnectionResult.SUCCESS
        if (hasNearby) out.add("nearby")
        return out
    }

    private fun openUpgradeTransport(
        tag: String,
        initiator: Boolean,
        onReady: (PeerTransport) -> Unit,
        onFailed: () -> Unit,
    ) {
        val ctx = appContext ?: return onFailed()
        val pair = record ?: return onFailed()
        var built: PeerTransport? = null
        val ready: () -> Unit = { built?.let { t -> onReady(t) } }
        val ok = when (tag) {
            "aware" -> WifiAwareTransport(ctx, publisher = !initiator, onLinkReady = ready)
                .also { built = it }.start()
            "nearby" -> NearbyConnectionsTransport(
                ctx,
                advertise = !initiator,
                endpointName = upgradeEndpointName(pair),
                onLinkReady = ready,
            ).also { built = it }.start()
            else -> false
        }
        if (!ok) {
            runCatching { built?.close() }
            onFailed()
        }
    }

    private fun upgradeEndpointName(pair: PairRecord): String {
        val myDiscoveryKey = Derivations.discoveryKey(pair.pairRoot, pair.roleByte)
        val nowSeconds = System.currentTimeMillis() / 1000
        val token = Discovery.token(myDiscoveryKey, Discovery.epoch(nowSeconds), pair.roleByte)
        return token.joinToString("") { "%02x".format(it) }
    }

    private fun buildUpgradeRunner(
        tag: String,
        transport: PeerTransport,
        initiator: Boolean,
        sid: ByteArray,
        events: SecureSessionRunner.Events,
    ): SecureSessionRunner {
        val pair = record ?: error("no pair record for the upgrade transport")
        val session = if (initiator) {
            SecureSession.initiator(
                pairId = pair.pairId,
                transportTag = tag,
                localStaticPrivate = pair.localStaticPrivate,
                pinnedPeerStatic = pair.peerStaticPublic,
                pairRoot = pair.pairRoot,
                sid = sid,
            )
        } else {
            SecureSession.responderForUpgrade(
                pairId = pair.pairId,
                transportTag = tag,
                localStaticPrivate = pair.localStaticPrivate,
                pinnedPeerStatic = pair.peerStaticPublic,
                pairRoot = pair.pairRoot,
                sid = sid,
            )
        }
        return SecureSessionRunner(
            transport = transport,
            session = session,
            isInitiator = initiator,
            sessionReadyBody = { sessionReadyBody() },
            events = events,
        )
    }

    private fun performSwitchover(newRunner: SecureSessionRunner, newTag: String) {
        val previousRunner = activeRunner
        val previousTag = activeTransportTag
        activeRunner = newRunner
        activeTransportTag = newTag
        if (previousTag == "ble") {
            // Retain BLE authenticated + idle as the control fallback (A.7).
            bleControlRunner = previousRunner
        } else {
            runCatching {
                previousRunner?.sendMessage(
                    SessionMsgType.DISCONNECT,
                    cborMapOf(1L to CborInt(ReasonCodes.UPGRADED.toLong())),
                )
            }
            runCatching { previousRunner?.close() }
        }
        // Ranging attempt state is keyed by sid/attemptId and is NOT renegotiated;
        // the ranging output already reads activeRunner, so I/O is now repointed.
    }

    private fun handleActiveTransportLost(reason: Int) {
        val ble = bleControlRunner
        if (ble != null && activeTransportTag != "ble") {
            // Revert in place: no new handshake, no signalLost transition (A.7).
            activeRunner = ble
            activeTransportTag = "ble"
            bleControlRunner = null
            upgradeManager?.onRevertToBle()
        } else {
            recovery?.onLinkClosed(reason)
        }
    }

    private fun startUpgradeTimer() {
        stopUpgradeTimer()
        val runnable = object : Runnable {
            override fun run() {
                if (isUpgradeEvalState()) upgradeManager?.evaluate()
                handler.postDelayed(this, UPGRADE_EVAL_MS)
            }
        }
        upgradeTimer = runnable
        handler.postDelayed(runnable, UPGRADE_EVAL_MS)
    }

    private fun stopUpgradeTimer() {
        upgradeTimer?.let { handler.removeCallbacks(it) }
        upgradeTimer = null
    }

    private fun isUpgradeEvalState(): Boolean = when (coordinator?.uiState?.state) {
        FindState.CONNECTED,
        FindState.RANGING_STARTING,
        FindState.DIRECTION_AVAILABLE,
        FindState.DISTANCE_ONLY,
        FindState.PROXIMITY_ONLY,
        FindState.CONNECTED_ONLY -> true
        else -> false
    }

    private fun sessionReadyBody(): CborMap {
        val caps = capabilities?.capabilities()?.toCbor() ?: CborMap(emptyList())
        return cborMapOf(
            1L to caps,
            2L to CborInt(deadlineMillis / 1000),
            3L to CborInt(2),
        )
    }

    @TargetApi(36)
    private fun buildRanging(context: Context): RangingCoordinator {
        val output = object : RangingCoordinator.Output {
            override fun onDirection(measurement: Measurement) { handler.post { coordinator?.onDirection(measurement) } }
            override fun onDistance(measurement: Measurement) { handler.post { coordinator?.onDistance(measurement) } }
            override fun onProximity(band: ProximityBand, trend: RssiTrend, confidence: TrendConfidence) {
                handler.post { coordinator?.onProximity(band, trend, confidence) }
            }
            override fun onRangingUnavailable(detail: String) { handler.post { coordinator?.onRangingUnavailable() } }
            override fun onTechnology(technology: Int) { handler.post { coordinator?.onTechnology(technology) } }
            override fun onSignalLost() { handler.post { coordinator?.onSignalLost(ReasonCodes.TRANSPORT_LOST) } }
            override fun onCapabilityMismatch() { handler.post { onCapabilityMismatchDetected() } }
            override fun sendSessionMessage(msgType: Long, body: CborMap) {
                activeRunner?.sendMessage(msgType, body)
            }
            override fun scheduleRetry(delayMillis: Long, action: () -> Unit) {
                handler.postDelayed(action, delayMillis)
            }
        }
        val rawUwb = RawUwbController(context, rangingCallbacksHolder)
        val oob = AndroidOobController(context, rangingCallbacksHolder) { data ->
            activeRunner?.sendMessage(SessionMsgType.OOB_DATA, cborMapOf(2L to CborBytes(data)))
        }
        val coordinator = RangingCoordinator(output, rawUwb = rawUwb, androidOob = oob)
        // Route controller callbacks into the coordinator's fallback logic.
        rangingCallbacksHolder.delegate = coordinator.uwbCallbacks
        return coordinator
    }

    /** Indirection so both UWB controllers report into the current coordinator. */
    private val rangingCallbacksHolder = object : RawUwbController.Callbacks {
        @Volatile
        var delegate: RawUwbController.Callbacks? = null

        override fun onMeasurement(distanceMeters: Double?, azimuthDegrees: Double?, elevationDegrees: Double?) {
            delegate?.onMeasurement(distanceMeters, azimuthDegrees, elevationDegrees)
        }

        override fun onError(rangingErrorCode: Int, detail: String) {
            delegate?.onError(rangingErrorCode, detail)
        }

        override fun onStopped() {
            delegate?.onStopped()
        }

        override fun onStarted(technology: Int) {
            delegate?.onStarted(technology)
        }
    }

    /** Reports the app/service foreground state to gate background ranging (§8). */
    fun setForeground(foreground: Boolean) {
        ranging?.setForeground(foreground)
    }

    /** Tears down the dead transport + session but keeps the Find session and the
     * ranging coordinator alive so [FindRecoveryLoop] can re-arbitrate (§10). */
    private fun teardownTransportKeepingSession() {
        stopUpgradeTimer()
        runCatching { transportCoordinator?.cancel() }
        runCatching { activeRunner?.close() }
        runCatching { bleControlRunner?.close() }
        runCatching { ranging?.stop() }
        transportCoordinator = null
        activeRunner = null
        bleControlRunner = null
        activeTransportTag = "ble"
        upgradeManager = null
        boundPeerCaps = null
        boundSid = null
        activeInitiatorStatic = null
    }

    private fun registerForegroundWatcher(app: Context) {
        val application = app as? Application ?: return
        val watcher = ForegroundWatcher { foreground -> setForeground(foreground) }
        foregroundWatcher = watcher
        runCatching { application.registerActivityLifecycleCallbacks(watcher) }
    }

    private fun teardown() {
        retryRunnable?.let { handler.removeCallbacks(it) }
        retryRunnable = null
        stopUpgradeTimer()
        (appContext as? Application)?.let { app ->
            foregroundWatcher?.let { runCatching { app.unregisterActivityLifecycleCallbacks(it) } }
        }
        foregroundWatcher = null
        runCatching { transportCoordinator?.cancel() }
        runCatching { activeRunner?.close() }
        runCatching { bleControlRunner?.close() }
        runCatching { ranging?.close() }
        transportCoordinator = null
        activeRunner = null
        bleControlRunner = null
        activeTransportTag = "ble"
        upgradeManager = null
        boundPeerCaps = null
        boundSid = null
        ranging = null
        coordinator = null
        recovery = null
        record = null
        appContext = null
        activeInitiatorStatic = null
    }

    private fun publish(next: FindUiState) {
        state = next
        observers.forEach { it.onFindState(next) }
    }

    private const val UPGRADE_EVAL_MS = 5_000L
}

/**
 * Derives app foreground/background state from the started-activity count
 * (IMPLEMENTATION_PLAN.md §8). Registered while a Find session is active; a
 * foreground service alone does not lift platform background-ranging limits, so
 * this drives [FindController.setForeground] to gate ranging. Initialized as
 * foreground because [FindController.arm] runs from a visible Activity.
 */
private class ForegroundWatcher(
    private val onForeground: (Boolean) -> Unit,
) : Application.ActivityLifecycleCallbacks {
    private var started = 1

    override fun onActivityStarted(activity: Activity) {
        started += 1
        if (started == 1) onForeground(true)
    }

    override fun onActivityStopped(activity: Activity) {
        started = (started - 1).coerceAtLeast(0)
        if (started == 0) onForeground(false)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
