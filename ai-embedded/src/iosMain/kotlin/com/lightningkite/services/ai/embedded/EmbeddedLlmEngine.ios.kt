@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.lightningkite.services.ai.embedded

import kotlinx.cinterop.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import platform.CoreML.*
import platform.Foundation.*

/**
 * iOS implementation using Core ML for on-device LLM inference.
 *
 * Loads a compiled Core ML model (.mlmodelc) and runs autoregressive text generation.
 *
 * Core ML LLM models (exported via coremltools or similar) typically have:
 * - Input: `inputIds` (token IDs as MLMultiArray), plus optional KV-cache arrays
 * - Output: `logits` (next-token probabilities as MLMultiArray), plus optional updated KV-cache
 *
 * The tokenizer must be provided separately alongside the model. This implementation
 * expects a `tokenizer.json` (HuggingFace-format) in the same directory as the model.
 *
 * Model path in [EmbeddedEngineConfig.modelPath] should point to either:
 * - A compiled `.mlmodelc` directory
 * - A `.mlpackage` bundle
 *
 * **Limitations:**
 * - Tokenization uses a simple character-level fallback if no tokenizer file is found.
 *   For production use, provide a proper tokenizer.json alongside the model.
 * - KV-cache handling is model-specific; this implementation supports stateless
 *   (full-context) prediction. Stateful KV-cache models need per-model adaptation.
 */
