package com.bitchat.android.transport

import com.bitchat.android.transport.api.*
import com.bitchat.android.transport.lora.DutyCycleManager
import com.bitchat.android.transport.lora.LoRaFragmenter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests unitaires pour TransportManager
 */
class TransportManagerTest {

    private lateinit var dutyCycleManager: DutyCycleManager
    private lateinit var fragmenter: LoRaFragmenter

    @Before
    fun setup() {
        dutyCycleManager = DutyCycleManager(DutyCycleManager.Region.EU868)
        fragmenter = LoRaFragmenter(mtu = 200)
    }

    @Test
    fun `test duty cycle initial utilization is zero`() {
        val utilization = dutyCycleManager.getCurrentUtilization()
        assertEquals(0.0, utilization, 0.001)
    }

    @Test
    fun `test duty cycle backoff increases with usage`() = runTest {
        // Simuler beaucoup de transmissions
        repeat(50) {
            dutyCycleManager.logTransmission(1000) // 1 seconde chacune
        }
        
        val backoff = dutyCycleManager.getBackoffTime(100, 9)
        
        // Devrait avoir un backoff > 0 car on dépasse 1%
        assertTrue("Backoff should be > 0 when over duty cycle", backoff > 0)
    }

    @Test
    fun `test fragmenter small packet not fragmented`() {
        val smallPayload = ByteArray(100) { it.toByte() }
        val packet = TransportPacket(
            sourceHash = ByteArray(16),
            destinationHash = ByteArray(16),
            payload = smallPayload,
            packetId = "test-1"
        )
        
        val fragments = fragmenter.fragment(packet)
        
        assertEquals(1, fragments.size)
        assertTrue(fragments[0].isLast)
    }

    @Test
    fun `test fragmenter large packet is fragmented`() {
        val largePayload = ByteArray(500) { it.toByte() }
        val packet = TransportPacket(
            sourceHash = ByteArray(16),
            destinationHash = ByteArray(16),
            payload = largePayload,
            packetId = "test-2"
        )
        
        val fragments = fragmenter.fragment(packet)
        
        assertTrue("Large packet should be fragmented", fragments.size > 1)
        assertTrue("Last fragment should be marked", fragments.last().isLast)
    }

    @Test
    fun `test fragmenter reassembly works`() {
        val originalPayload = "Hello World Test Message".toByteArray()
        val packet = TransportPacket(
            sourceHash = ByteArray(16),
            destinationHash = ByteArray(16),
            payload = originalPayload,
            packetId = "test-3"
        )
        
        val fragments = fragmenter.fragment(packet)
        
        // Simuler réception
        var reassembled: TransportPacket? = null
        for (fragment in fragments) {
            reassembled = fragmenter.defragment(fragment)
        }
        
        assertNotNull("Reassembly should complete", reassembled)
        assertArrayEquals("Payload should match", originalPayload, reassembled?.payload)
    }

    @Test
    fun `test transport packet equality`() {
        val packet1 = TransportPacket(
            sourceHash = ByteArray(16),
            destinationHash = ByteArray(16),
            payload = "Test".toByteArray(),
            packetId = "same-id"
        )
        
        val packet2 = TransportPacket(
            sourceHash = ByteArray(16) { 1 },
            destinationHash = ByteArray(16) { 2 },
            payload = "Different".toByteArray(),
            packetId = "same-id"
        )
        
        // Deux packets avec même ID sont égaux (pour déduplication)
        assertEquals(packet1, packet2)
    }

    @Test
    fun `test transmit result success`() {
        val result = TransmitResult(success = true)
        assertTrue(result.success)
        assertFalse(result.queued)
        assertNull(result.error)
    }

    @Test
    fun `test transmit result queued`() {
        val result = TransmitResult(
            success = false,
            queued = true,
            estimatedDeliveryTime = 12345678,
            error = "Channel busy"
        )
        assertFalse(result.success)
        assertTrue(result.queued)
        assertNotNull(result.estimatedDeliveryTime)
        assertEquals("Channel busy", result.error)
    }
}
