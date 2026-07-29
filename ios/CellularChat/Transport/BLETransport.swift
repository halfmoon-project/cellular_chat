import Foundation
import CoreBluetooth
import CellularChatCore

/// Fixed BLE GATT service and characteristic UUIDs (PROTOCOL_V2.md §9).
enum BLEIDs {
    static let service = CBUUID(string: "4A0C5000-9C6F-4B2E-8FD8-3B6A2E0D5C71")
    static let rendezvous = CBUUID(string: "4A0C5001-9C6F-4B2E-8FD8-3B6A2E0D5C71")
    static let inbox = CBUUID(string: "4A0C5002-9C6F-4B2E-8FD8-3B6A2E0D5C71")
    static let outbox = CBUUID(string: "4A0C5003-9C6F-4B2E-8FD8-3B6A2E0D5C71")
}

/// BLE GATT transport (PROTOCOL_V2.md §9). Central and peripheral roles; the
/// role is chosen by `RoleArbiter` (iOS is central cross-platform). The
/// peripheral side is gated behind a capability flag and only used for
/// same-platform pairs where this device owns the peripheral role.
final class BLETransport: NSObject, PeerTransport {
    enum Role { case central, peripheral }

    let kind: TransportKind = .ble
    let role: Role

    /// This device's current advertised rendezvous token (§7), 16 bytes.
    private let localToken: () -> [UInt8]
    /// Verifies a scanned peer token belongs to the selected pair (§7).
    private let acceptsPeerToken: ([UInt8]) -> Bool
    private let connectTimeout: TimeInterval

    var onRecord: (([UInt8]) -> Void)?
    var onClosed: ((ReasonCode) -> Void)?
    /// Live BLE RSSI samples for the §12 proximity fallback (central role only).
    /// The timestamp is stamped in the radio callback, not by the consumer: the
    /// trend regression is over seconds, and a queue hop would compress the arc.
    var onRSSI: ((Double, TimeInterval) -> Void)?

    private let queue = DispatchQueue(label: "com.cellularchat.ble")
    private var central: CBCentralManager?
    private var peripheralMgr: CBPeripheralManager?

    // Central state
    private var peripheral: CBPeripheral?
    private var inboxChar: CBCharacteristic?
    private var reassembler = FragmentReassembler()
    /// Candidates already rejected in this attempt (§7/§9 token mismatch, or a
    /// failed connect). An iOS peripheral cannot advertise service data, so a
    /// peer iPhone's token is only readable after connecting — which means we
    /// commit to a candidate before we can filter it. Without this set the
    /// rescan re-picks the same stranger every pass and a co-located third
    /// device running this app starves the pair.
    private var rejectedPeers: Set<UUID> = []

    // Peripheral state
    private var outboxChar: CBMutableCharacteristic?
    private var subscribedCentral: CBCentral?
    private var peerReassembler = FragmentReassembler()
    /// Fragments waiting on Core Bluetooth's transmit queue (§9). `updateValue`
    /// returns false when the queue is full and the fragment is NOT sent; a
    /// dropped one stalls the peer's reassembly into the 10-second §9 budget and
    /// surfaces to the user as "went out of range", so it must be retried.
    private var outboxQueue: [Data] = []

    private var connectContinuation: CheckedContinuation<Result<Void, TransportFailure>, Never>?
    private var didConnect = false
    // §7/§9 rendezvous filter: the central link is usable only once the peer
    // token is verified (advert service data OR the rendezvous read) AND both
    // characteristics are ready — never before, closing the callback race.
    private var tokenVerified = false
    private var notifyReady = false

    // §9/§12 monitors, armed once connected (central-side reads RSSI; both sides
    // poll the reassembly stall budget). Accessed only on `queue`.
    private var rssiTimer: DispatchSourceTimer?
    private var stallTimer: DispatchSourceTimer?
    private let rssiInterval: TimeInterval = 1.5

    init(role: Role,
         localToken: @escaping () -> [UInt8],
         acceptsPeerToken: @escaping ([UInt8]) -> Bool,
         connectTimeout: TimeInterval = 8) {
        self.role = role
        self.localToken = localToken
        self.acceptsPeerToken = acceptsPeerToken
        self.connectTimeout = connectTimeout
        super.init()
    }

