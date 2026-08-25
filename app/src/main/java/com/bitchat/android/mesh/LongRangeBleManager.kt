package com.bitchat.android.mesh

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.bitchat.android.ui.debug.DebugPreferenceManager
import com.bitchat.android.util.AppConstants

/**
 * Bluetooth 5 "Long Range" (LE Coded PHY) support.
 *
 * Two independent, complementary features:
 *
 *  1. Connection PHY upgrade ([applyToGatt]): requests Coded PHY (S=8 or S=2) on GATT
 *     client connections. S=8 trades ~8x airtime for ~+12 dB link budget, i.e. up to
 *     ~4x range in line of sight. The PHY update procedure is negotiated: if the peer
 *     rejects it (no Coded PHY support), the link transparently stays on 1M.
 *
 *  2. Coded discovery ([startCodedAdvertising]): runs an additional *extended*
 *     advertising set whose primary PHY is Coded, so long-range peers can also
 *     discover each other at range. The classic legacy advertisement is left running
 *     untouched for compatibility with unpatched peers.
 *
 * All settings live in [DebugPreferenceManager]; nothing here throws.
 */
object LongRangeBleManager {
    private const val TAG = "LongRangeBle"

    enum class Coding { S8, S2 }

    /** Observed PHY of a live link, as reported by onPhyUpdate/onPhyRead. */
    enum class PhyStatus { CODED_BIDIRECTIONAL, CODED_TX_ONLY, CODED_RX_ONLY, PHY_1M, PHY_2M, UPDATE_FAILED, UNKNOWN }

    data class ActivePhy(
        val txPhy: Int,
        val rxPhy: Int,
        val status: Int,
        val isClientRole: Boolean,
        val updatedAtMillis: Long
    ) {
        val phyStatus: PhyStatus get() = when {
            status != BluetoothGatt.GATT_SUCCESS -> PhyStatus.UPDATE_FAILED
            txPhy == BluetoothDevice.PHY_LE_CODED && rxPhy == BluetoothDevice.PHY_LE_CODED -> PhyStatus.CODED_BIDIRECTIONAL
            txPhy == BluetoothDevice.PHY_LE_CODED -> PhyStatus.CODED_TX_ONLY
            rxPhy == BluetoothDevice.PHY_LE_CODED -> PhyStatus.CODED_RX_ONLY
            txPhy == BluetoothDevice.PHY_LE_2M || rxPhy == BluetoothDevice.PHY_LE_2M -> PhyStatus.PHY_2M
            else -> PhyStatus.PHY_1M
        }
    }

    private val _activePhy = MutableStateFlow<Map<String, ActivePhy>>(emptyMap())
    /** Per-address view of the PHY actually in use. Empty entry == never confirmed. */
    val activePhy: StateFlow<Map<String, ActivePhy>> = _activePhy.asStateFlow()

    fun recordPhy(address: String, txPhy: Int, rxPhy: Int, status: Int, isClientRole: Boolean) {
        _activePhy.value = _activePhy.value + (address to
            ActivePhy(txPhy, rxPhy, status, isClientRole, System.currentTimeMillis()))
    }

    /** Drop stale state so the UI never shows Coded on a dead link. */
    fun clearPhy(address: String) {
        if (_activePhy.value.containsKey(address)) _activePhy.value = _activePhy.value - address
    }

    fun phyStatusOf(address: String): PhyStatus =
        _activePhy.value[address]?.phyStatus ?: PhyStatus.UNKNOWN

    /** True if the local chipset supports LE Coded PHY. */
    fun isCodedPhySupported(context: Context): Boolean = try {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bm.adapter?.isLeCodedPhySupported == true
    } catch (_: Exception) { false }

    /** True if the local chipset supports BLE 5 extended advertising. */
    fun isExtendedAdvSupported(context: Context): Boolean = try {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bm.adapter?.isLeExtendedAdvertisingSupported == true
    } catch (_: Exception) { false }

    fun isPhyUpgradeEnabled(): Boolean = try {
        DebugPreferenceManager.getLongRangePhyEnabled(true)
    } catch (_: Exception) { false }

    fun isCodedAdvEnabled(): Boolean = try {
        isPhyUpgradeEnabled() && DebugPreferenceManager.getLongRangeAdvEnabled(true)
    } catch (_: Exception) { false }

