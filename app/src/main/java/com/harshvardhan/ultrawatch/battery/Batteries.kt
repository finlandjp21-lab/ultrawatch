package com.harshvardhan.ultrawatch.battery

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.harshvardhan.ultrawatch.Notify
import com.harshvardhan.ultrawatch.core.BatteryMath
import com.harshvardhan.ultrawatch.core.BatteryProtocol
import com.harshvardhan.ultrawatch.core.DeviceBattery
import com.harshvardhan.ultrawatch.data.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** Collects battery levels for the watch, the paired phone and connected Bluetooth devices. */
object Batteries {
    private val _devices = MutableStateFlow<List<DeviceBattery>>(emptyList())
    val devices: StateFlow<List<DeviceBattery>> = _devices
    private val lowAlert = BatteryProtocol.LowAlert(20)
    private val history = ArrayList<Pair<Int, Long>>() // watch level samples for time estimates

    private val BATTERY_SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    private val BATTERY_LEVEL: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

    fun watch(ctx: Context): DeviceBattery {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val pct = if (level >= 0) level * 100 / scale else null
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        if (pct != null && (history.isEmpty() || history.last().first != pct)) {
            history += pct to System.currentTimeMillis()
            if (history.size > 30) history.removeAt(0)
        }
        return DeviceBattery("watch", Build.MODEL ?: "Watch", DeviceBattery.Kind.WATCH, pct, charging, System.currentTimeMillis())
    }

    /** "Full in 40 min" / "About 13 h left", from the watch's own recent readings. */
    fun watchEstimate(charging: Boolean): String {
        if (history.size < 2) return BatteryMath.formatMinutes(null)
        val (p1, t1) = history.first(); val (p2, t2) = history.last()
        val m = BatteryMath.minutesRemaining(p1, t1, p2, t2, charging)
        return if (m == null) BatteryMath.formatMinutes(null) else if (charging) "Full in ${BatteryMath.formatMinutes(m)}" else "About ${BatteryMath.formatMinutes(m)} left"
    }

    fun phone(ctx: Context): DeviceBattery? {
        val d = BatteryProtocol.decode(Prefs.get(ctx).phoneBattery.toByteArray()) ?: return null
        return DeviceBattery("phone", "Phone", DeviceBattery.Kind.PHONE, d.first, d.second, d.third)
    }

    /** Asks the phone app for a fresh reading; returns false when no phone app is reachable. */
    suspend fun requestPhone(ctx: Context): Boolean = withTimeoutOrNull(5_000) {
        val nodes = Wearable.getNodeClient(ctx).connectedNodes.await()
        nodes.forEach { Wearable.getMessageClient(ctx).sendMessage(it.id, BatteryProtocol.PATH_REQUEST_BATTERY, ByteArray(0)).await() }
        nodes.isNotEmpty()
    } ?: false

    suspend fun findPhone(ctx: Context): Boolean = withTimeoutOrNull(5_000) {
        val nodes = Wearable.getNodeClient(ctx).connectedNodes.await()
        nodes.forEach { Wearable.getMessageClient(ctx).sendMessage(it.id, BatteryProtocol.PATH_FIND_PHONE, ByteArray(0)).await() }
        nodes.isNotEmpty()
    } ?: false

    /**
     * Bluetooth devices: Android tracks battery for most headsets (HFP/A2DP); BLE devices are read
     * from the standard Battery Service. Devices that report battery only to their own app show "--".
     */
    @SuppressLint("MissingPermission")
    fun bluetooth(ctx: Context, onUpdate: () -> Unit): List<DeviceBattery> {
        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val bm = ctx.getSystemService(BluetoothManager::class.java) ?: return emptyList()
        val adapter = bm.adapter ?: return emptyList()
        val connected = LinkedHashMap<String, BluetoothDevice>()
        adapter.bondedDevices.orEmpty().filter { isConnected(it) }.forEach { connected[it.address] = it }
        runCatching { bm.getConnectedDevices(BluetoothProfile.GATT) }.getOrNull().orEmpty().forEach { connected[it.address] = it }

        return connected.values.map { d ->
            val name = runCatching { d.name }.getOrNull() ?: "Bluetooth device"
            val known = systemLevel(d) ?: bleCache[d.address]
            if (known == null && d.type != BluetoothDevice.DEVICE_TYPE_CLASSIC) readBle(ctx, d, onUpdate)
            DeviceBattery(d.address, name, BatteryProtocol.kindFor(name, d.bluetoothClass?.majorDeviceClass), known, false, System.currentTimeMillis())
        }
    }

    /** BluetoothDevice.getBatteryLevel() is not public SDK; it exists on Android 8.1+ and is used read-only. */
    private fun systemLevel(d: BluetoothDevice): Int? = runCatching {
        (d.javaClass.getMethod("getBatteryLevel").invoke(d) as Int).takeIf { it in 0..100 }
    }.getOrNull()

    private fun isConnected(d: BluetoothDevice): Boolean = runCatching {
        d.javaClass.getMethod("isConnected").invoke(d) as Boolean
    }.getOrDefault(false)

    private val bleCache = HashMap<String, Int>()
    private val bleBusy = HashSet<String>()

    @SuppressLint("MissingPermission")
    private fun readBle(ctx: Context, d: BluetoothDevice, onUpdate: () -> Unit) {
        if (!bleBusy.add(d.address)) return
        d.connectGatt(ctx, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) g.discoverServices()
                else { g.close(); bleBusy.remove(d.address) }
            }
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val c = g.getService(BATTERY_SERVICE)?.getCharacteristic(BATTERY_LEVEL)
                if (c == null) { g.disconnect(); return }
                @Suppress("DEPRECATION") g.readCharacteristic(c)
            }
            @Deprecated("Deprecated in Java")
            override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
                @Suppress("DEPRECATION") BatteryProtocol.parseBleLevel(c.value)?.let { bleCache[d.address] = it; onUpdate() }
                g.disconnect()
            }
        })
    }

    fun refresh(ctx: Context) {
        val list = buildList {
            add(watch(ctx))
            phone(ctx)?.let { add(it) }
            addAll(bluetooth(ctx) { refresh(ctx) })
        }
        val sorted = BatteryProtocol.sortForDisplay(list)
        _devices.value = sorted
        if (Prefs.get(ctx).lowBatteryAlerts) sorted.forEach { d ->
            if (lowAlert.check(d)) Notify.post(ctx, Notify.CH_BATTERY, 200 + d.id.hashCode() % 100, "${d.name} battery low", "${d.percent}% left", "battery")
        }
    }
}

/** Receives battery updates and replies from the phone companion app. */
class PhoneLinkListenerService : WearableListenerService() {
    override fun onMessageReceived(e: MessageEvent) {
        if (e.path == BatteryProtocol.PATH_PHONE_BATTERY && BatteryProtocol.decode(e.data) != null) {
            Prefs.get(this).phoneBattery = String(e.data, Charsets.UTF_8)
            Batteries.refresh(this)
        }
    }
}