    // BLE central is available on every iPhone unless the user denied it.
    var isAvailable: Bool { LocalCapabilities.bleCentralAvailable() }

    func connect() async -> Result<Void, TransportFailure> {
        await withCheckedContinuation { cont in
            queue.async { [weak self] in
                guard let self else { cont.resume(returning: .failure(.failed)); return }
                self.connectContinuation = cont
                switch self.role {
                case .central:
                    self.central = CBCentralManager(delegate: self, queue: self.queue)
                case .peripheral:
                    self.peripheralMgr = CBPeripheralManager(delegate: self, queue: self.queue)
                }
                self.queue.asyncAfter(deadline: .now() + self.connectTimeout) { [weak self] in
                    self?.finishConnect(.failure(.timeout))
                }
            }
        }
    }

    func send(record: [UInt8]) throws {
        queue.async { [weak self] in self?.write(record: record) }
    }

    func disconnect(reason: ReasonCode) {
        queue.async { [weak self] in
            guard let self else { return }
            self.stopMonitors()
            if let p = self.peripheral { self.central?.cancelPeripheralConnection(p) }
            self.peripheralMgr?.stopAdvertising()
            self.central?.stopScan()
            // Release the peripheral-side link BEFORE reporting: the central's
            // unsubscribe lands after we tear down, and `didUnsubscribeFrom`
            // would otherwise report `.transportLost` on top of this reason —
            // a spurious signalLost/retry right after a deliberate stop.
            self.subscribedCentral = nil
            self.outboxQueue.removeAll()
            self.onClosed?(reason)
        }
    }

    // MARK: helpers

    /// Map a Core Bluetooth manager state to a connect failure (§13). A denied
    /// authorization surfaces `.permissionRequired` so the UI can offer a settings
    /// path; a genuinely absent radio surfaces `.radioUnavailable`. `nil` means
    /// keep waiting (`.poweredOff`/`.resetting`/`.unknown` may still transition on).
    static func connectFailure(for state: CBManagerState) -> TransportFailure? {
        switch state {
        case .unauthorized: return .permissionRequired
        case .unsupported: return .radioUnavailable
        default: return nil
        }
    }

    private func finishConnect(_ result: Result<Void, TransportFailure>) {
        guard let cont = connectContinuation else { return }
        connectContinuation = nil
        if case .success = result {
            didConnect = true
            startStallMonitor()
            if role == .central { startRSSIMonitoring() }
        }
        cont.resume(returning: result)
    }

    /// The central link is usable only once we can write (inbox), receive
    /// (outbox notify), AND have verified the peer token (§7/§9). Gating on
    /// `tokenVerified` closes the notification/read callback race.
    private func maybeFinishConnect() {
        guard !didConnect, tokenVerified, inboxChar != nil, notifyReady else { return }
        finishConnect(.success(()))
    }

    // MARK: RSSI + reassembly-stall monitors (§9/§12)

