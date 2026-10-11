package com.example.vegetablescanner

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Finds the image-embedding model. Order:
 *  1. app/src/main/assets/embedder.tflite (if you bundled it yourself)
 *  2. a copy previously downloaded to the app's private storage
 *  3. download it once from the public MediaPipe model bucket
 */
object ModelProvider {

    private const val ASSET_NAME = "embedder.tflite"
    private const val LOCAL_NAME = "embedder.tflite"
    private const val MODEL_URL =
        "https://storage.googleapis.com/mediapipe-models/image_embedder/" +
            "mobilenet_v3_small/float32/latest/mobilenet_v3_small.tflite"

    /** Returns the model bytes, or throws if it can't be found or downloaded. */
    fun load(context: Context, onStatus: (String) -> Unit): ByteArray {
        // 1. bundled asset
        try {
            return context.assets.open(ASSET_NAME).use { it.readBytes() }
        } catch (_: Exception) {
        }

        // 2. already downloaded
        val local = File(context.filesDir, LOCAL_NAME)
        if (local.exists() && local.length() > 100_000) return local.readBytes()

        // 3. download once
        onStatus(context.getString(R.string.downloading_model))
        val tmp = File(context.filesDir, "$LOCAL_NAME.part")
        val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        try {
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        } finally {
            conn.disconnect()
        }
        if (tmp.length() < 100_000) {
            tmp.delete()
            error("Downloaded file too small")
        }
        tmp.renameTo(local)
        return local.readBytes()
    }
}