    fun coding(): Coding = try {
        if (DebugPreferenceManager.getLongRangePhyS2(false)) Coding.S2 else Coding.S8
    } catch (_: Exception) { Coding.S8 }

    /**
     * Request Coded PHY on an existing GATT client connection.
     * No-op when the feature is disabled. Returns true if the request was queued.
     */
    @SuppressLint("MissingPermission")
    fun applyToGatt(gatt: BluetoothGatt, reason: String, context: Context? = null): Boolean {
        if (!isPhyUpgradeEnabled()) return false
        // Never issue a Coded request the local controller cannot honour.
        if (context != null && !isCodedPhySupported(context)) {
            Log.d(TAG, "Local controller lacks Coded PHY; skipping request ($reason)")
            return false
        }
        val phyOption = if (coding() == Coding.S2)
            BluetoothDevice.PHY_OPTION_S2 else BluetoothDevice.PHY_OPTION_S8
        return try {
            // setPreferredPhy is fire-and-forget (Unit); the result arrives via onPhyUpdate
            gatt.setPreferredPhy(
                BluetoothDevice.PHY_LE_CODED_MASK,
                BluetoothDevice.PHY_LE_CODED_MASK,
                phyOption
            )
            Log.i(TAG, "Coded PHY (${coding()}) requested on ${gatt.device.address} ($reason)")
            true
        } catch (e: Exception) {
            Log.w(TAG, "setPreferredPhy failed on ${gatt.device.address}: ${e.message}")
            false
        }
    }

    /**
     * Request Coded PHY from the GATT *server* role. The link layer PHY is shared by both
     * roles, so this mostly matters when the remote peer is the one that connected to us
     * and never asks for an upgrade itself.
     */
    @SuppressLint("MissingPermission")
    fun applyToGattServer(
        server: android.bluetooth.BluetoothGattServer,
        device: BluetoothDevice,
        context: Context,
        reason: String
    ): Boolean {
        if (!isPhyUpgradeEnabled()) return false
        if (!isCodedPhySupported(context)) return false
        val phyOption = if (coding() == Coding.S2)
            BluetoothDevice.PHY_OPTION_S2 else BluetoothDevice.PHY_OPTION_S8
        return try {
            server.setPreferredPhy(
                device,
                BluetoothDevice.PHY_LE_CODED_MASK,
                BluetoothDevice.PHY_LE_CODED_MASK,
                phyOption
            )
            Log.i(TAG, "Coded PHY (${coding()}) requested (server) on ${device.address} ($reason)")
            true
        } catch (e: Exception) {
            Log.w(TAG, "server setPreferredPhy failed on ${device.address}: ${e.message}")
            false
        }
    }

    /** Re-apply the current PHY preference to every active client connection. */
    fun applyToAllConnections(connectionTracker: BluetoothConnectionTracker, context: Context? = null) {
        val enabled = isPhyUpgradeEnabled()
        connectionTracker.getConnectedDevices().values.forEach { dc ->
            val gatt = dc.gatt
            if (dc.isClient && gatt != null) {
                if (enabled) applyToGatt(gatt, "settings-changed", context) else restoreDefaultPhy(gatt)
            }
        }
    }

    /** Restore the stack default (1M) PHY preference on a connection. */
    @SuppressLint("MissingPermission")
    private fun restoreDefaultPhy(gatt: BluetoothGatt) {
        try {
            gatt.setPreferredPhy(
                BluetoothDevice.PHY_LE_1M_MASK,
                BluetoothDevice.PHY_LE_1M_MASK,
                BluetoothDevice.PHY_OPTION_NO_PREFERRED
            )
        } catch (_: Exception) { }
    }

    // --- Coded extended advertising (long-range discovery) ---

    enum class AdvState { STOPPED, STARTING, RUNNING, FAILED }

    private val advLock = Any()
    private var codedAdvCallback: AdvertisingSetCallback? = null
    private var codedAdvSet: android.bluetooth.le.AdvertisingSet? = null
    private val _codedAdvState = MutableStateFlow(AdvState.STOPPED)
    val codedAdvState: StateFlow<AdvState> = _codedAdvState.asStateFlow()

