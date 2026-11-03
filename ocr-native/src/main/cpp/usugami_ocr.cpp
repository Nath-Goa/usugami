// Usugami OCR's bridge between Kotlin (io.github.nath_goa.usugami.ocr.NcnnNative) and ncnn: loads a
// network from the APK's assets and runs it on a picture. Bad arguments and failures come back to
// Kotlin as exceptions; nothing here may crash the app.
#include <jni.h>
#include <android/asset_manager_jni.h>
#include <android/bitmap.h>

#include <algorithm>
#include <cstdint>
#include <new>

#include "allocator.h"
#include "cpu.h"
#include "mat.h"
#include "net.h"

namespace {

// Far above anything Usugami passes (a page is at most 2600 pixels on its long side), but small
// enough that sizes can't overflow.
constexpr int kMaxSide = 8192;
constexpr int64_t kMaxPixels = 32LL * 1024 * 1024;
constexpr int64_t kMaxOutput = 64LL * 1024 * 1024;

void throwJava(JNIEnv* env, const char* className, const char* message) {
    if (env->ExceptionCheck()) return; // keep the first exception
    jclass type = env->FindClass(className);
    if (type != nullptr) env->ThrowNew(type, message);
}

void throwIllegalArgument(JNIEnv* env, const char* message) {
    throwJava(env, "java/lang/IllegalArgumentException", message);
}

// A network, with memory pools of its own when it reuses memory: buffers are then kept from one run
// to the next until trim() gives them back. The pools are declared first, so they outlive the network.
struct Network {
    ncnn::PoolAllocator blobs;
    ncnn::PoolAllocator workspace;
    ncnn::Net net;
};

Network* networkOf(JNIEnv* env, jlong handle) {
    auto* network = reinterpret_cast<Network*>(handle);
    if (network == nullptr) throwJava(env, "java/lang/IllegalStateException", "The network is closed");
    return network;
}

// Turns [bitmap] into the network's input: resized to width x height, padded on the right and at the
// bottom with padValue, in BGR channel order, then (value - mean) * norm per channel.
bool makeInput(JNIEnv* env, jobject bitmap, jint width, jint height, jint padRight, jint padBottom,
               jfloat padValue, jfloatArray mean, jfloatArray norm, ncnn::Mat& input) {
    if (bitmap == nullptr || mean == nullptr || norm == nullptr) {
        throwIllegalArgument(env, "Missing argument");
        return false;
    }
    if (env->GetArrayLength(mean) != 3 || env->GetArrayLength(norm) != 3) {
        throwIllegalArgument(env, "mean and norm need 3 values");
        return false;
    }
    if (width < 1 || height < 1 || width > kMaxSide || height > kMaxSide || padRight < 0 || padBottom < 0 ||
        padRight > kMaxSide || padBottom > kMaxSide ||
        static_cast<int64_t>(width + padRight) * (height + padBottom) > kMaxPixels) {
        throwIllegalArgument(env, "Bad input size");
        return false;
    }
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        throwIllegalArgument(env, "Not a bitmap");
        return false;
    }
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || info.width < 1 || info.height < 1 ||
        info.width > static_cast<uint32_t>(kMaxSide) || info.height > static_cast<uint32_t>(kMaxSide) ||
        info.stride < info.width * 4) {
        throwIllegalArgument(env, "The bitmap must be ARGB_8888 and at most 8192 pixels on each side");
        return false;
    }
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || pixels == nullptr) {
        throwIllegalArgument(env, "The bitmap's pixels can't be read (recycled?)");
        return false;
    }
    // Android keeps ARGB_8888 pixels as R, G, B, A bytes. The models were trained on BGR pictures.
    ncnn::Mat image = ncnn::Mat::from_pixels_resize(static_cast<const unsigned char*>(pixels), ncnn::Mat::PIXEL_RGBA2BGR,
                                                    static_cast<int>(info.width), static_cast<int>(info.height),
                                                    static_cast<int>(info.stride), width, height);
    AndroidBitmap_unlockPixels(env, bitmap);
    if (image.empty()) {
        throwJava(env, "java/lang/OutOfMemoryError", "No memory for the network's input");
        return false;
    }
    if (padRight > 0 || padBottom > 0) {
        ncnn::Mat padded;
        ncnn::copy_make_border(image, padded, 0, padBottom, 0, padRight, ncnn::BORDER_CONSTANT, padValue);
        if (padded.empty()) {
            throwJava(env, "java/lang/OutOfMemoryError", "No memory for the network's input");
            return false;
        }
        image = padded;
    }
    float meanValues[3];
    float normValues[3];
    env->GetFloatArrayRegion(mean, 0, 3, meanValues);
    env->GetFloatArrayRegion(norm, 0, 3, normValues);
    // Done here rather than with Mat::substract_mean_normalize, which needs ncnn's Scale layer.
    for (int q = 0; q < image.c; q++) {
        float* values = image.channel(q);
        const int64_t size = static_cast<int64_t>(image.w) * image.h;
        for (int64_t i = 0; i < size; i++) values[i] = (values[i] - meanValues[q]) * normValues[q];
    }
    input = image;
    return true;
}