    private func startRSSIMonitoring() {
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + rssiInterval, repeating: rssiInterval)
        timer.setEventHandler { [weak self] in self?.peripheral?.readRSSI() }
        rssiTimer = timer
        timer.resume()
    }

    /// Polls the §9 10-second reassembly budget so a FIRST-only stalled record
    /// tears the link down even when no further fragment ever arrives.
    private func startStallMonitor() {
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + 1, repeating: 1)
        timer.setEventHandler { [weak self] in self?.checkReassemblyStall() }
        stallTimer = timer
        timer.resume()
    }

    private func checkReassemblyStall() {
        do {
            try reassembler.checkStallTimeout()
            try peerReassembler.checkStallTimeout()
        } catch {
            stopMonitors()
            onClosed?(.protocolError)   // §9/§14: a stalled reassembly is fatal
        }
    }

    private func stopMonitors() {
        rssiTimer?.cancel(); rssiTimer = nil
        stallTimer?.cancel(); stallTimer = nil
    }

    /// Fragment one record into ATT-sized writes/notifications (§9).
    private func write(record: [UInt8]) {
        switch role {
        case .central:
            guard let peripheral, let inboxChar else { return }
            let mtu = peripheral.maximumWriteValueLength(for: .withResponse) + 3
            for frag in Fragmentation.fragment(record: record, mtu: mtu) {
                peripheral.writeValue(Data(frag), for: inboxChar, type: .withResponse)
            }
        case .peripheral:
            guard let subscribedCentral else { return }
            let mtu = subscribedCentral.maximumUpdateValueLength + 3
            outboxQueue.append(contentsOf: Fragmentation.fragment(record: record, mtu: mtu).map { Data($0) })
            drainOutbox()
        }
    }

    /// Sends `queue` through `send` in order, stopping at the first fragment
    /// `send` refuses, and returns what is still unsent. `updateValue`'s result
    /// is authoritative: false means the fragment did NOT go out, so it must
    /// stay queued rather than be dropped. Pure, so the rule is testable
    /// without Core Bluetooth.
    static func drain(_ queue: [Data], send: (Data) -> Bool) -> [Data] {
        var remaining = queue
        while let next = remaining.first, send(next) { remaining.removeFirst() }
        return remaining
    }

    /// Pushes queued fragments until Core Bluetooth's transmit queue fills; the
    /// remainder drains from `peripheralManagerIsReady(toUpdateSubscribers:)`.
    private func drainOutbox() {
        guard let outboxChar, let subscribedCentral, let mgr = peripheralMgr else { return }
        outboxQueue = Self.drain(outboxQueue) {
            mgr.updateValue($0, for: outboxChar, onSubscribedCentrals: [subscribedCentral])
        }
    }

    private func deliver(fragment: [UInt8], into reassembler: inout FragmentReassembler) {
        do {
            if let record = try reassembler.push(fragment) { onRecord?(record) }
        } catch {
            // §14: a fragmentation violation is fatal for the connection.
            onClosed?(.protocolError)
        }
    }
}

// MARK: - Central role

extension BLETransport: CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        if central.state == .poweredOn {
            central.scanForPeripherals(withServices: [BLEIDs.service],
                                       options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
        } else if let failure = Self.connectFailure(for: central.state) {
            finishConnect(.failure(failure))
        }
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        // Never re-pick a candidate this attempt already rejected, and never
        // abandon one that is still being evaluated.
        guard self.peripheral == nil, !rejectedPeers.contains(peripheral.identifier) else { return }
        // Prefer the token from service data; fall back to the rendezvous read
        // after connect (§9: iOS overflow-area advertising may hide service data).
        if let sd = advertisementData[CBAdvertisementDataServiceDataKey] as? [CBUUID: Data],
           let tokenData = sd[BLEIDs.service] {
            guard acceptsPeerToken(Array(tokenData)) else {
                rejectedPeers.insert(peripheral.identifier)
                return
            }
            tokenVerified = true   // §7/§9 filter satisfied from the advertisement
        }
        central.stopScan()
        self.peripheral = peripheral
        peripheral.delegate = self
        central.connect(peripheral, options: nil)
    }

    /// Drops the current candidate and resumes scanning for another. The
    /// `connectTimeout` still bounds the whole attempt, so this cannot spin
    /// forever; without it the first stranger advertising our service UUID ends
    /// the attempt outright.
    private func rejectCandidate(_ candidate: CBPeripheral) {
        rejectedPeers.insert(candidate.identifier)
        central?.cancelPeripheralConnection(candidate)
        peripheral = nil
        inboxChar = nil
        tokenVerified = false
        notifyReady = false
        reassembler = FragmentReassembler()
        central?.scanForPeripherals(withServices: [BLEIDs.service],
                                    options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        peripheral.discoverServices([BLEIDs.service])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        rejectCandidate(peripheral)
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        // Before the link is up this fires from our own `rejectCandidate`, which
        // is still searching — not a lost session.
        guard didConnect else { return }
        stopMonitors()
        onClosed?(.transportLost)
    }
}

