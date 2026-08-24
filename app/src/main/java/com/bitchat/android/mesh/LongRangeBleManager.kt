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
    fun applyToGatt(gatt: BluetoothGatt, reason: String): Boolean {
        if (!isPhyUpgradeEnabled()) return false
        val phyOption = if (coding() == Coding.S2)
            BluetoothDevice.PHY_OPTION_S2 else BluetoothDevice.PHY_OPTION_S8
        return try {
            val ok = gatt.setPreferredPhy(
                BluetoothDevice.PHY_LE_CODED_MASK,
                BluetoothDevice.PHY_LE_CODED_MASK,
                phyOption
            )
            Log.i(TAG, "Coded PHY (${coding()}) requested on ${gatt.device.address} ($reason): $ok")
            ok
        } catch (e: Exception) {
            Log.w(TAG, "setPreferredPhy failed on ${gatt.device.address}: ${e.message}")
            false
        }
    }

    /** Re-apply the current PHY preference to every active client connection. */
    fun applyToAllConnections(connectionTracker: BluetoothConnectionTracker) {
        val enabled = isPhyUpgradeEnabled()
        connectionTracker.getConnectedDevices().values.forEach { dc ->
            val gatt = dc.gatt
            if (dc.isClient && gatt != null) {
                if (enabled) applyToGatt(gatt, "settings-changed") else restoreDefaultPhy(gatt)
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

    private var codedAdvCallback: AdvertisingSetCallback? = null

    /**
     * Start a non-legacy, connectable advertising set on Coded PHY, in parallel with
     * the legacy advertisement. Idempotent. Failures are logged, never fatal.
     */
    @SuppressLint("MissingPermission")
    fun startCodedAdvertising(context: Context, myPeerID: String) {
        if (!isCodedAdvEnabled()) return
        if (codedAdvCallback != null) return // already running
        if (!isExtendedAdvSupported(context)) {
            Log.w(TAG, "Extended advertising not supported on this device; coded discovery unavailable")
            return
        }
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val advertiser = try { bm.adapter?.bluetoothLeAdvertiser } catch (_: Exception) { null } ?: return

        val peerIDBytes = try {
            myPeerID.chunked(2).map { it.toInt(16).toByte() }.toByteArray().take(8).toByteArray()
        } catch (_: Exception) { ByteArray(0) }

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
                    Log.i(TAG, "Coded long-range advertising started (txPower=$txPower dBm)")
                } else {
                    Log.e(TAG, "Coded advertising failed to start: status=$status")
                    codedAdvCallback = null
                }
            }

            override fun onAdvertisingSetStopped(advertisingSet: android.bluetooth.le.AdvertisingSet?) {
                Log.i(TAG, "Coded long-range advertising stopped")
            }
        }

        try {
            advertiser.startAdvertisingSet(params, advData, null, null, null, callback)
            codedAdvCallback = callback
            Log.i(TAG, "Starting coded long-range advertising set…")
        } catch (e: Exception) {
            Log.e(TAG, "startAdvertisingSet exception: ${e.message}")
        }
    }

    /** Stop the coded advertising set if running. Safe to call anytime. */
    @SuppressLint("MissingPermission")
    fun stopCodedAdvertising(context: Context) {
        val cb = codedAdvCallback ?: return
        codedAdvCallback = null
        try {
            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            bm.adapter?.bluetoothLeAdvertiser?.stopAdvertisingSet(cb)
        } catch (e: Exception) {
            Log.w(TAG, "stopAdvertisingSet exception: ${e.message}")
        }
    }
}