internal actual class EmbeddedLlmEngine actual constructor(
    private val config: EmbeddedEngineConfig,
) {
    private var model: MLModel? = null
    private var tokenizer: SimpleTokenizer? = null

    actual suspend fun loadModel() = withContext(Dispatchers.Default) {
        val path = config.modelPath
            ?: throw IllegalStateException("modelPath is required for iOS embedded inference")

        val modelUrl = NSURL.fileURLWithPath(path)

        // Load the compiled Core ML model
        val mlConfig = MLModelConfiguration().apply {
            computeUnits = MLComputeUnitsAll
        }

        val loadedModel = memScoped {
            val errorPtr = alloc<ObjCObjectVar<NSError?>>()
            val result = MLModel.modelWithContentsOfURL(modelUrl, configuration = mlConfig, error = errorPtr.ptr)
            val error = errorPtr.value
            if (result == null || error != null) {
                throw IllegalStateException(
                    "Failed to load Core ML model at $path: ${error?.localizedDescription ?: "unknown error"}"
                )
            }
            result
        }

        model = loadedModel

        // Attempt to load a tokenizer from the model's parent directory.
        // Look for tokenizer.json (HuggingFace format) next to the model.
        val modelDir = modelUrl.URLByDeletingLastPathComponent
        val tokenizerUrl = modelDir?.URLByAppendingPathComponent("tokenizer.json")
        tokenizer = if (tokenizerUrl != null) {
            loadTokenizerOrNull(tokenizerUrl)
        } else {
            null
        }

        if (tokenizer == null) {
            // Fall back to a naive character-level tokenizer.
            // This is NOT suitable for real inference -- just prevents crashes during development.
            tokenizer = CharacterTokenizer()
        }
    }

    actual suspend fun unloadModel() {
        model = null
        tokenizer = null
    }

    actual fun isModelLoaded(): Boolean = model != null

    actual fun generate(
        text: String,
        maxTokens: Int,
        temperature: Double,
        stopSequences: List<String>,
    ): Flow<String> = callbackFlow {
        val currentModel = model ?: throw IllegalStateException("Model not loaded. Call loadModel() first.")
        val tok = tokenizer ?: throw IllegalStateException("Tokenizer not initialized")

        withContext(Dispatchers.Default) {
            val inputIds = tok.encode(text).toMutableList()
            val generated = StringBuilder()

            for (step in 0 until maxTokens) {
                // Build the input feature provider with the current token sequence
                val inputArray = createMLMultiArray(inputIds)
                    ?: throw IllegalStateException("Failed to create MLMultiArray for input")

                val featureProvider = createFeatureProvider(inputArray, inputIds.size)
                    ?: throw IllegalStateException("Failed to create feature provider")

                // Run prediction
                val output = memScoped {
                    val errorPtr = alloc<ObjCObjectVar<NSError?>>()
                    val result = currentModel.predictionFromFeatures(featureProvider, error = errorPtr.ptr)
                    val error = errorPtr.value
                    if (result == null || error != null) {
                        throw IllegalStateException(
                            "Core ML prediction failed: ${error?.localizedDescription ?: "unknown error"}"
                        )
                    }
                    result
                }

                // Extract logits from output
                val logits = extractLogits(output, inputIds.size)
                    ?: throw IllegalStateException(
                        "Could not extract logits from model output. " +
                            "Available features: ${describeFeatures(output)}"
                    )

                // Sample next token
                val nextToken = sampleToken(logits, temperature)
                inputIds.add(nextToken)

                // Decode the new token to text
                val tokenText = tok.decode(listOf(nextToken))
                generated.append(tokenText)

                trySend(tokenText)

                // Check for EOS
                if (tok.isEndOfSequence(nextToken)) break

                // Check for stop sequences
                val shouldStop = stopSequences.any { seq ->
                    generated.endsWith(seq)
                }
                if (shouldStop) break
            }
        }

        close()
        awaitClose()
    }

    // -- Core ML helpers --

    /**
     * Creates an MLMultiArray containing the input token IDs.
     * Shape: [1, sequenceLength] (batch size 1).
     */
    private fun createMLMultiArray(tokenIds: List<Int>): MLMultiArray? = memScoped {
        val shape = listOf(
            NSNumber.numberWithInt(1),
            NSNumber.numberWithInt(tokenIds.size),
        )
        val errorPtr = alloc<ObjCObjectVar<NSError?>>()
        val array = MLMultiArray.create(
            shape = shape,
            dataType = MLMultiArrayDataTypeInt32,
            error = errorPtr.ptr,
        )
        if (array == null || errorPtr.value != null) return@memScoped null

        // Fill in the token IDs
        for (i in tokenIds.indices) {
            array.setObject(NSNumber.numberWithInt(tokenIds[i]), atIndexedSubscript = i.toLong())
        }
        array
    }

    /**
     * Creates the input feature provider for the model.
     *
     * Most Core ML LLM models expect at minimum an "inputIds" or "input_ids" feature.
     * Some also expect "attention_mask" or "position_ids".
     *
     * This inspects the model's input description to provide the right features.
     */
    private fun createFeatureProvider(inputArray: MLMultiArray, seqLen: Int): MLDictionaryFeatureProvider? = memScoped {
        val currentModel = model ?: return@memScoped null
        val inputDesc = currentModel.modelDescription.inputDescriptionsByName

        val features = mutableMapOf<Any?, Any>()

        // Determine the input ID feature name (common variants)
        val inputIdKey = findFeatureName(inputDesc, "inputIds", "input_ids", "token_ids", "tokens")
        if (inputIdKey != null) {
            features[inputIdKey] = MLFeatureValue.featureValueWithMultiArray(inputArray)
        } else {
            // If we can't find a known input name, use the first multi-array input
            val firstArrayInput = findFirstMultiArrayInput(inputDesc)
            if (firstArrayInput != null) {
                features[firstArrayInput] = MLFeatureValue.featureValueWithMultiArray(inputArray)
            } else {
                return@memScoped null
            }
        }

        // Add attention mask if the model expects one (all 1s = attend to everything)
        val maskKey = findFeatureName(inputDesc, "attentionMask", "attention_mask", "causalMask", "causal_mask")
        if (maskKey != null) {
            val mask = createAttentionMask(seqLen)
            if (mask != null) {
                features[maskKey] = MLFeatureValue.featureValueWithMultiArray(mask)
            }
        }

        val errorPtr = alloc<ObjCObjectVar<NSError?>>()
        @Suppress("UNCHECKED_CAST")
        val provider = MLDictionaryFeatureProvider(
            dictionary = features as Map<Any?, *>,
            error = errorPtr.ptr,
        )
        if (errorPtr.value != null) return@memScoped null
        provider
    }

    /**
     * Creates a simple attention mask of all 1s (attend to all tokens).
     */
    private fun createAttentionMask(seqLen: Int): MLMultiArray? = memScoped {
        val shape = listOf(
            NSNumber.numberWithInt(1),
            NSNumber.numberWithInt(seqLen),
        )
        val errorPtr = alloc<ObjCObjectVar<NSError?>>()
        val mask = MLMultiArray.create(
            shape = shape,
            dataType = MLMultiArrayDataTypeInt32,
            error = errorPtr.ptr,
        )
        if (mask == null || errorPtr.value != null) return@memScoped null

        for (i in 0 until seqLen) {
            mask.setObject(NSNumber.numberWithInt(1), atIndexedSubscript = i.toLong())
        }
        mask
    }

    /**
     * Extracts the logits array from the model's prediction output.
     *
     * Logits are typically shaped [1, seqLen, vocabSize]. We want the last position's
     * logits: [vocabSize] floats representing the probability distribution for the next token.
     *
     * Uses the MLMultiArray strides to compute the flat offset for the last sequence position,
     * then reads values via subscript. This avoids creating intermediate NSNumber objects
     * per vocabulary entry where possible.
     */
    private fun extractLogits(output: MLFeatureProviderProtocol, seqLen: Int): FloatArray? {
        val logitsKey = findOutputFeatureName(output, "logits", "output_logits", "token_logits", "output")
            ?: return null

        val logitsValue = output.featureValueForName(logitsKey) ?: return null
        val logitsArray = logitsValue.multiArrayValue ?: return null

        val shape = logitsArray.shape
        if (shape.isEmpty()) return null

        val vocabSize = (shape.last() as NSNumber).intValue
        val lastPos = seqLen - 1

        // Compute the flat base offset for the last position's logits using strides.
        // For shape [1, seqLen, vocabSize] with strides [s0, s1, s2]:
        //   offset = 0*s0 + lastPos*s1, then iterate v*s2 for each vocab entry.
        val strides = logitsArray.strides
        val baseOffset: Long = when (shape.size) {
            3 -> (strides[1] as NSNumber).longValue * lastPos
            2 -> (strides[0] as NSNumber).longValue * lastPos
            else -> 0L
        }
        val vocabStride: Long = when {
            strides.isNotEmpty() -> (strides.last() as NSNumber).longValue
            else -> 1L
        }

        val result = FloatArray(vocabSize)
        for (v in 0 until vocabSize) {
            val flatIndex = baseOffset + v * vocabStride
            val num = logitsArray.objectAtIndexedSubscript(flatIndex) as? NSNumber
            result[v] = num?.floatValue ?: 0f
        }
        return result
    }

    /**
     * Samples a token from logits using temperature-scaled softmax.
     * Temperature 0.0 = greedy (argmax). Higher = more random.
     */
    private fun sampleToken(logits: FloatArray, temperature: Double): Int {
        if (temperature < 1e-7) {
            // Greedy: pick the token with highest logit
            return logits.indices.maxByOrNull { logits[it] } ?: 0
        }

        // Apply temperature scaling
        val scaled = FloatArray(logits.size) { (logits[it] / temperature).toFloat() }

        // Softmax
        val maxVal = scaled.max()
        val exps = FloatArray(scaled.size) { kotlin.math.exp((scaled[it] - maxVal).toDouble()).toFloat() }
        val sum = exps.sum()
        val probs = FloatArray(exps.size) { exps[it] / sum }

        // Sample from the distribution
        val r = kotlin.random.Random.nextFloat()
        var cumulative = 0f
        for (i in probs.indices) {
            cumulative += probs[i]
            if (r <= cumulative) return i
        }
        return probs.size - 1
    }

    // -- Feature name helpers --

    private fun findFeatureName(descriptions: Map<Any?, *>, vararg names: String): String? {
        for (name in names) {
            if (descriptions.containsKey(name)) return name
        }
        return null
    }

    private fun findFirstMultiArrayInput(descriptions: Map<Any?, *>): String? {
        for ((key, value) in descriptions) {
            val desc = value as? MLFeatureDescription ?: continue
            if (desc.type == MLFeatureTypeMultiArray) return key as? String
        }
        return null
    }

    private fun findOutputFeatureName(output: MLFeatureProviderProtocol, vararg names: String): String? {
        val featureNames = output.featureNames
        for (name in names) {
            if (featureNames.contains(name)) return name
        }
        // Fallback: return the first available multi-array feature
        for (name in featureNames) {
            val nameStr = name as? String ?: continue
            val value = output.featureValueForName(nameStr)
            if (value?.multiArrayValue != null) return nameStr
        }
        return null
    }

    private fun describeFeatures(output: MLFeatureProviderProtocol): String {
        return output.featureNames.joinToString(", ") { it.toString() }
    }
}

