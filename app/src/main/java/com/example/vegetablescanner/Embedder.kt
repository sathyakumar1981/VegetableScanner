package com.example.vegetablescanner

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imageembedder.ImageEmbedder
import java.nio.ByteBuffer

/** Turns a camera frame into a float vector. Similar-looking objects give similar vectors. */
class Embedder(context: Context, modelBytes: ByteArray) {

    private val embedder: ImageEmbedder

    init {
        val buffer = ByteBuffer.allocateDirect(modelBytes.size)
        buffer.put(modelBytes)
        buffer.rewind()

        val options = ImageEmbedder.ImageEmbedderOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(buffer).build())
            .setRunningMode(RunningMode.IMAGE)
            .setL2Normalize(true)
            .build()
        embedder = ImageEmbedder.createFromOptions(context, options)
    }

    @Synchronized
    fun embed(bitmap: Bitmap): FloatArray {
        val result = embedder.embed(BitmapImageBuilder(bitmap).build())
        return result.embeddingResult().embeddings()[0].floatEmbedding()
    }

    fun close() = embedder.close()
}
