package dz.univ.alger1.heartcareai.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * Compatible uniquement avec les appareils BLE qui exposent le service
 * standard Heart Rate (0x180D) et la caractéristique Measurement (0x2A37).
 * Une montre qui n'expose PAS ce service nécessite une autre intégration.
 */
data class SensorState(
    val message: String = "Aucun appareil connecté",
    val deviceName: String? = null,
    val pulse: Int? = null,
    val connected: Boolean = false,
    val scanning: Boolean = false
)

class HeartRateBleManager(private val context: Context) {
    companion object {
        private val HEART_RATE_SERVICE = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        private val HEART_RATE_MEASUREMENT = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        private val CLIENT_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private val handler = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(SensorState())
    val state: StateFlow<SensorState> = _state
    private var isScanning = false

    private val callback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!isScanning) return
            stopScanOnly()
            val device = result.device ?: return
            val label = try { device.name ?: result.scanRecord?.deviceName ?: "Capteur BLE" }
            catch (_: SecurityException) { "Capteur BLE" }
            _state.value = SensorState(message = "Connexion au capteur...", deviceName = label)
            try { gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE) }
            catch (_: SecurityException) {
                _state.value = SensorState(message = "Autorisation Bluetooth manquante")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            _state.value = SensorState(message = "Recherche impossible (code $errorCode)")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                _state.value = _state.value.copy(message = "Découverte du service cardiaque...", connected = true)
                try { g.discoverServices() } catch (_: SecurityException) { stop() }
            } else {
                _state.value = SensorState(
                    message = if (status != BluetoothGatt.GATT_SUCCESS)
                        "Échec de la connexion BLE (code $status)" else "Capteur déconnecté"
                )
                try { g.close() } catch (_: SecurityException) { }
                if (gatt == g) gatt = null
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _state.value = _state.value.copy(message = "Service Bluetooth indisponible")
                return
            }
            val characteristic = g.getService(HEART_RATE_SERVICE)?.getCharacteristic(HEART_RATE_MEASUREMENT)
            if (characteristic == null) {
                _state.value = _state.value.copy(message = "Service fréquence cardiaque non exposé par ce capteur")
                return
            }
            try {
                g.setCharacteristicNotification(characteristic, true)
                val descriptor = characteristic.getDescriptor(CLIENT_CONFIG)
                if (descriptor == null) {
                    _state.value = _state.value.copy(message = "Notifications BLE non disponibles")
                    return
                }
                val requested = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(descriptor)
                }
                if (requested) {
                    _state.value = _state.value.copy(message = "Activation des notifications BLE...")
                } else {
                    _state.value = _state.value.copy(message = "Échec d'activation des notifications BLE")
                }
            } catch (_: SecurityException) { stop() }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (d.uuid == CLIENT_CONFIG) {
                _state.value = _state.value.copy(message =
                    if (status == BluetoothGatt.GATT_SUCCESS) "Capteur connecté. Attente de mesures..."
                    else "Notifications BLE refusées (code $status)")
            }
        }

        @Deprecated("Compatibilité Android < 13")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            receive(c.value ?: return)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            receive(value)
        }
    }

    private fun receive(bytes: ByteArray) {
        if (bytes.size < 2) return
        val sixteenBit = (bytes[0].toInt() and 1) != 0
        if (sixteenBit && bytes.size < 3) return
        val pulse = if (sixteenBit) {
            (bytes[1].toInt() and 0xff) + ((bytes[2].toInt() and 0xff) shl 8)
        } else bytes[1].toInt() and 0xff
        if (pulse in 25..240) {
            _state.value = _state.value.copy(pulse = pulse, message = "Mesure en direct", connected = true)
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        stop()
        val adapter: BluetoothAdapter = manager.adapter ?: run {
            _state.value = SensorState(message = "Bluetooth absent sur cet appareil")
            return
        }
        if (!adapter.isEnabled) {
            _state.value = SensorState(message = "Veuillez activer le Bluetooth du téléphone")
            return
        }
        try {
            val currentScanner = adapter.bluetoothLeScanner ?: run {
                _state.value = SensorState(message = "Scanner BLE indisponible")
                return
            }
            scanner = currentScanner
            isScanning = true
            _state.value = SensorState(message = "Recherche de capteurs cardiaques BLE...", scanning = true)
            // Ne détecte pas les montres dont le service 0x180D n'est pas annoncé.
            val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(HEART_RATE_SERVICE)).build())
            currentScanner.startScan(filters,
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
            handler.postDelayed({
                if (isScanning) {
                    stopScanOnly()
                    _state.value = SensorState(message = "Aucun capteur BLE standard trouvé (15 s)")
                }
            }, 15_000L)
        } catch (_: SecurityException) {
            isScanning = false
            _state.value = SensorState(message = "Autorisation Bluetooth refusée")
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScanOnly() {
        if (isScanning) {
            try { scanner?.stopScan(callback) } catch (_: SecurityException) { }
        }
        isScanning = false
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        stopScanOnly()
        try { gatt?.disconnect(); gatt?.close() } catch (_: SecurityException) { }
        gatt = null
        _state.value = SensorState()
    }
}
