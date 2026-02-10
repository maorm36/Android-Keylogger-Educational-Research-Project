package com.android.myapp.ml

import android.content.Context
import android.util.Log
import com.android.myapp.data.CapturedEvent
import com.android.myapp.data.SensitivityClassification
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max

/**
 * Sensitive data detector powered by a 5-class TFLite model trained by model_new.py:
 *   0 = normal
 *   1 = password
 *   2 = email
 *   3 = credit_card
 *   4 = phone_il
 */
class SensitiveDataDetector(private val context: Context) {

    companion object {
        private const val TAG = "SensitiveDetector"
        private const val MODEL_FILE = "sensitive_data_detector.tflite"

        // MUST match Python label ids
        private const val LBL_NORMAL = 0
        private const val LBL_PASSWORD = 1
        private const val LBL_EMAIL = 2
        private const val LBL_CREDIT_CARD = 3
        private const val LBL_PHONE_IL = 4

        private val LABELS = arrayOf("normal", "password", "email", "credit_card", "phone_il")

        // Required priority order:
        // credit_card -> password -> email -> phone_il -> normal
        private val PRIORITY = intArrayOf(LBL_CREDIT_CARD, LBL_PASSWORD, LBL_EMAIL, LBL_PHONE_IL, LBL_NORMAL)

        // Probability thresholds (note to myself: tune if needed to reduce false positives)
        private val THRESH = floatArrayOf(
            0.0f,   // normal (unused)
            0.60f,  // password
            0.70f,  // email
            0.70f,  // credit_card
            0.65f   // phone_il (a bit lower helps recall for 10-digit mobiles)
        )
    }

    private var interpreter: Interpreter? = null
    private var outputClasses: Int = 5
    private var inputLen: Int = 96 // will be overwritten from model input tensor

    init {
        loadModel()
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }

    /**
     * MAIN ENTRY:
     * Scans candidate tokens and returns the best match by PRIORITY.
     */
    fun classifySensitivity(text: String, event: CapturedEvent): SensitivityClassification {
        val tfl = interpreter ?: return SensitivityClassification(false, null, 0f)

        val candidates = extractCandidates(text)
        var best: Detection? = null

        for (rawToken in candidates) {
            if (rawToken.isBlank()) continue

            val probs = predictProbs(tfl, rawToken)

            if (event.isPassword) {
                probs[LBL_PASSWORD] = (probs[LBL_PASSWORD] + 0.10f).coerceAtMost(1.0f)
            }

            val det = pickWithPriority(rawToken, probs)

            // Debug: uncomment if needed
            // Log.d(TAG, "token='$rawToken' -> ${det.type} (${String.format("%.3f", det.confidence)}) probs=${probs.contentToString()}")

            if (det.type != "normal") {
                best = betterOf(best, det)
            }
        }

        return if (best != null) {
            // If your app expects "phone" instead of "phone_il"
            val typeForApp = if (best.type == "phone_il") "phone" else best.type
            SensitivityClassification(true, typeForApp, best.confidence)
        } else {
            SensitivityClassification(false, null, 0f)
        }
    }