// -- Tokenizer --

/**
 * Minimal tokenizer interface for encoding text to token IDs and decoding back.
 */
internal interface SimpleTokenizer {
    fun encode(text: String): List<Int>
    fun decode(tokenIds: List<Int>): String
    fun isEndOfSequence(tokenId: Int): Boolean
}

/**
 * Attempts to load a HuggingFace-format tokenizer.json file.
 *
 * TODO: Implement full BPE/SentencePiece tokenizer parsing. HuggingFace tokenizer.json
 * files contain the vocabulary, merge rules, and special tokens. A full implementation
 * would parse the "model" section for BPE merges and the "added_tokens" for special tokens.
 * Consider using a native tokenizer library (e.g., building tokenizers-cpp as a static lib
 * and linking via cinterop) for production use.
 *
 * For now, returns null so the engine falls back to [CharacterTokenizer].
 */
private fun loadTokenizerOrNull(url: NSURL): SimpleTokenizer? {
    val data = NSData.dataWithContentsOfURL(url) ?: return null
    val jsonString = NSString.create(data = data, encoding = NSUTF8StringEncoding) ?: return null

    // Parse the JSON to extract vocabulary
    val jsonData = jsonString.dataUsingEncoding(NSUTF8StringEncoding) ?: return null

    @Suppress("UNCHECKED_CAST")
    val parsed = memScoped {
        val errorPtr = alloc<ObjCObjectVar<NSError?>>()
        val result = NSJSONSerialization.JSONObjectWithData(
            jsonData,
            options = 0u,
            error = errorPtr.ptr,
        )
        if (errorPtr.value != null) return null
        result as? Map<Any?, Any?>
    } ?: return null

    // Extract vocabulary from the "model" -> "vocab" path
    @Suppress("UNCHECKED_CAST")
    val modelSection = parsed["model"] as? Map<Any?, Any?> ?: return null
    @Suppress("UNCHECKED_CAST")
    val vocab = modelSection["vocab"] as? Map<Any?, Any?> ?: return null

    // Build token <-> id mappings
    val tokenToId = mutableMapOf<String, Int>()
    val idToToken = mutableMapOf<Int, String>()
    for ((token, id) in vocab) {
        val tokenStr = token as? String ?: continue
        val tokenId = (id as? NSNumber)?.intValue ?: continue
        tokenToId[tokenStr] = tokenId
        idToToken[tokenId] = tokenStr
    }

    // Extract special tokens for EOS detection
    @Suppress("UNCHECKED_CAST")
    val addedTokens = parsed["added_tokens"] as? List<Any?> ?: emptyList<Any?>()
    val eosIds = mutableSetOf<Int>()
    for (entry in addedTokens) {
        @Suppress("UNCHECKED_CAST")
        val tokenMap = entry as? Map<Any?, Any?> ?: continue
        val special = tokenMap["special"] as? Boolean ?: false
        val content = tokenMap["content"] as? String ?: continue
        if (special && (content.contains("eos") || content.contains("end"))) {
            val id = tokenToId[content]
            if (id != null) eosIds.add(id)
        }
    }

    if (tokenToId.isEmpty()) return null

    return VocabTokenizer(tokenToId, idToToken, eosIds)
}

