package com.bitchat.android.transport.lora

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Gestionnaire USB pour modules LoRa
 * 
 * Gère:
 * - Détection des devices USB
 * - Demandes de permission
 * - Connexion/Déconnexion hot-plug
 * - Broadcast receivers
 */
class LoRaUsbManager(private val context: Context) {

    companion object {
        const val ACTION_USB_PERMISSION = "com.bitchat.android.USB_PERMISSION"
        const val ACTION_USB_ATTACHED = "android.hardware.usb.action.USB_DEVICE_ATTACHED"
        const val ACTION_USB_DETACHED = "android.hardware.usb.action.USB_DEVICE_DETACHED"
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    
    private val _connectionState = MutableStateFlow<UsbConnectionState>(UsbConnectionState.Disconnected)
    val connectionState: StateFlow<UsbConnectionState> = _connectionState
    
    private var permissionCallback: ((Boolean) -> Unit)? = null
    private var deviceCallback: ((UsbDevice?) -> Unit)? = null
    
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        
                        if (granted) {
                            _connectionState.value = UsbConnectionState.Connected(device!!)
                        } else {
                            _connectionState.value = UsbConnectionState.PermissionDenied
                        }
                        
                        permissionCallback?.invoke(granted)
                    }
                }
                
                ACTION_USB_ATTACHED -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    device?.let { checkAndRequestPermission(it) }
                    deviceCallback?.invoke(device)
                }
                
                ACTION_USB_DETACHED -> {
                    _connectionState.value = UsbConnectionState.Disconnected
                    deviceCallback?.invoke(null)
                }
            }
        }
    }

    /**
     * Enregistre le broadcast receiver
     * À appeler dans onResume() de l'Activity
     */
    fun register() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(ACTION_USB_ATTACHED)
            addAction(ACTION_USB_DETACHED)
        }
        context.registerReceiver(usbReceiver, filter)
        
        // Vérifier si un device est déjà connecté
        checkExistingDevice()
    }

    /**
     * Désenregistre le broadcast receiver
     * À appeler dans onPause() de l'Activity
     */
    fun unregister() {
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (e: IllegalArgumentException) {
            // Déjà désenregistré
        }
    }

    /**
     * Recherche un module LoRa déjà connecté
     */
    fun findLoRaDevice(): UsbDevice? {
        return usbManager.deviceList.values.find { device ->
            isKnownLoRaDevice(device)
        }
    }

    /**
     * Demande la permission pour un device USB
     */
    fun requestPermission(device: UsbDevice, callback: (Boolean) -> Unit) {
        permissionCallback = callback
        
        val permissionIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION_USB_PERMISSION),
            PendingIntent.FLAG_IMMUTABLE
        )
        
        usbManager.requestPermission(device, permissionIntent)
    }

    /**
     * Vérifie si on a déjà la permission
     */
    fun hasPermission(device: UsbDevice): Boolean {
        return usbManager.hasPermission(device)
    }

    /**
     * Définit un callback pour les changements de device
     */
    fun setDeviceCallback(callback: (UsbDevice?) -> Unit) {
        this.deviceCallback = callback
    }

    private fun checkExistingDevice() {
        findLoRaDevice()?.let { device ->
            if (hasPermission(device)) {
                _connectionState.value = UsbConnectionState.Connected(device)
            } else {
                _connectionState.value = UsbConnectionState.WaitingPermission(device)
                requestPermission(device) {}
            }
        }
    }

    private fun checkAndRequestPermission(device: UsbDevice) {
        if (!hasPermission(device)) {
            _connectionState.value = UsbConnectionState.WaitingPermission(device)
            requestPermission(device) {}
        }
    }

    private fun isKnownLoRaDevice(device: UsbDevice): Boolean {
        return when (device.vendorId to device.productId) {
            // CP2102 (LilyGo T-Beam, etc.)
            0x10C4 to 0xEA60 -> true
            // CH340
            0x1A86 to 0x7523 -> true
            // FT232
            0x0403 to 0x6001 -> true
            // Wio SX1262
            0x2886 to 0x802F -> true
            else -> false
        }
    }

    sealed class UsbConnectionState {
        object Disconnected : UsbConnectionState()
        data class WaitingPermission(val device: UsbDevice) : UsbConnectionState()
        data class Connected(val device: UsbDevice) : UsbConnectionState()
        object PermissionDenied : UsbConnectionState()
    }
}