extension BLETransport: CBPeripheralDelegate {
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let service = peripheral.services?.first(where: { $0.uuid == BLEIDs.service }) else {
            finishConnect(.failure(.failed)); return
        }
        peripheral.discoverCharacteristics([BLEIDs.rendezvous, BLEIDs.inbox, BLEIDs.outbox], for: service)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        for ch in service.characteristics ?? [] {
            switch ch.uuid {
            case BLEIDs.inbox: inboxChar = ch
            case BLEIDs.outbox: peripheral.setNotifyValue(true, for: ch)
            case BLEIDs.rendezvous: peripheral.readValue(for: ch)   // token verification fallback
            default: break
            }
        }
        maybeFinishConnect()
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard let data = characteristic.value else { return }
        switch characteristic.uuid {
        case BLEIDs.rendezvous:
            // Verify the peripheral's current token before treating it as our peer.
            guard acceptsPeerToken(Array(data)) else {
                rejectCandidate(peripheral)
                return
            }
            tokenVerified = true
            maybeFinishConnect()
        case BLEIDs.outbox:
            deliver(fragment: Array(data), into: &reassembler)
        default:
            break
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        // Never a success trigger on its own: the token must be verified first
        // (§7/§9), which `maybeFinishConnect` enforces.
        if characteristic.uuid == BLEIDs.outbox {
            notifyReady = true
            maybeFinishConnect()
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didReadRSSI RSSI: NSNumber, error: Error?) {
        guard error == nil else { return }
        onRSSI?(RSSI.doubleValue, Date().timeIntervalSince1970)
    }
}

// MARK: - Peripheral role

extension BLETransport: CBPeripheralManagerDelegate {
    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        if peripheral.state == .poweredOn {
            let rendezvous = CBMutableCharacteristic(type: BLEIDs.rendezvous, properties: [.read],
                                                     value: nil, permissions: [.readable])
            let inbox = CBMutableCharacteristic(type: BLEIDs.inbox, properties: [.write],
                                                value: nil, permissions: [.writeable])
            let outbox = CBMutableCharacteristic(type: BLEIDs.outbox, properties: [.notify],
                                                 value: nil, permissions: [])
            let service = CBMutableService(type: BLEIDs.service, primary: true)
            service.characteristics = [rendezvous, inbox, outbox]
            outboxChar = outbox
            peripheral.add(service)
            peripheral.startAdvertising([
                CBAdvertisementDataServiceUUIDsKey: [BLEIDs.service],
                CBAdvertisementDataLocalNameKey: "",
            ])
        } else if let failure = Self.connectFailure(for: peripheral.state) {
            finishConnect(.failure(failure))
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
        // Always return the current 16-byte token (§9) so a central that missed
        // the service-data advertisement can still verify before handshaking.
        if request.characteristic.uuid == BLEIDs.rendezvous {
            request.value = Data(localToken())
            peripheral.respond(to: request, withResult: .success)
        } else {
            peripheral.respond(to: request, withResult: .attributeNotFound)
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        for request in requests where request.characteristic.uuid == BLEIDs.inbox {
            if let value = request.value { deliver(fragment: Array(value), into: &peerReassembler) }
        }
        peripheral.respond(to: requests[0], withResult: .success)
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral,
                           didSubscribeTo characteristic: CBCharacteristic) {
        // Keep the first subscriber: `subscribedCentral` is a single slot, so a
        // second central subscribing would silently redirect every notification
        // away from the live peer. Noise IKpsk2 prevents impersonation, but
        // nothing else protects availability here.
        guard characteristic.uuid == BLEIDs.outbox, subscribedCentral == nil else { return }
        subscribedCentral = central
        finishConnect(.success(()))
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral,
                           didUnsubscribeFrom characteristic: CBCharacteristic) {
        guard characteristic.uuid == BLEIDs.outbox,
              subscribedCentral?.identifier == central.identifier else { return }
        subscribedCentral = nil
        outboxQueue.removeAll()
        stopMonitors()
        onClosed?(.transportLost)
    }

    func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) {
        drainOutbox()
    }
}