    /**
     * Start a non-legacy, connectable advertising set on Coded PHY, in parallel with
     * the legacy advertisement. Idempotent. Failures are logged, never fatal.
     */
    @SuppressLint("MissingPermission")
    fun startCodedAdvertising(context: Context, myPeerID: String) {
        if (!isCodedAdvEnabled()) return
        synchronized(advLock) {
            if (codedAdvCallback != null) return // already starting or running
        }
        if (!isExtendedAdvSupported(context)) {
            Log.w(TAG, "Extended advertising not supported on this device; coded discovery unavailable")
            return
        }
        // Extended advertising alone is not enough: the radio must also do Coded PHY,
        // otherwise startAdvertisingSet() is accepted then fails asynchronously.
        if (!isCodedPhySupported(context)) {
            Log.w(TAG, "Coded PHY not supported on this device; coded discovery unavailable")
            return
        }
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val advertiser = try { bm.adapter?.bluetoothLeAdvertiser } catch (_: Exception) { null } ?: return

        // Peer identity must be exactly 16 hex chars -> 8 bytes. Advertising an empty or
        // truncated identity produces peers nobody can route to, so refuse instead.
        val peerIDBytes = try {
            require(myPeerID.length == 16 && myPeerID.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                "malformed peerID (len=${myPeerID.length})"
            }
            myPeerID.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "Cannot start coded advertising: ${e.message}")
            return
        }

        // Extended advertising: connectable and scannable are mutually exclusive (BT5 spec).
        // We choose connectable and put the 8-byte peer identity directly in the adv data.
        val params = AdvertisingSetParameters.Builder()
            .setLegacyMode(false)
            .setConnectable(true)
            .setScannable(false)
            .setInterval(AdvertisingSetParameters.INTERVAL_MEDIUM)
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
            .setPrimaryPhy(BluetoothDevice.PHY_LE_CODED)
            .setSecondaryPhy(BluetoothDevice.PHY_LE_CODED)
            .setIncludeTxPower(false)
            .build()

        val advData = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(AppConstants.Mesh.Gatt.SERVICE_UUID))
            .addServiceData(ParcelUuid(AppConstants.Mesh.Gatt.SERVICE_UUID), peerIDBytes)
            .setIncludeTxPowerLevel(false)
            .setIncludeDeviceName(false)
            .build()

        val callback = object : AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(
                advertisingSet: android.bluetooth.le.AdvertisingSet?,
                txPower: Int,
                status: Int
            ) {
                if (status == ADVERTISE_SUCCESS) {
                    synchronized(advLock) { codedAdvSet = advertisingSet }
                    _codedAdvState.value = AdvState.RUNNING
                    Log.i(TAG, "Coded long-range advertising started (txPower=$txPower dBm)")
                } else {
                    synchronized(advLock) { codedAdvCallback = null; codedAdvSet = null }
                    _codedAdvState.value = AdvState.FAILED
                    Log.e(TAG, "Coded advertising failed to start: status=$status")
                }
            }

            override fun onAdvertisingSetStopped(advertisingSet: android.bluetooth.le.AdvertisingSet?) {
                synchronized(advLock) { codedAdvCallback = null; codedAdvSet = null }
                _codedAdvState.value = AdvState.STOPPED
                Log.i(TAG, "Coded long-range advertising stopped")
            }
        }

        try {
            synchronized(advLock) { codedAdvCallback = callback }
            _codedAdvState.value = AdvState.STARTING
            advertiser.startAdvertisingSet(params, advData, null, null, null, callback)
            Log.i(TAG, "Starting coded long-range advertising set…")
        } catch (e: Exception) {
            synchronized(advLock) { codedAdvCallback = null }
            _codedAdvState.value = AdvState.FAILED
            Log.e(TAG, "startAdvertisingSet exception: ${e.message}")
        }
    }

    /** Stop the coded advertising set if running. Safe to call anytime. */
    @SuppressLint("MissingPermission")
    fun stopCodedAdvertising(context: Context) {
        val cb = synchronized(advLock) {
            val c = codedAdvCallback ?: return
            codedAdvCallback = null
            codedAdvSet = null
            c
        }
        _codedAdvState.value = AdvState.STOPPED
        try {
            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            bm.adapter?.bluetoothLeAdvertiser?.stopAdvertisingSet(cb)
        } catch (e: Exception) {
            Log.w(TAG, "stopAdvertisingSet exception: ${e.message}")
        }
    }
}
