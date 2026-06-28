cmake_minimum_required(VERSION 3.22.1)
project("aegisvoice")

# ==========================================
# 1. 核心设置：稳定高性能模式
# ==========================================
# 开启 CPU 计算
add_compile_definitions(GGML_USE_CPU)

# 【关键修复】从 -O3 降级为 -O2
# -O3 太激进导致编译器崩溃，-O2 是最稳定的高性能选项
add_compile_options(-O2 -fno-math-errno)

# 版本定义
add_compile_definitions(GGML_VERSION="android-beta-1")
add_compile_definitions(GGML_COMMIT="unknown")
add_compile_definitions(WHISPER_VERSION="1.7.1")
add_compile_definitions(WHISPER_BUILD_DATE="2025")

# 2. 标准 C++17
set(CMAKE_CXX_STANDARD 17)

# 3. 头文件路径
include_directories(
        ${CMAKE_SOURCE_DIR}/include
        ${CMAKE_SOURCE_DIR}/ggml/include
        ${CMAKE_SOURCE_DIR}/ggml/src
        ${CMAKE_SOURCE_DIR}/ggml/src/ggml-cpu
)

# 4. 自动抓取源文件
file(GLOB WHISPER_SOURCES "src/*.cpp")
file(GLOB GGML_ROOT_SOURCES "ggml/src/*.c" "ggml/src/*.cpp")
file(GLOB_RECURSE GGML_CPU_SOURCES "ggml/src/ggml-cpu/*.c" "ggml/src/ggml-cpu/*.cpp")

set(ALL_GGML_SOURCES ${GGML_ROOT_SOURCES} ${GGML_CPU_SOURCES})

# 5. 自动踢掉不兼容的架构 (显卡、非ARM等)
foreach(f ${ALL_GGML_SOURCES})
    if(f MATCHES "vulkan" OR f MATCHES "metal" OR f MATCHES "cuda" OR f MATCHES "opencl" OR f MATCHES "sycl" OR f MATCHES "kompute" OR f MATCHES "rpc" OR f MATCHES "blas" OR f MATCHES "cann" OR f MATCHES "backend-test" OR f MATCHES "hexagon" OR f MATCHES "amx" OR f MATCHES "avx" OR f MATCHES "sse" OR f MATCHES "riscv" OR f MATCHES "kleidiai" OR f MATCHES "lsw" OR f MATCHES "zdnn" OR f MATCHES "mpi" OR f MATCHES "qnn" OR f MATCHES "spacemit" OR f MATCHES "musa" OR f MATCHES "hip")
        list(REMOVE_ITEM ALL_GGML_SOURCES ${f})
    endif()
    # 踢掉 llamafile
    if(f MATCHES "loongarch" OR f MATCHES "s390" OR f MATCHES "riscv" OR f MATCHES "wasm" OR f MATCHES "x86" OR f MATCHES "llamafile")
        list(REMOVE_ITEM ALL_GGML_SOURCES ${f})
    endif()
endforeach()

# 6. 生成库
add_library(native-lib SHARED native-lib.cpp ${WHISPER_SOURCES} ${ALL_GGML_SOURCES})

# 7. 链接日志
find_library(log-lib log)
target_link_libraries(native-lib ${log-lib})