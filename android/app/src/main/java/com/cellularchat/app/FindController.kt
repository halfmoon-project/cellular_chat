package com.cellularchat.app

import android.annotation.TargetApi
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.cellularchat.app.background.FindForegroundService
import com.cellularchat.app.core.ReasonCodes
import com.cellularchat.app.core.cbor.CborInt
import com.cellularchat.app.core.cbor.CborMap
import com.cellularchat.app.core.cbor.cborMapOf
import com.cellularchat.app.core.crypto.X25519
import com.cellularchat.app.core.protocol.CapabilitySet
import com.cellularchat.app.core.protocol.SecureSession
import com.cellularchat.app.core.protocol.SessionEnvelope
import com.cellularchat.app.identity.PairRecord
import com.cellularchat.app.ranging.AndroidOobController
import com.cellularchat.app.ranging.Measurement
import com.cellularchat.app.ranging.ProximityBand
import com.cellularchat.app.ranging.RangingCoordinator
import com.cellularchat.app.ranging.RawUwbController
import com.cellularchat.app.transport.BleRoleSelector
import com.cellularchat.app.transport.CapabilityProvider
import com.cellularchat.app.transport.PeerTransport
import com.cellularchat.app.transport.SecureSessionRunner
import com.cellularchat.app.transport.TransportCandidateFactory
import com.cellularchat.app.transport.TransportCoordinator
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
    private var runner: SecureSessionRunner? = null
    private var record: PairRecord? = null
    private var capabilities: CapabilityProvider? = null
    private var deadlineMillis: Long = 0

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
        this.record = pair
        this.capabilities = capabilityProvider
        this.deadlineMillis = System.currentTimeMillis() + durationMillis

        val fsc = FindSessionCoordinator { next -> publish(next) }
        coordinator = fsc
        ranging = buildRanging(app)

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
        if (candidates.isEmpty()) return
        val coord = TransportCoordinator(candidates)
        transportCoordinator = coord
        coord.arbitrate { result ->
            when (result) {
                is TransportCoordinator.Result.Won -> onTransportWon(pair, result)
                TransportCoordinator.Result.Exhausted -> Unit // stay searching; user/service controls retry.
            }
        }
    }

    private fun onTransportWon(pair: PairRecord, won: TransportCoordinator.Result.Won) {
        coordinator?.onTransportConnected()
        val localStatic = pair.localStaticPublic()
        val initiator = BleRoleSelector.localIsCentral(
            localStatic = localStatic,
            peerStatic = pair.peerStaticPublic,
            localIsIos = false,
            peerIsIos = false,
        )
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
            events = sessionEvents(initiator),
        )
        runner = newRunner
        runCatching { newRunner.start() }
    }

    private fun sessionEvents(initiator: Boolean) = object : SecureSessionRunner.Events {
        override fun onAuthenticated(peerSessionReady: SessionEnvelope) {
            coordinator?.onAuthenticated()
            val peerCaps = runCatching {
                (peerSessionReady.body[1L] as? CborMap)?.let { CapabilitySet.fromCbor(it) }
            }.getOrNull() ?: return
            val local = capabilities?.capabilities() ?: return
            ranging?.select(local, peerCaps)
            coordinator?.onRangingStarting()
            val peerUuid = UUID.nameUUIDFromBytes(record?.peerStaticPublic ?: ByteArray(0))
            runCatching { ranging?.start(peerUuid, oobInitiator = initiator) }
        }

        override fun onSessionMessage(envelope: SessionEnvelope) {
            ranging?.onSessionMessage(envelope.msgType, envelope.body)
        }

        override fun onClosed(reason: Int) {
            handler.post {
                coordinator?.onSignalLost(reason)
                coordinator?.onRetryWait()
            }
        }
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
            override fun onProximity(band: ProximityBand) { handler.post { coordinator?.onProximity(band) } }
            override fun onRangingUnavailable(detail: String) { handler.post { coordinator?.onRangingUnavailable() } }
            override fun onSignalLost() { handler.post { coordinator?.onSignalLost(ReasonCodes.TRANSPORT_LOST) } }
            override fun sendSessionMessage(msgType: Long, body: CborMap) {
                runner?.sendMessage(msgType, body)
            }
            override fun scheduleRetry(delayMillis: Long, action: () -> Unit) {
                handler.postDelayed(action, delayMillis)
            }
        }
        val rawUwb = RawUwbController(context, rangingCallbacksHolder)
        val oob = AndroidOobController(context, rangingCallbacksHolder) { data ->
            runner?.sendMessage(com.cellularchat.app.core.protocol.SessionMsgType.OOB_DATA, cborMapOf(2L to com.cellularchat.app.core.cbor.CborBytes(data)))
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
    }

    private fun teardown() {
        runCatching { transportCoordinator?.cancel() }
        runCatching { runner?.close() }
        runCatching { ranging?.close() }
        transportCoordinator = null
        runner = null
        ranging = null
        coordinator = null
        record = null
    }

    private fun publish(next: FindUiState) {
        state = next
        observers.forEach { it.onFindState(next) }
    }
}
