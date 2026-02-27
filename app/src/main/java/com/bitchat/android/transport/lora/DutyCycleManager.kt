package com.bitchat.android.transport.lora

/**
 * Duty Cycle Manager - Garantit la conformité réglementaire
 * 
 * Réglementation EU 868 MHz:
 * - 868.0 - 868.6 MHz: 1% duty cycle
 * - 868.7 - 869.2 MHz: 0.1% duty cycle  
 * - 869.4 - 869.65 MHz: 10% duty cycle (canal 10%, usage limité)
 * 
 * Sans respect du duty cycle:
 * - Interférences avec autres services
 * - Sanctions réglementaires
 * - Brouillage illégal
 */
class DutyCycleManager(private val region: Region) {
    
    enum class Region {
        EU868,      // Europe (1% sur 868.0-868.6)
        US915,      // USA (pas de duty cycle mais LBT)
        AS923,      // Asie
        AU915       // Australie
    }
    
    // Fenêtre de mesure: 1 heure (reglementation EU)
    private val WINDOW_MS = 60 * 60 * 1000L
    
    // Historique des transmissions (timestamp -> airtime_ms)
    private val transmissionLog = mutableListOf<TransmissionRecord>()
    
    // Limits par région et fréquence
    private val limits = when (region) {
        Region.EU868 -> mapOf(
            868_000_000L..868_600_000L to 0.01,  // 1%
            868_700_000L..869_200_000L to 0.001, // 0.1%
            869_400_000L..869_650_000L to 0.10   // 10%
        )
        else -> mapOf(LongRange.EMPTY to 1.0) // Pas de limite
    }
    
    /**
     * Calcule le temps d'attente avant prochaine transmission autorisée
     * 
     * @param packetSize Taille du packet en bytes
     * @param spreadingFactor SF LoRa (affecte le temps d'air)
     * @return Temps d'attente en ms (0 si transmission immédiate possible)
     */
    fun getBackoffTime(packetSize: Int, spreadingFactor: Int): Long {
        if (region != Region.EU868) return 0 // Pas de duty cycle
        
        // Nettoyer les vieux records
        cleanupOldRecords()
        
        // Calculer le temps d'air estimé
        val estimatedAirtime = estimateAirtime(packetSize, spreadingFactor)
        
        // Calculer l'utilisation actuelle
        val currentUsage = getCurrentUtilization()
        val limit = 0.01 // 1% par défaut
        
        // Si on est déjà à la limite, calculer quand on pourra transmettre
        if (currentUsage >= limit) {
            return calculateWaitTime(limit, estimatedAirtime)
        }
        
        // Vérifier si cette transmission ferait dépasser la limite
        val totalWindowTime = WINDOW_MS
        val usedTime = (transmissionLog.sumOf { it.airtime }).toDouble()
        val projectedUsage = (usedTime + estimatedAirtime) / totalWindowTime
        
        return if (projectedUsage > limit) {
            // Attendre que suffisamment de temps s'écoule
            val excessTime = (projectedUsage - limit) * totalWindowTime
            excessTime.toLong()
        } else {
            0 // OK pour transmettre
        }
    }
    
    /**
     * Enregistre une transmission effectuée
     */
    fun logTransmission(airtimeMs: Long) {
        transmissionLog.add(TransmissionRecord(
            timestamp = System.currentTimeMillis(),
            airtime = airtimeMs
        ))
        
        // Nettoyer périodiquement
        if (transmissionLog.size > 1000) {
            cleanupOldRecords()
        }
    }
    
    /**
     * Retourne l'utilisation actuelle du duty cycle (0.0 - 1.0)
     */
    fun getCurrentUtilization(): Double {
        cleanupOldRecords()
        
        if (transmissionLog.isEmpty()) return 0.0
        
        val totalAirtime = transmissionLog.sumOf { it.airtime }
        return totalAirtime.toDouble() / WINDOW_MS
    }
    
    /**
     * Retourne le temps d'air restant disponible dans la fenêtre courante
     */
    fun getRemainingAirtime(): Long {
        val limit = (WINDOW_MS * 0.01).toLong() // 1% = 36 secondes
        val used = transmissionLog.sumOf { it.airtime }
        return (limit - used).coerceAtLeast(0)
    }
    
    /**
     * Calcule le temps d'air estimé pour un packet
     */
    private fun estimateAirtime(bytes: Int, sf: Int): Long {
        // Approximation basée sur SX1262 datasheet
        val symbolTime = (1 shl sf) / 125.0 // ms par symbole @ 125kHz
        
        // Preambule
        val preamble = 16 * symbolTime
        
        // Payload (approximation)
        val payloadSym = 8 + kotlin.math.ceil(
            (8 * bytes - 4 * sf + 28 + 16) / (4.0 * sf)
        ) * (8.0 / 4.0) // CR 4/8
        
        val payload = payloadSym * symbolTime
        
        return (preamble + payload).toLong()
    }
    
    /**
     * Calcule le temps d'attente nécessaire
     */
    private fun calculateWaitTime(limit: Double, neededAirtime: Long): Long {
        // Trier les transmissions par ancienneté
        val sorted = transmissionLog.sortedBy { it.timestamp }
        
        var simulatedUsage = getCurrentUtilization()
        var simulatedWindow = transmissionLog.toMutableList()
        var waitTime = 0L
        
        // Simuler l'écoulement du temps jusqu'à pouvoir transmettre
        while (simulatedUsage >= limit) {
            // Retirer la plus vieille transmission
            if (simulatedWindow.isNotEmpty()) {
                val oldest = simulatedWindow.removeAt(0)
                simulatedUsage = simulatedWindow.sumOf { it.airtime }.toDouble() / WINDOW_MS
                
                // Avancer le temps virtuel
                if (simulatedWindow.isNotEmpty()) {
                    waitTime = simulatedWindow.first().timestamp - oldest.timestamp
                }
            } else {
                break
            }
        }
        
        return waitTime.coerceAtLeast(1000) // Minimum 1 seconde
    }
    
    /**
     * Supprime les transmissions plus vieilles que 1 heure
     */
    private fun cleanupOldRecords() {
        val cutoff = System.currentTimeMillis() - WINDOW_MS
        transmissionLog.removeAll { it.timestamp < cutoff }
    }
    
    data class TransmissionRecord(
        val timestamp: Long,
        val airtime: Long
    )
    
    /**
     * Statistiques pour monitoring
     */
    fun getStats(): DutyCycleStats {
        cleanupOldRecords()
        return DutyCycleStats(
            currentUtilization = getCurrentUtilization(),
            remainingAirtime = getRemainingAirtime(),
            transmissionsInWindow = transmissionLog.size,
            nextAvailableSlot = if (getCurrentUtilization() >= 0.01) {
                System.currentTimeMillis() + getBackoffTime(50, 9)
            } else null
        )
    }
    
    data class DutyCycleStats(
        val currentUtilization: Double,
        val remainingAirtime: Long,
        val transmissionsInWindow: Int,
        val nextAvailableSlot: Long?
    )
}
