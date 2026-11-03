package io.github.nath_goa.usugami.ocr

import android.content.res.AssetManager
import android.graphics.Bitmap
import java.io.Closeable
import java.io.IOException

/**
 * A neural network that Tencent's ncnn runs on the CPU, loaded from the app's assets
 * (libusugami_ocr.so, built from third_party/ncnn by src/main/cpp/CMakeLists.txt). Close it to free
 * its memory. Calls are serialized, so closing waits for a run in progress.
 */
class NcnnNet private constructor(private var handle: Long) : Closeable {

    /**
     * How a picture becomes the network's input: resized to [width] × [height], padded with
     * [padValue] by [padRight] and [padBottom] pixels, in BGR channel order, then
     * (value - [mean]) × [norm] for each channel (values 0..255 before that).
     */
    class Input(
        val image: Bitmap,
        val width: Int,
        val height: Int,
        val mean: FloatArray,
        val norm: FloatArray,
        val padRight: Int = 0,
        val padBottom: Int = 0,
        val padValue: Float = 0f,
    )

    /** A tensor of [channels] × [height] × [width] values, row by row. */
    class Output<T>(val values: T, val channels: Int, val height: Int, val width: Int)

    /**
     * Runs the network.
     * @throws IllegalArgumentException for an unusable input (wrong bitmap format, sizes out of range)
     * @throws IllegalStateException when the network fails or has been closed
     * @throws OutOfMemoryError when there isn't enough memory
     */
    @Synchronized
    fun run(input: Input): Output<FloatArray> {
        val shape = IntArray(3)
        val values = NcnnNative.runFloats(open(), input.image, input.width, input.height, input.padRight, input.padBottom, input.padValue, input.mean, input.norm, shape)
        return Output(values, shape[0], shape[1], shape[2])
    }

    /** [run] for a network whose outputs are probabilities: each is returned as 0..255 (unsigned). */
    @Synchronized
    fun runToBytes(input: Input): Output<ByteArray> {
        val shape = IntArray(3)
        val values = NcnnNative.runBytes(open(), input.image, input.width, input.height, input.padRight, input.padBottom, input.padValue, input.mean, input.norm, shape)
        return Output(values, shape[0], shape[1], shape[2])
    }

    /** Gives back the memory a network that reuses memory has kept from past runs. */
    @Synchronized
    fun trim() {
        if (handle != 0L) NcnnNative.trim(handle)
    }

    private fun open(): Long = handle.takeIf { it != 0L } ?: throw IllegalStateException("The network is closed")

    @Synchronized
    override fun close() {
        if (handle != 0L) {
            NcnnNative.free(handle)
            handle = 0L
        }
    }

    companion object {
        private val library = lazy { System.loadLibrary("usugami_ocr") }

        /** Loads the native library. @throws UnsatisfiedLinkError when it can't be loaded. */
        fun loadLibrary() {
            library.value
        }

        /**
         * Loads the network [param] and its weights [model] (ncnn's .param and .bin files) from [assets].
         * With [reuseMemory], buffers are kept from one run to the next until [trim] (faster for many
         * small inputs); without, memory goes back to the system after each run (for large inputs).
         * @throws IOException when a file is missing or damaged
         */
        fun load(assets: AssetManager, param: String, model: String, reuseMemory: Boolean): NcnnNet {
            loadLibrary()
            return NcnnNet(NcnnNative.load(assets, param, model, reuseMemory))
        }
    }
}

/** The functions in libusugami_ocr.so (src/main/cpp/usugami_ocr.cpp). */
internal object NcnnNative {
    @JvmStatic
    @Throws(IOException::class)
    external fun load(assets: AssetManager, param: String, model: String, reuseMemory: Boolean): Long

    @JvmStatic
    external fun free(handle: Long)

    @JvmStatic
    external fun trim(handle: Long)

    @JvmStatic
    external fun runFloats(
        handle: Long, image: Bitmap, width: Int, height: Int, padRight: Int, padBottom: Int,
        padValue: Float, mean: FloatArray, norm: FloatArray, shape: IntArray,
    ): FloatArray

    @JvmStatic
    external fun runBytes(
        handle: Long, image: Bitmap, width: Int, height: Int, padRight: Int, padBottom: Int,
        padValue: Float, mean: FloatArray, norm: FloatArray, shape: IntArray,
    ): ByteArray
}
