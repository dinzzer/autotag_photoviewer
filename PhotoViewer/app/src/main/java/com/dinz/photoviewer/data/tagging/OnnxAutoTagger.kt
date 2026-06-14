package com.dinz.photoviewer.data.tagging

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import com.dinz.photoviewer.data.Photo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer

/** One label in the tagger vocabulary, aligned by index with the model output. */
data class TagLabel(val name: String, val category: Int)

/**
 * On-device anime tagger backed by a wd-tagger style ONNX model (e.g. SmilingWolf wd-v1-4 / wd-v3),
 * run via ONNX Runtime Mobile.
 *
 * Default preprocessing follows the wd-v1-4 convention: pad to a white square, resize to the model
 * input size (typically 448), convert RGB→BGR, keep float values in 0–255, NHWC layout. The model
 * output is assumed to be per-tag probabilities (sigmoid already applied). Adjust [inputSize],
 * [bgr] and the thresholds to match a different model.
 *
 * @param labels vocabulary parsed from `selected_tags.csv`, index-aligned with the output.
 */
class OnnxAutoTagger(
    private val context: Context,
    modelFile: File,
    private val labels: List<TagLabel>,
    // Defaults tuned for SmilingWolf wd-swinv2-tagger-v3 (and the rest of the wd-v3 family):
    // 448px input, square white padding, RGB→BGR, float 0–255, NHWC, sigmoid output.
    private val inputSize: Int = 448,
    private val bgr: Boolean = true,
    private val generalThreshold: Float = 0.35f,
    private val characterThreshold: Float = 0.85f,
    private val maxTags: Int = 32,
    override val engineName: String = "WD Tagger v3 (ONNX)",
) : AutoTagger, AutoCloseable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession =
        env.createSession(modelFile.absolutePath, OrtSession.SessionOptions())
    private val inputName: String = session.inputNames.iterator().next()
    private val outputName: String = session.outputNames.iterator().next()

    override suspend fun tagsFor(photo: Photo): List<Tag> = withContext(Dispatchers.Default) {
        val bitmap = loadBitmap(photo) ?: return@withContext emptyList()
        val square = padToSquare(bitmap)
        val resized = Bitmap.createScaledBitmap(square, inputSize, inputSize, true)
        val input = toFloatBuffer(resized)

        val results = ArrayList<Tag>()
        OnnxTensor.createTensor(
            env,
            input,
            longArrayOf(1, inputSize.toLong(), inputSize.toLong(), 3),
        ).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { output ->
                val raw = output.get(outputName).orElse(null)?.value ?: return@use
                @Suppress("UNCHECKED_CAST")
                val probs: FloatArray = when (raw) {
                    is Array<*> -> (raw[0] as FloatArray)
                    is FloatArray -> raw
                    else -> return@use
                }
                val count = minOf(probs.size, labels.size)
                for (i in 0 until count) {
                    val label = labels[i]
                    val p = probs[i]
                    val keep = when (label.category) {
                        9 -> false // skip rating tags
                        4 -> p >= characterThreshold
                        else -> p >= generalThreshold
                    }
                    if (keep) results += Tag(label.name, p)
                }
            }
        }
        results.sortedByDescending { it.confidence }.take(maxTags)
    }

    private fun loadBitmap(photo: Photo): Bitmap? = runCatching {
        context.contentResolver.openInputStream(photo.uri)?.use { stream ->
            // First decode bounds to choose a downsample factor (memory-friendly).
            val bytes = stream.readBytes()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, inputSize)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        }
    }.getOrNull()

    private fun sampleSizeFor(w: Int, h: Int, target: Int): Int {
        var sample = 1
        var halfW = w / 2
        var halfH = h / 2
        while (halfW >= target && halfH >= target) {
            sample *= 2
            halfW /= 2
            halfH /= 2
        }
        return sample.coerceAtLeast(1)
    }

    private fun padToSquare(src: Bitmap): Bitmap {
        val side = maxOf(src.width, src.height)
        if (src.width == src.height) return src
        val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        val left = (side - src.width) / 2
        val top = (side - src.height) / 2
        canvas.drawBitmap(src, null, Rect(left, top, left + src.width, top + src.height), null)
        return out
    }

    private fun toFloatBuffer(bitmap: Bitmap): FloatBuffer {
        val n = inputSize * inputSize
        val pixels = IntArray(n)
        bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        val buffer = FloatBuffer.allocate(n * 3)
        for (i in 0 until n) {
            val px = pixels[i]
            val r = ((px shr 16) and 0xFF).toFloat()
            val g = ((px shr 8) and 0xFF).toFloat()
            val b = (px and 0xFF).toFloat()
            if (bgr) {
                buffer.put(b); buffer.put(g); buffer.put(r)
            } else {
                buffer.put(r); buffer.put(g); buffer.put(b)
            }
        }
        buffer.rewind()
        return buffer
    }

    override fun close() {
        runCatching { session.close() }
    }

    companion object {
        /** Parses `selected_tags.csv` (`tag_id,name,category,count`) into index-aligned labels. */
        fun parseLabels(csv: File): List<TagLabel> {
            val lines = csv.readLines()
            if (lines.isEmpty()) return emptyList()
            val start = if (lines.first().startsWith("tag_id")) 1 else 0
            return lines.drop(start).mapNotNull { line ->
                val cols = line.split(',')
                if (cols.size < 3) return@mapNotNull null
                val name = cols[1].trim()
                val category = cols[2].trim().toIntOrNull() ?: 0
                TagLabel(name, category)
            }
        }
    }
}
