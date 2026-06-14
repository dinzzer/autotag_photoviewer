package com.dinz.photoviewer.data.tagging

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Chooses the tagging backend at runtime.
 *
 * The wd-tagger ONNX model is large and is NOT bundled in the repository. The factory looks for
 * `model.onnx` + `selected_tags.csv` in the app's private files dir first (so they can be pushed
 * with `adb push` without rebuilding), then in the APK `assets/` (copied out on first run). If
 * neither is found, it falls back to [StubAutoTagger] so the app stays fully functional.
 *
 * Place the files at one of:
 *   - `/data/data/com.dinz.photoviewer/files/model.onnx` (+ `selected_tags.csv`), or
 *   - `app/src/main/assets/model.onnx` (+ `selected_tags.csv`) before building.
 */
object TaggerFactory {

    private const val TAG = "TaggerFactory"
    const val MODEL_NAME = "model.onnx"
    const val LABELS_NAME = "selected_tags.csv"

    fun create(context: Context): AutoTagger {
        val modelFile = resolveAsset(context, MODEL_NAME)
        val labelsFile = resolveAsset(context, LABELS_NAME)
        if (modelFile == null || labelsFile == null) {
            Log.i(TAG, "Tagger model not found; using StubAutoTagger.")
            return StubAutoTagger()
        }
        return runCatching {
            val labels = OnnxAutoTagger.parseLabels(labelsFile)
            if (labels.isEmpty()) error("empty label vocabulary")
            OnnxAutoTagger(context, modelFile, labels)
        }.getOrElse { t ->
            Log.w(TAG, "Failed to init OnnxAutoTagger, falling back to stub.", t)
            StubAutoTagger()
        }
    }

    /** Returns a usable file in filesDir, copying from assets if necessary; null if unavailable. */
    private fun resolveAsset(context: Context, name: String): File? {
        val local = File(context.filesDir, name)
        if (local.exists() && local.length() > 0) return local
        return runCatching {
            context.assets.open(name).use { input ->
                local.outputStream().use { output -> input.copyTo(output) }
            }
            local.takeIf { it.exists() && it.length() > 0 }
        }.getOrNull()
    }
}
