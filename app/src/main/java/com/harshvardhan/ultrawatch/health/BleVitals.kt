package com.harshvardhan.ultrawatch.health

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.harshvardhan.ultrawatch.core.GattParsers
import com.harshvardhan.ultrawatch.data.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

/**
 * Connects to any certified Bluetooth health device using the standard Bluetooth SIG profiles:
 * Heart Rate (chest straps), Blood Pressure (cuffs), Pulse Oximeter, Weight Scale and Body Composition.
 * No brand SDK needed. Readings go to the vitals log; nothing is ever estimated or invented here.
 */
@SuppressLint("MissingPermission") // permissions are requested by the Vitals screen before use
object BleVitals {
    enum class Kind(val label: String, val service: UUID, val chars: List<UUID>) {
        HEART_RATE("Heart-rate strap", uuid(0x180D), listOf(uuid(0x2A37))),
        BLOOD_PRESSURE("Blood-pressure cuff", uuid(0x1810), listOf(uuid(0x2A35))),
        PULSE_OXIMETER("Pulse oximeter", uuid(0x1822), listOf(uuid(0x2A5F), uuid(0x2A5E))),
        WEIGHT("Weight scale", uuid(0x181D), listOf(uuid(0x2A9D))),
        BODY_COMPOSITION("Body-composition scale", uuid(0x181B), listOf(uuid(0x2A9C))),
    }

    data class Found(val address: String, val name: String, val kind: Kind, val rssi: Int)

    val found = MutableStateFlow<List<Found>>(emptyList())
    val status = MutableStateFlow("Idle")
    val liveHr = MutableStateFlow<Int?>(null)
    val rrIntervals = MutableStateFlow<List<Double>>(emptyList())
    val lastSpo2 = MutableStateFlow<Pair<Int, Int>?>(null) // spo2, pulse
    val overnightSpo2 = ArrayList<Pair<Long, Int>>()

    private val CCCD = uuid(0x2902)
    private var gatt: BluetoothGatt? = null
    private var scanCb: ScanCallback? = null

    private fun uuid(short: Int): UUID = UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", short))

    fun scan(ctx: Context) {
        val scanner = ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner ?: run { status.value = "Bluetooth is off"; return }
        stopScan(ctx)
        found.value = emptyList()
        val filters = Kind.values().map { ScanFilter.Builder().setServiceUuid(ParcelUuid(it.service)).build() }
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                val uuids = r.scanRecord?.serviceUuids.orEmpty().map { it.uuid }
                val kind = Kind.values().firstOrNull { it.service in uuids } ?: return
                val f = Found(r.device.address, r.scanRecord?.deviceName ?: runCatching { r.device.name }.getOrNull() ?: kind.label, kind, r.rssi)
                found.value = (found.value.filter { it.address != f.address } + f).sortedByDescending { it.rssi }
            }
            override fun onScanFailed(errorCode: Int) { status.value = "Scan failed ($errorCode)" }
        }
        scanCb = cb
        scanner.startScan(filters, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        status.value = "Searching... put the device in pairing mode"
    }

    fun stopScan(ctx: Context) {
        scanCb?.let { ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(it) }
        scanCb = null
    }

    fun connect(ctx: Context, f: Found) {
        stopScan(ctx)
        disconnect()
        val dev: BluetoothDevice = ctx.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(f.address)
        status.value = "Connecting to ${f.name}..."
        rrIntervals.value = emptyList()
        gatt = dev.connectGatt(ctx, false, object : BluetoothGattCallback() {
            private val pending = ArrayDeque<BluetoothGattCharacteristic>()

            override fun onConnectionStateChange(g: BluetoothGatt, s: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) { status.value = "Connected to ${f.name}"; g.discoverServices() }
                else { status.value = "Disconnected"; liveHr.value = null; g.close() }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, s: Int) {
                val svc = g.getService(f.kind.service) ?: run { status.value = "Device doesn't offer ${f.kind.label}"; return }
                f.kind.chars.mapNotNull { svc.getCharacteristic(it) }.forEach { pending += it }
                subscribeNext(g)
                status.value = when (f.kind) {
                    Kind.HEART_RATE -> "Receiving heart rate"
                    Kind.BLOOD_PRESSURE -> "Start a measurement on the cuff"
                    Kind.PULSE_OXIMETER -> "Keep your finger in the oximeter"
                    else -> "Step on the scale"
                }
            }

            @Suppress("DEPRECATION")
            private fun subscribeNext(g: BluetoothGatt) {
                val c = pending.removeFirstOrNull() ?: return
                g.setCharacteristicNotification(c, true)
                val d = c.getDescriptor(CCCD) ?: return subscribeNext(g)
                val indicate = c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
                d.value = if (indicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(d)
            }

            override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, s: Int) = subscribeNext(g)

            @Deprecated("Deprecated in Java")
            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
                val v = c.value ?: return
                handle(ctx, f.kind, v)
            }
        })
    }

    private fun handle(ctx: Context, kind: Kind, v: ByteArray) {
        val p = Prefs.get(ctx)
        val now = System.currentTimeMillis()
        runCatching {
            when (kind) {
                Kind.HEART_RATE -> {
                    val hr = GattParsers.heartRate(v)
                    liveHr.value = hr.bpm
                    if (hr.rrMs.isNotEmpty()) rrIntervals.value = (rrIntervals.value + hr.rrMs).takeLast(600)
                }
                Kind.BLOOD_PRESSURE -> GattParsers.bloodPressure(v)?.let { bp ->
                    val k = if (bp.unitKpa) 7.50062 else 1.0 // store in mmHg
                    p.addVital("bp|$now|${(bp.systolic * k).toInt()}|${(bp.diastolic * k).toInt()}|${bp.pulse?.toInt() ?: ""}")
                    status.value = "Saved ${(bp.systolic * k).toInt()}/${(bp.diastolic * k).toInt()}"
                }
                Kind.PULSE_OXIMETER -> GattParsers.plx(v)?.let { o ->
                    lastSpo2.value = o.spo2.toInt() to o.pulse.toInt()
                    overnightSpo2 += now to o.spo2.toInt()
                    if (overnightSpo2.size % 30 == 1) p.addVital("spo2|$now|${o.spo2.toInt()}|${o.pulse.toInt()}")
                }
                Kind.WEIGHT -> GattParsers.weight(v)?.let { w ->
                    p.addVital("weight|$now|${"%.1f".format(java.util.Locale.US, w.kg)}")
                    p.profile = p.profile.copy(weightKg = w.kg.toInt())
                    status.value = "Saved ${"%.1f".format(java.util.Locale.US, w.kg)} kg"
                }
                Kind.BODY_COMPOSITION -> {
                    val b = GattParsers.bodyComposition(v)
                    p.addVital("body|$now|${b.fatPct}|${b.muscleKg ?: ""}|${b.waterKg ?: ""}")
                    status.value = "Saved body fat ${b.fatPct}%"
                }
            }
        }.onFailure { status.value = "Couldn't read that measurement" }
    }

    fun disconnect() { gatt?.let { runCatching { it.disconnect(); it.close() } }; gatt = null }
}
