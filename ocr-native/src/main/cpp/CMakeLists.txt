# libusugami_ocr.so: Usugami OCR's text recognition engine. Tencent's ncnn (the git submodule
# third_party/ncnn) is built from source as a static library with only the layers the PaddleOCR
# models need, and linked into one shared library together with the JNI bridge.
cmake_minimum_required(VERSION 3.22.1)
project(usugami_ocr C CXX)

set(NCNN_SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/ncnn")
if(NOT EXISTS "${NCNN_SOURCE_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "third_party/ncnn is missing. Run: git submodule update --init third_party/ncnn")
endif()

# Small code: -Os, every function and variable in its own section so the linker can drop what is
# unused. The same flags for debug builds, so the device tests run at the real speed.
set(USUGAMI_FLAGS "-Os -DNDEBUG -ffunction-sections -fdata-sections -fvisibility=hidden -fvisibility-inlines-hidden")
foreach(lang C CXX)
    foreach(config DEBUG RELEASE RELWITHDEBINFO MINSIZEREL)
        set(CMAKE_${lang}_FLAGS_${config} "${USUGAMI_FLAGS}")
    endforeach()
endforeach()

macro(ncnn_option name value)
    set(${name} ${value} CACHE BOOL "" FORCE)
endmacro()
# A fixed version instead of the build date, so the same source always gives the same library.
set(NCNN_VERSION 20261020)
ncnn_option(NCNN_SHARED_LIB OFF)
ncnn_option(NCNN_INSTALL_SDK OFF)
ncnn_option(NCNN_VULKAN OFF)
ncnn_option(NCNN_INT8 OFF)
ncnn_option(NCNN_BF16 OFF)
ncnn_option(NCNN_WEIGHT_QUANT OFF)
ncnn_option(NCNN_C_API OFF)
# The models' Reshape layers give a batch size, which ncnn only reads with batch support.
ncnn_option(NCNN_BATCH ON)
ncnn_option(NCNN_PIXEL_DRAWING OFF)
ncnn_option(NCNN_PIXEL_ROTATE OFF)
ncnn_option(NCNN_PIXEL_AFFINE OFF)
ncnn_option(NCNN_BUILD_TOOLS OFF)
ncnn_option(NCNN_BUILD_EXAMPLES OFF)
ncnn_option(NCNN_BUILD_BENCHMARK OFF)
ncnn_option(NCNN_BUILD_TESTS OFF)
ncnn_option(NCNN_PYTHON OFF)
ncnn_option(NCNN_OPENMP ON)
# The networks run in 32-bit floats (usugami_ocr.cpp), so the kernels for 16-bit floats on ARM
# (ARMv8.2 half-precision arithmetic, and the 16-bit storage kernels for VFPv4) would never run.
ncnn_option(NCNN_ARM82 OFF)
ncnn_option(NCNN_VFPV4 OFF)
# Models are read from the APK's assets (Android's AAssetManager), with readable layer names.
ncnn_option(NCNN_PLATFORM_API ON)
ncnn_option(NCNN_STRING ON)

# The layers in the three models' .param files, plus the ones ncnn creates inside them: Padding,
# Crop, Cast and Packing for any network, Flatten for InnerProduct, and every activation a
# convolution or inner product can have fused into it (fused_activation.h). ncnn doesn't check that
# those exist, so leaving one out crashes when the model loads; a missing layer of the .param files
# only makes loading fail. The device tests load all three models.
set(USUGAMI_NCNN_LAYERS
    BinaryOp Concat Convolution ConvolutionDepthWise Deconvolution Gemm HardSigmoid HardSwish
    InnerProduct Input Interp LayerNorm MultiHeadAttention Permute Pooling Reshape Sigmoid Softmax
    Split Squeeze Swish
    Padding Crop Cast Packing Flatten ReLU Clip Mish)
file(STRINGS "${NCNN_SOURCE_DIR}/src/CMakeLists.txt" ncnn_layer_lines REGEX "^ncnn_add_layer\\(")
foreach(line IN LISTS ncnn_layer_lines)
    string(REGEX REPLACE "^ncnn_add_layer\\(([A-Za-z0-9_]+).*" "\\1" layer "${line}")
    string(TOLOWER "${layer}" name)
    if(layer IN_LIST USUGAMI_NCNN_LAYERS)
        ncnn_option(WITH_LAYER_${name} ON)
    else()
        ncnn_option(WITH_LAYER_${name} OFF)
    endif()
endforeach()

add_subdirectory("${NCNN_SOURCE_DIR}" ncnn EXCLUDE_FROM_ALL)

add_library(usugami_ocr SHARED usugami_ocr.cpp)
target_link_libraries(usugami_ocr PRIVATE ncnn android jnigraphics log)
target_compile_options(usugami_ocr PRIVATE -Wall -Wextra -fno-rtti -fno-exceptions)
# 16 KB pages: every LOAD segment aligned to 16 KB, which Android 15+ devices with 16 KB pages need.
target_link_options(usugami_ocr PRIVATE
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL
    -Wl,--as-needed
    -Wl,-z,max-page-size=16384
    -s)