// Runs [net] on [input]. On success [output] is a plain float32 tensor of 1 to 3 dimensions.
bool run(JNIEnv* env, ncnn::Net* net, const ncnn::Mat& input, ncnn::Mat& output, jintArray shape) {
    if (shape == nullptr || env->GetArrayLength(shape) != 3) {
        throwIllegalArgument(env, "shape needs 3 values");
        return false;
    }
    ncnn::Extractor extractor = net->create_extractor();
    int result = extractor.input("in0", input);
    if (result == 0) result = extractor.extract("out0", output);
    if (result != 0 || output.empty()) {
        // ncnn reports running out of memory as -100.
        throwJava(env, result == -100 ? "java/lang/OutOfMemoryError" : "java/lang/IllegalStateException",
                  "The network failed to run");
        return false;
    }
    if (output.dims < 1 || output.dims > 3 || output.elempack != 1 || output.elemsize != 4) {
        throwJava(env, "java/lang/IllegalStateException", "Unexpected network output");
        return false;
    }
    int64_t size = static_cast<int64_t>(output.c) * output.h * output.w;
    if (size < 1 || size > kMaxOutput) {
        throwJava(env, "java/lang/IllegalStateException", "Unexpected network output size");
        return false;
    }
    const jint dims[3] = {output.c, output.h, output.w};
    env->SetIntArrayRegion(shape, 0, 3, dims);
    return true;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_io_github_nath_1goa_usugami_ocr_NcnnNative_load(
        JNIEnv* env, jclass, jobject assets, jstring paramPath, jstring modelPath, jboolean reuseMemory) {
    if (assets == nullptr || paramPath == nullptr || modelPath == nullptr) {
        throwIllegalArgument(env, "Missing argument");
        return 0;
    }
    AAssetManager* manager = AAssetManager_fromJava(env, assets);
    if (manager == nullptr) {
        throwIllegalArgument(env, "No asset manager");
        return 0;
    }
    auto* network = new (std::nothrow) Network();
    if (network == nullptr) {
        throwJava(env, "java/lang/OutOfMemoryError", "No memory for the network");
        return 0;
    }
    ncnn::Net* net = &network->net;
    if (reuseMemory) {
        // Faster for many small runs (the lines of a page).
        network->blobs.set_size_compare_ratio(0.f); // like ncnn's own pools: any larger buffer will do
        network->workspace.set_size_compare_ratio(0.f);
        net->opt.blob_allocator = &network->blobs;
        net->opt.workspace_allocator = &network->workspace;
    } else {
        // For large pictures a pool would keep about twice the memory a run needs (and ncnn's own pool
        // keeps every buffer it ever used), so memory goes straight back to the system instead.
        net->opt.use_local_pool_allocator = false;
    }
    net->opt.use_vulkan_compute = false;
    net->opt.num_threads = std::max(1, std::min(4, ncnn::get_big_cpu_count()));
    const char* param = env->GetStringUTFChars(paramPath, nullptr);
    const char* model = param != nullptr ? env->GetStringUTFChars(modelPath, nullptr) : nullptr;
    // Fails when a file is missing or damaged, or uses a layer this build leaves out.
    bool loaded = param != nullptr && model != nullptr && net->load_param(manager, param) == 0 &&
                  net->load_model(manager, model) == 0;
    if (model != nullptr) env->ReleaseStringUTFChars(modelPath, model);
    if (param != nullptr) env->ReleaseStringUTFChars(paramPath, param);
    if (!loaded) {
        delete network;
        throwJava(env, "java/io/IOException", "Could not load the text recognition model");
        return 0;
    }
    return reinterpret_cast<jlong>(network);
}

JNIEXPORT void JNICALL Java_io_github_nath_1goa_usugami_ocr_NcnnNative_free(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<Network*>(handle);
}

// Gives the buffers kept from past runs back to the system.
JNIEXPORT void JNICALL Java_io_github_nath_1goa_usugami_ocr_NcnnNative_trim(JNIEnv* env, jclass, jlong handle) {
    Network* network = networkOf(env, handle);
    if (network == nullptr) return;
    network->blobs.clear();
    network->workspace.clear();
}

JNIEXPORT jfloatArray JNICALL Java_io_github_nath_1goa_usugami_ocr_NcnnNative_runFloats(
        JNIEnv* env, jclass, jlong handle, jobject bitmap, jint width, jint height, jint padRight, jint padBottom,
        jfloat padValue, jfloatArray mean, jfloatArray norm, jintArray shape) {
    Network* network = networkOf(env, handle);
    ncnn::Mat input;
    ncnn::Mat output;
    if (network == nullptr || !makeInput(env, bitmap, width, height, padRight, padBottom, padValue, mean, norm, input) ||
        !run(env, &network->net, input, output, shape)) {
        return nullptr;
    }
    input.release();
    const jsize plane = output.h * output.w;
    jfloatArray result = env->NewFloatArray(plane * output.c);
    if (result == nullptr) return nullptr; // OutOfMemoryError is pending
    for (int q = 0; q < output.c; q++) {
        env->SetFloatArrayRegion(result, q * plane, plane, output.channel(q));
    }
    return result;
}

// The same, for outputs between 0 and 1 (probabilities), returned as 0..255 to save memory.
JNIEXPORT jbyteArray JNICALL Java_io_github_nath_1goa_usugami_ocr_NcnnNative_runBytes(
        JNIEnv* env, jclass, jlong handle, jobject bitmap, jint width, jint height, jint padRight, jint padBottom,
        jfloat padValue, jfloatArray mean, jfloatArray norm, jintArray shape) {
    Network* network = networkOf(env, handle);
    ncnn::Mat input;
    ncnn::Mat output;
    if (network == nullptr || !makeInput(env, bitmap, width, height, padRight, padBottom, padValue, mean, norm, input) ||
        !run(env, &network->net, input, output, shape)) {
        return nullptr;
    }
    input.release();
    const jsize plane = output.h * output.w;
    jbyteArray result = env->NewByteArray(plane * output.c);
    if (result == nullptr) return nullptr; // OutOfMemoryError is pending
    jbyte* bytes = env->GetByteArrayElements(result, nullptr);
    if (bytes == nullptr) return nullptr;
    for (int q = 0; q < output.c; q++) {
        const float* values = output.channel(q);
        jbyte* target = bytes + static_cast<int64_t>(q) * plane;
        for (jsize i = 0; i < plane; i++) {
            float v = values[i];
            // Written so that NaN becomes 0.
            int level = !(v > 0.f) ? 0 : v >= 1.f ? 255 : static_cast<int>(v * 255.f + 0.5f);
            target[i] = static_cast<jbyte>(level);
        }
    }
    env->ReleaseByteArrayElements(result, bytes, 0);
    return result;
}

} // extern "C"
