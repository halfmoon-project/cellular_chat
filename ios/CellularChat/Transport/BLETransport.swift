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

    private let queue = DispatchQueue(label: "com.cellularchat.ble")
    private var central: CBCentralManager?
    private var peripheralMgr: CBPeripheralManager?

    // Central state
    private var peripheral: CBPeripheral?
    private var inboxChar: CBCharacteristic?
    private var reassembler = FragmentReassembler()

    // Peripheral state
    private var outboxChar: CBMutableCharacteristic?
    private var subscribedCentral: CBCentral?
    private var peerReassembler = FragmentReassembler()

    private var connectContinuation: CheckedContinuation<Result<Void, TransportFailure>, Never>?
    private var didConnect = false

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
            if let p = self.peripheral { self.central?.cancelPeripheralConnection(p) }
            self.peripheralMgr?.stopAdvertising()
            self.central?.stopScan()
            self.onClosed?(reason)
        }
    }

    // MARK: helpers

    private func finishConnect(_ result: Result<Void, TransportFailure>) {
        guard let cont = connectContinuation else { return }
        connectContinuation = nil
        if case .success = result { didConnect = true }
        cont.resume(returning: result)
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
            guard let outboxChar, let subscribedCentral, let mgr = peripheralMgr else { return }
            let mtu = subscribedCentral.maximumUpdateValueLength + 3
            for frag in Fragmentation.fragment(record: record, mtu: mtu) {
                mgr.updateValue(Data(frag), for: outboxChar, onSubscribedCentrals: [subscribedCentral])
            }
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
        switch central.state {
        case .poweredOn:
            central.scanForPeripherals(withServices: [BLEIDs.service],
                                       options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
        case .unauthorized, .unsupported:
            finishConnect(.failure(.radioUnavailable))
        default:
            break
        }
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        // Prefer the token from service data; fall back to the rendezvous read
        // after connect (§9: iOS overflow-area advertising may hide service data).
        if let sd = advertisementData[CBAdvertisementDataServiceDataKey] as? [CBUUID: Data],
           let tokenData = sd[BLEIDs.service] {
            guard acceptsPeerToken(Array(tokenData)) else { return }
        }
        central.stopScan()
        self.peripheral = peripheral
        peripheral.delegate = self
        central.connect(peripheral, options: nil)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        peripheral.discoverServices([BLEIDs.service])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        finishConnect(.failure(.failed))
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
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
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard let data = characteristic.value else { return }
        switch characteristic.uuid {
        case BLEIDs.rendezvous:
            // Verify the peripheral's current token before treating it as our peer.
            guard acceptsPeerToken(Array(data)) else {
                central?.cancelPeripheralConnection(peripheral)
                finishConnect(.failure(.failed))
                return
            }
            if inboxChar != nil { finishConnect(.success(())) }
        case BLEIDs.outbox:
            deliver(fragment: Array(data), into: &reassembler)
        default:
            break
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        if characteristic.uuid == BLEIDs.outbox, inboxChar != nil, !didConnect {
            // Ready once we can both write and receive; token check may already have passed.
            finishConnect(.success(()))
        }
    }
}

// MARK: - Peripheral role

extension BLETransport: CBPeripheralManagerDelegate {
    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        switch peripheral.state {
        case .poweredOn:
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
        case .unauthorized, .unsupported:
            finishConnect(.failure(.radioUnavailable))
        default:
            break
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
        subscribedCentral = central
        finishConnect(.success(()))
    }
}