/**
 * Greedy longest-match tokenizer using a vocabulary table.
 *
 * This is a simplified tokenizer that does greedy longest-prefix matching against
 * the vocabulary. It does NOT implement BPE merges, which means tokenization may
 * differ from the model's training tokenizer. For accurate results, use a proper
 * BPE implementation or native tokenizer library.
 */
internal class VocabTokenizer(
    private val tokenToId: Map<String, Int>,
    private val idToToken: Map<Int, String>,
    private val eosTokenIds: Set<Int>,
) : SimpleTokenizer {

    override fun encode(text: String): List<Int> {
        val tokens = mutableListOf<Int>()
        var i = 0
        while (i < text.length) {
            // Greedy longest match
            var bestLen = 0
            var bestId = -1
            val maxLen = minOf(text.length - i, 32) // cap search length
            for (len in maxLen downTo 1) {
                val candidate = text.substring(i, i + len)
                val id = tokenToId[candidate]
                if (id != null) {
                    bestLen = len
                    bestId = id
                    break
                }
            }
            if (bestId >= 0) {
                tokens.add(bestId)
                i += bestLen
            } else {
                // Unknown character: try single-char lookup, then skip
                val charId = tokenToId[text[i].toString()]
                if (charId != null) tokens.add(charId)
                // else: skip unknown character (lossy)
                i++
            }
        }
        return tokens
    }

    override fun decode(tokenIds: List<Int>): String = buildString {
        for (id in tokenIds) {
            val token = idToToken[id] ?: continue
            // HuggingFace tokenizers use special unicode char for space prefix
            append(token.replace('\u2581', ' '))
        }
    }

    override fun isEndOfSequence(tokenId: Int): Boolean = tokenId in eosTokenIds
}

/**
 * Fallback character-level tokenizer. Maps each character to its Unicode code point.
 *
 * This produces very long token sequences and will not match any model's expected
 * tokenization. It exists only to allow the engine to load and run without crashing
 * during development when no proper tokenizer is available.
 */
internal class CharacterTokenizer : SimpleTokenizer {
    override fun encode(text: String): List<Int> = text.map { it.code }
    override fun decode(tokenIds: List<Int>): String = tokenIds.map { Char(it) }.joinToString("")
    override fun isEndOfSequence(tokenId: Int): Boolean = false
}
