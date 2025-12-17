package com.lightningkite.services.voiceagent.phonecall

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AudioConverterTest {

    @Test
    fun `mulaw to pcm16 produces 3x samples`() {
        // 100 μ-law samples at 8kHz → 300 samples at 24kHz → 600 bytes
        val mulawData = ByteArray(100) { 0x7F.toByte() } // Silence in μ-law
        val pcm16Data = AudioConverter.mulawToPcm16_24k(mulawData)

        // 3x upsampling: 100 samples → 300 samples
        // Each PCM16 sample is 2 bytes → 600 bytes
        assertEquals(600, pcm16Data.size)
    }

    @Test
    fun `pcm16 to mulaw produces one-third samples`() {
        // 300 PCM16 samples (600 bytes) at 24kHz → 100 samples at 8kHz
        val pcm16Data = ByteArray(600) { 0 } // Silence in PCM16
        val mulawData = AudioConverter.pcm16_24kToMulaw(pcm16Data)

        // 3x downsampling: 300 samples → 100 samples
        assertEquals(100, mulawData.size)
    }

    @Test
    fun `roundtrip preserves approximate signal`() {
        // Create a simple test signal: alternating values
        val original = ByteArray(100) { i ->
            if (i % 2 == 0) 0x50.toByte() else 0xD0.toByte()
        }

        // Convert to PCM and back
        val pcm16 = AudioConverter.mulawToPcm16_24k(original)
        val roundtrip = AudioConverter.pcm16_24kToMulaw(pcm16)

        // The roundtrip won't be exact due to resampling, but should be similar
        assertEquals(original.size, roundtrip.size)

        // Check that we don't have all zeros (signal preserved somewhat)
        val nonZeroCount = roundtrip.count { it != 0.toByte() }
        assertTrue(nonZeroCount > 0, "Signal should be preserved through roundtrip")
    }

    @Test
    fun `mulaw silence decodes to near-zero pcm`() {
        // 0xFF is silence in μ-law (after complementing)
        val silence = ByteArray(10) { 0xFF.toByte() }
        val pcm16 = AudioConverter.mulawToPcm16_24k(silence)

        // Convert bytes back to shorts to check values
        val shorts = ShortArray(pcm16.size / 2)
        for (i in shorts.indices) {
            val low = pcm16[i * 2].toInt() and 0xFF
            val high = pcm16[i * 2 + 1].toInt()
            shorts[i] = ((high shl 8) or low).toShort()
        }

        // All values should be very small (near silence)
        for (s in shorts) {
            assertTrue(s.toInt() in -100..100, "Decoded silence should be near zero, got $s")
        }
    }

    @Test
    fun `empty input produces empty output`() {
        assertEquals(0, AudioConverter.mulawToPcm16_24k(ByteArray(0)).size)
        assertEquals(0, AudioConverter.pcm16_24kToMulaw(ByteArray(0)).size)
    }

    @Test
    fun `single sample mulaw converts correctly`() {
        val single = ByteArray(1) { 0x7F.toByte() }
        val pcm16 = AudioConverter.mulawToPcm16_24k(single)

        // 1 sample → 3 samples after upsampling → 6 bytes
        assertEquals(6, pcm16.size)
    }
}
