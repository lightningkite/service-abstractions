package com.lightningkite.services.voiceagent.phonecall

import com.lightningkite.services.voiceagent.AudioFormat
import kotlin.math.max
import kotlin.math.min

/**
 * Converts audio between different formats.
 *
 * Primary use case: bridging telephone audio (μ-law 8kHz) with voice agents (PCM16 24kHz).
 *
 * ## Conversion Pipeline
 *
 * Phone → Voice Agent:
 * 1. Decode μ-law to linear PCM16
 * 2. Upsample from 8kHz to 24kHz (3x interpolation)
 *
 * Voice Agent → Phone:
 * 1. Downsample from 24kHz to 8kHz (3x decimation)
 * 2. Encode linear PCM16 to μ-law
 */
public object AudioConverter {

    /**
     * Converts μ-law 8kHz audio to PCM16 24kHz.
     *
     * @param mulawData Raw μ-law encoded bytes (8-bit samples)
     * @return PCM16 little-endian bytes (16-bit samples, 3x the sample count)
     */
    public fun mulawToPcm16_24k(mulawData: ByteArray): ByteArray {
        // First decode μ-law to PCM16 at 8kHz
        val pcm8k = ShortArray(mulawData.size)
        for (i in mulawData.indices) {
            pcm8k[i] = mulawDecode(mulawData[i])
        }

        // Upsample 8kHz → 24kHz (3x linear interpolation)
        val pcm24k = upsample3x(pcm8k)

        // Convert to bytes (little-endian)
        return shortArrayToBytes(pcm24k)
    }

    /**
     * Converts PCM16 24kHz audio to μ-law 8kHz.
     *
     * @param pcm16Data PCM16 little-endian bytes (16-bit samples)
     * @return μ-law encoded bytes (8-bit samples, 1/3 the sample count)
     */
    public fun pcm16_24kToMulaw(pcm16Data: ByteArray): ByteArray {
        // Convert bytes to shorts
        val pcm24k = bytesToShortArray(pcm16Data)

        // Downsample 24kHz → 8kHz (3x decimation with low-pass filter)
        val pcm8k = downsample3x(pcm24k)

        // Encode to μ-law
        val mulawData = ByteArray(pcm8k.size)
        for (i in pcm8k.indices) {
            mulawData[i] = mulawEncode(pcm8k[i])
        }

        return mulawData
    }

    /**
     * Gets the target format for a given source format when bridging.
     */
    public fun getTargetFormat(sourceFormat: AudioFormat): AudioFormat {
        return when (sourceFormat) {
            AudioFormat.G711_ULAW, AudioFormat.G711_ALAW -> AudioFormat.PCM16_24K
            AudioFormat.PCM16_24K -> AudioFormat.G711_ULAW
            AudioFormat.PCM16_16K -> AudioFormat.G711_ULAW
            AudioFormat.PCM16_8K -> AudioFormat.G711_ULAW
        }
    }

    // ==================== μ-law Codec ====================

    /**
     * μ-law decoding table (ITU-T G.711).
     * Maps 8-bit μ-law values to 16-bit linear PCM.
     */
    private val MULAW_DECODE_TABLE = ShortArray(256) { i ->
        val mulaw = i.toByte()
        val sign = if ((mulaw.toInt() and 0x80) != 0) -1 else 1
        val exponent = (mulaw.toInt() shr 4) and 0x07
        val mantissa = mulaw.toInt() and 0x0F
        val magnitude = ((mantissa shl 3) + 0x84) shl exponent
        (sign * (magnitude - 0x84)).toShort()
    }

    /**
     * Decodes a single μ-law byte to a 16-bit PCM sample.
     */
    private fun mulawDecode(mulaw: Byte): Short {
        // Complement the value (μ-law is stored inverted)
        val index = (mulaw.toInt() xor 0xFF) and 0xFF
        return MULAW_DECODE_TABLE[index]
    }

    /**
     * Encodes a 16-bit PCM sample to μ-law.
     */
    private fun mulawEncode(sample: Short): Byte {
        val MULAW_MAX = 0x1FFF
        val MULAW_BIAS = 0x84

        var pcmValue = sample.toInt()

        // Get the sign
        val sign = if (pcmValue < 0) 0x80 else 0
        if (pcmValue < 0) pcmValue = -pcmValue

        // Clip to max
        if (pcmValue > MULAW_MAX) pcmValue = MULAW_MAX

        // Add bias
        pcmValue += MULAW_BIAS

        // Find exponent and mantissa
        var exponent = 7
        var expMask = 0x4000
        while (exponent > 0 && (pcmValue and expMask) == 0) {
            exponent--
            expMask = expMask shr 1
        }

        val mantissa = (pcmValue shr (exponent + 3)) and 0x0F

        // Combine and complement
        val mulawByte = (sign or (exponent shl 4) or mantissa) xor 0xFF
        return mulawByte.toByte()
    }

    // ==================== Resampling ====================

    /**
     * Upsamples audio by 3x using linear interpolation.
     *
     * For each input sample, generates 3 output samples.
     * This is a simple interpolation - for higher quality, consider
     * using a proper polyphase filter.
     */
    private fun upsample3x(input: ShortArray): ShortArray {
        if (input.isEmpty()) return ShortArray(0)
        if (input.size == 1) return ShortArray(3) { input[0] }

        val output = ShortArray(input.size * 3)
        for (i in 0 until input.size - 1) {
            val s0 = input[i].toInt()
            val s1 = input[i + 1].toInt()

            output[i * 3] = s0.toShort()
            output[i * 3 + 1] = ((s0 * 2 + s1) / 3).toShort()
            output[i * 3 + 2] = ((s0 + s1 * 2) / 3).toShort()
        }
        // Last sample: repeat
        val last = input.last().toInt()
        output[output.size - 3] = last.toShort()
        output[output.size - 2] = last.toShort()
        output[output.size - 1] = last.toShort()

        return output
    }

    /**
     * Downsamples audio by 3x using averaging (simple low-pass filter).
     *
     * Takes every 3rd sample after averaging with neighbors.
     */
    private fun downsample3x(input: ShortArray): ShortArray {
        if (input.isEmpty()) return ShortArray(0)

        val outputSize = input.size / 3
        val output = ShortArray(outputSize)

        for (i in 0 until outputSize) {
            val idx = i * 3
            // Average 3 samples for basic low-pass filtering
            val sum = input[idx].toInt() +
                    (if (idx + 1 < input.size) input[idx + 1].toInt() else input[idx].toInt()) +
                    (if (idx + 2 < input.size) input[idx + 2].toInt() else input[idx].toInt())
            output[i] = (sum / 3).toShort()
        }

        return output
    }

    // ==================== Byte/Short Conversion ====================

    /**
     * Converts a short array to little-endian byte array.
     */
    private fun shortArrayToBytes(shorts: ShortArray): ByteArray {
        val bytes = ByteArray(shorts.size * 2)
        for (i in shorts.indices) {
            val s = shorts[i].toInt()
            bytes[i * 2] = (s and 0xFF).toByte()
            bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    /**
     * Converts a little-endian byte array to short array.
     */
    private fun bytesToShortArray(bytes: ByteArray): ShortArray {
        val shorts = ShortArray(bytes.size / 2)
        for (i in shorts.indices) {
            val low = bytes[i * 2].toInt() and 0xFF
            val high = bytes[i * 2 + 1].toInt()
            shorts[i] = ((high shl 8) or low).toShort()
        }
        return shorts
    }
}