    // -----------------------------
    // Model I/O
    // -----------------------------
    private fun loadModel() {
        try {
            val buf = loadModelFile(MODEL_FILE)
            interpreter = Interpreter(buf, Interpreter.Options()).also { tfl ->
                val inTensor = tfl.getInputTensor(0)
                val outTensor = tfl.getOutputTensor(0)

                Log.d(TAG, "Input shape=${inTensor.shape().contentToString()} type=${inTensor.dataType()}")
                Log.d(TAG, "Output shape=${outTensor.shape().contentToString()} type=${outTensor.dataType()}")

                require(inTensor.dataType() == DataType.INT32) {
                    "Expected INT32 input. Your model must be trained with int32 input."
                }

                val inShape = inTensor.shape()
                require(inShape.size == 2) { "Expected [batch, len] input shape, got=${inShape.contentToString()}" }
                inputLen = inShape[1]

                val outShape = outTensor.shape()
                outputClasses = outShape.lastOrNull() ?: 5
                require(outputClasses >= 5) {
                    "Expected >=5 output classes, got $outputClasses. Did you deploy the new 5-class model?"
                }

                Log.d(TAG, "Using inputLen=$inputLen outputClasses=$outputClasses")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load TFLite model", e)
            interpreter = null
        }
    }

    private fun loadModelFile(modelName: String): MappedByteBuffer {
        val fd = context.assets.openFd(modelName)
        FileInputStream(fd.fileDescriptor).use { input ->
            val channel = input.channel
            return channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
    }

    private fun predictProbs(tfl: Interpreter, rawText: String): FloatArray {
        val input = Array(1) { IntArray(inputLen) }
        encodeToIds(rawText, input[0])

        val output = Array(1) { FloatArray(outputClasses) }
        tfl.run(input, output)

        return output[0]
    }

    /**
     * Must match Python encoding in model_new.py:
     * 1) normalize digits => 'D', collapse whitespace
     * 2) UTF-8 bytes
     * 3) ids[i] = byte + 1, pad with 0
     */
    private fun encodeToIds(rawText: String, out: IntArray) {
        out.fill(0)

        val norm = normalizeForModel(rawText)
        val bytes = norm.toByteArray(Charsets.UTF_8)
        val n = minOf(bytes.size, out.size)

        for (i in 0 until n) {
            out[i] = (bytes[i].toInt() and 0xFF) + 1
        }
    }

    private fun normalizeForModel(s: String): String {
        // Equivalent to Python:
        //   s = "".join(("D" if ch.isdigit() else ch) for ch in s)
        //   s = " ".join(s.split())
        val sb = StringBuilder(s.length)
        var lastWasSpace = true // to trim leading spaces

        for (ch0 in s) {
            var ch = ch0
            ch = if (ch in '0'..'9') 'D' else ch
            if (ch == '\n' || ch == '\r' || ch == '\t') ch = ' '

            if (ch == ' ') {
                if (!lastWasSpace) {
                    sb.append(' ')
                    lastWasSpace = true
                }
            } else {
                sb.append(ch)
                lastWasSpace = false
            }
        }

        // Trim trailing space if any
        if (sb.isNotEmpty() && sb.last() == ' ') sb.setLength(sb.length - 1)
        return sb.toString()
    }

    // -----------------------------------------------------------------------------
    // Priority selection (applied sanity constraints to avoid phone -> credit_card)
    // -----------------------------------------------------------------------------
    private fun pickWithPriority(rawToken: String, probs: FloatArray): Detection {
        for (idx in PRIORITY) {
            val p = probs.getOrElse(idx) { 0f }
            val th = THRESH.getOrElse(idx) { 0.7f }
            if (idx == LBL_NORMAL) continue
            if (p < th) continue

            if (idx == LBL_CREDIT_CARD && !isPossibleCreditCardToken(rawToken)) continue
            if (idx == LBL_PHONE_IL && !isPossibleIlPhoneToken(rawToken)) continue

            return Detection(LABELS[idx], p)
        }

        // Fallback to constrained argmax
        var bestIdx = LBL_NORMAL
        var bestP = -1f

        val limit = minOf(probs.size, LABELS.size)
        for (i in 0 until limit) {
            if (i == LBL_CREDIT_CARD && !isPossibleCreditCardToken(rawToken)) continue
            if (i == LBL_PHONE_IL && !isPossibleIlPhoneToken(rawToken)) continue

            if (probs[i] > bestP) {
                bestP = probs[i]
                bestIdx = i
            }
        }

        return Detection(LABELS[bestIdx], max(bestP, 0f))
    }

    private fun isPossibleCreditCardToken(token: String): Boolean {
        // CC lengths are 13..19 digits (after removing separators).
        // Treat 'D' as a digit placeholder too (helps when you test with 054DDDDDDD).
        val n = countDigitsOrD(token)
        return n in 13..19
    }

    private fun isPossibleIlPhoneToken(token: String): Boolean {
        // Common IL phone number formats:
        //   Local:   0XXXXXXXXX (9 digits) or 05XXXXXXXX (10 digits)
        //   Intl:    +972XXXXXXXXX / 972XXXXXXXXX (11-12 digits total, depending on area/mobile)
        val n = countDigitsOrD(token)
        if (n !in 9..12) return false

        val t = token.trim()
        return t.startsWith("0") || t.startsWith("+972") || t.startsWith("972") ||
                // Also accept pure digit/D tokens of the right length (no punctuation)
                t.all { it.isDigit() || it == 'D' }
    }

    private fun countDigitsOrD(s: String): Int {
        var c = 0
        for (ch in s) {
            if (ch.isDigit() || ch == 'D') c++
        }
        return c
    }

    private fun betterOf(a: Detection?, b: Detection): Detection {
        if (a == null) return b

        val ra = priorityRank(a.type)
        val rb = priorityRank(b.type)

        return when {
            rb < ra -> b
            rb > ra -> a
            else -> if (b.confidence > a.confidence) b else a
        }
    }

    private fun priorityRank(type: String): Int {
        val idx = LABELS.indexOf(type)
        if (idx < 0) return Int.MAX_VALUE
        val rank = PRIORITY.indexOf(idx)
        return if (rank < 0) Int.MAX_VALUE else rank
    }

    // -----------------------------
    // Candidate extraction (no regex)
    // -----------------------------
    private fun extractCandidates(text: String): List<String> {
        val out = LinkedHashSet<String>()

        val parts = text.split(' ', '\n', '\r', '\t')
        for (p0 in parts) {
            val p = p0.trim()
            if (p.isEmpty()) continue

            val trimmed = trimEdgePunct(p)
            if (trimmed.length in 2..80) out.add(trimmed)

            // Also extract digit/D runs from inside tokens, e.g. "call:0541234567"
            for (run in extractDigitRuns(p)) {
                if (run.length in 2..80) out.add(run)
            }
        }

        return out.toList()
    }

    private fun trimEdgePunct(s: String): String {
        var start = 0
        var end = s.length

        fun isEdgePunct(ch: Char): Boolean {
            return ch == '.' || ch == ',' || ch == ';' || ch == ':' ||
                    ch == '(' || ch == ')' || ch == '[' || ch == ']' ||
                    ch == '{' || ch == '}' || ch == '<' || ch == '>' ||
                    ch == '"' || ch == '\'' || ch == '!' || ch == '?' ||
                    ch == '،' || ch == '؛'
        }

        while (start < end && isEdgePunct(s[start])) start++
        while (end > start && isEdgePunct(s[end - 1])) end--

        return if (start == 0 && end == s.length) s else s.substring(start, end)
    }

    private fun extractDigitRuns(s: String): List<String> {
        val runs = ArrayList<String>(2)
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            val isDigitLike = ch.isDigit() || ch == 'D'
            if (!isDigitLike) {
                i++
                continue
            }

            val start = i
            i++
            while (i < s.length && (s[i].isDigit() || s[i] == 'D')) i++
            val len = i - start

            // Keep only meaningful runs
            if (len >= 8) {
                runs.add(s.substring(start, i))
            }
        }
        return runs
    }

    private data class Detection(val type: String, val confidence: Float)
}