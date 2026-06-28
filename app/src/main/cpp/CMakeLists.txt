cmake_minimum_required(VERSION 3.22.1)
project("aegisvoice_v13_titan")

# =========================================================================================
# 🏭 AegisVoice V13 "Titan" - 核心兵工厂 (Native Build System)
#
# 📢 【开源作者架构说明与性能挑战宣言】
#
# 1. ⚖️ 算力分发体系 (Dual-Engine Architecture):
#    - 重型装甲 (Whisper 核心): 负责高精度的上下文识别。主要依赖高通 NPU (Hexagon DSP)
#      释放极限算力，若 NPU 缺失则自动降级至 GPU (Vulkan)，最后才是 CPU 兜底。
#    - 轻量斥候 (Vosk 辅助): 负责基础唤醒与轻量级任务，这部分完全交由 CPU 处理。
#
# 2. 🧱 硬件断代现实 (The Snapdragon Gen 3 Divide):
#    在商业化落地的真实海量测试中，我们得出了一个残酷的物理结论：
#    将纯本地化、未阉割的 Whisper 级大模型硬塞入移动端，Snapdragon Gen 2 及更老的芯片
#    在算力延迟、能耗和发热上表现极差，体验存在明显的卡顿与撕裂感。
#
# 3. 🛡️ 本项目的商业妥协:
#    为了捍卫 299 美金级极其丝滑的极致体验，当前兵工厂的底层驱动 (.so) 库与编译策略，
#    全部**专为 Snapdragon Gen 3、Gen 4、Gen 5+ 等新一代旗舰架构量身定制**。
#    这也是为何在 build.gradle 中我们强行设置 `minSdk = 34`，通过系统级门禁限制了低端机。
#
# 4. ⚔️ 致极客与开源工程师的邀请:
#    "如何在不严重损耗精度的前提下，让端侧大模型在低端旧设备上依然能流畅奔跑？"
#    这不仅是本项目的痛点，也是整个端侧 AI 行业的圣杯。
#    我当前的解法是“切断低端生态”。如果您拥有更激进的模型量化方案、更底层的汇编压榨技巧，
#    或者全新的推理引擎替代方案，欢迎 Fork 本项目！
#    打破硬件的阶级壁垒，我期待在 PR 中看到你们的伟大代码！
# =========================================================================================


# ==========================================
# 0. 安全与反破解基座 (Security Foundation)
# ==========================================
# 🛡️ [防破解 & 性能全开]
# -Ofast: 极速优化 (针对数学计算)
# -fno-finite-math-only: 🚀 [关键修复] 允许 NaN/Infinity，防止 GGML 编译错误
# -fvisibility=hidden: 👻 核心防御，隐藏所有 C++ 函数名，让反编译看到的都是 sub_xxxxx
# -s: 剥离调试符号，减小体积
add_compile_options(-Ofast -fno-finite-math-only -fvisibility=hidden -s -fno-math-errno -Wall)

# 强制 C++17 (QNN 和 Whisper 的硬性要求)
set(CMAKE_CXX_STANDARD 17)
# 👇👇👇 [核心指令] 解决 Google Play 16KB 内存页拦截 👇👇👇
# 强制共享库链接器使用 16KB 内存页对齐。此设置向下兼容当前的 4KB 手机。
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")
# 👆👆👆 [指令结束] 👆👆👆

# ==========================================
# 0.5 🛡️ 日志与安全宏控制中心
# ==========================================
# 控制 C++ 底层代码中的日志输出与防御逻辑
if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    # Debug 模式：允许输出日志，方便调试 AssetsLoader 等模块
    add_compile_definitions(AEGIS_DEBUG_MODE=1)
    message(STATUS "🐞 [Log] Debug Mode: Logs ENABLED (AEGIS_DEBUG_MODE=1)")
else()
    # Release 模式：强制静音，物理切断 Log 输出，增加逆向难度
    add_compile_definitions(AEGIS_DEBUG_MODE=0)
    add_compile_definitions(NDEBUG) # 禁用 assert
    message(STATUS "🔇 [Log] Release Mode: Logs MUTED (AEGIS_DEBUG_MODE=0)")
endif()

# ==========================================
# 1. 动态算力仲裁配置 (Compute Arbitration)
# ==========================================
# 核心逻辑：NPU > GPU > CPU (兜底)
# 任何环节缺失即自动静默降级，绝不报错中断。

# --- [第一梯队] NPU (Qualcomm QNN/HTP) ---
# 目标：Snapdragon 8 Gen 3 / Elite 及以上
set(QNN_LIB_PATH "${CMAKE_SOURCE_DIR}/libs/qnn")
set(QNN_INCLUDE_PATH "${CMAKE_SOURCE_DIR}/libs/qnn/include/qnn")

# 侦测逻辑：仅当头文件和库文件均存在时，激活 NPU
if(EXISTS "${QNN_LIB_PATH}/libQnnHtp.so" AND EXISTS "${QNN_INCLUDE_PATH}")
    message(STATUS "🚀 [NPU] 检测到 Gen3+ 驱动环境，正在激活 Hexagon DSP 加速引擎...")
    set(ENABLE_QNN TRUE)
    add_compile_definitions(AEGIS_USE_QNN) # 通知 C++ 层：NPU 可用
    include_directories(${QNN_INCLUDE_PATH})
else()
    # ⚠️ 软报错机制：仅在编译日志警告，不中断构建，自动降级
    message(WARNING "⚠️ [NPU] 未检测到 QNN SDK！编译将自动降级为 GPU/CPU 模式 (不影响 APP 运行，仅损失 NPU 速度)。")
    set(ENABLE_QNN FALSE)
endif()

# --- [第二梯队] GPU (Vulkan/Adreno) ---
# 目标：Snapdragon Gen 2 及旧机型降级方案
set(VULKAN_SRC "${CMAKE_SOURCE_DIR}/ggml/src/ggml-vulkan.cpp")
set(BACKEND_SRC "${CMAKE_SOURCE_DIR}/ggml/src/ggml-backend.c")

if(EXISTS "${VULKAN_SRC}")
    message(STATUS "🎮 [GPU] 检测到 Vulkan 核心，激活 Adreno GPU 备用加速通道...")
    set(ENABLE_VULKAN TRUE)
    add_compile_definitions(GGML_USE_VULKAN) # 通知 C++ 层：GPU 可用
else()
    message(WARNING "⚠️ [GPU] 未找到 Vulkan 源码，将降级为纯 CPU 模式。")
    set(ENABLE_VULKAN FALSE)
endif()

# --- [第三梯队] CPU (终极兜底) ---
# CPU 引擎常驻激活状态
add_compile_definitions(GGML_USE_CPU)

# ==========================================
# 2. 版本与元数据注入
# ==========================================
add_compile_definitions(GGML_VERSION="aegis-v13-titan")
add_compile_definitions(GGML_COMMIT="final-titan-v1")
add_compile_definitions(WHISPER_VERSION="1.7.1-opt")

# ==========================================
# 3. 头文件路径装载
# ==========================================
include_directories(
        ${CMAKE_SOURCE_DIR}/include
        ${CMAKE_SOURCE_DIR}/ggml/include
        ${CMAKE_SOURCE_DIR}/ggml/src
        ${CMAKE_SOURCE_DIR}/ggml/src/ggml-cpu
)

# ==========================================
# 4. 源文件装载与架构清洗
# ==========================================
# A. 核心 Whisper 逻辑
file(GLOB WHISPER_SOURCES "src/*.cpp")
file(GLOB GGML_ROOT_SOURCES "ggml/src/*.c" "ggml/src/*.cpp")

# B. CPU 后端逻辑
file(GLOB_RECURSE GGML_CPU_SOURCES "ggml/src/ggml-cpu/*.c" "ggml/src/ggml-cpu/*.cpp")

set(ALL_GGML_SOURCES
        ${GGML_ROOT_SOURCES}
        ${GGML_CPU_SOURCES}
)

# C. GPU 逻辑注入 (若激活)
if(ENABLE_VULKAN)
    list(APPEND ALL_GGML_SOURCES "${VULKAN_SRC}" "${BACKEND_SRC}")
    file(GLOB VULKAN_HELPERS "ggml/src/ggml-alloc.c" "ggml/src/ggml-quants.c")
    list(APPEND ALL_GGML_SOURCES ${VULKAN_HELPERS})
endif()

# D. 深度清洗 (Architecture Cleaning)
# 🛡️ 剔除一切 PC 端 (CUDA/Metal) 及无用干扰项，实现移动端极致瘦身
foreach(f ${ALL_GGML_SOURCES})
    if(f MATCHES "metal" OR f MATCHES "cuda" OR f MATCHES "opencl" OR f MATCHES "sycl" OR f MATCHES "blas" OR f MATCHES "avx" OR f MATCHES "sse" OR f MATCHES "kleidiai" OR f MATCHES "riscv" OR f MATCHES "amx" OR f MATCHES "spacemit" OR f MATCHES "lsw" OR f MATCHES "zdnn" OR f MATCHES "mpicalc" OR f MATCHES "backend-test")
        list(REMOVE_ITEM ALL_GGML_SOURCES ${f})
    endif()
endforeach()

# E. CPU 架构对齐
# (针对 Android 端，剔除 x86 碎片，专注优化 ARM64)
if(ANDROID_ABI MATCHES "arm" OR ANDROID_ABI MATCHES "aarch64")
    message(STATUS "🛡️ Optimizing for ARM64 (Kryo/Cortex)...")
    foreach(f ${ALL_GGML_SOURCES})
        if(f MATCHES "/ggml-cpu/arch/" AND NOT f MATCHES "/ggml-cpu/arch/arm")
            list(REMOVE_ITEM ALL_GGML_SOURCES ${f})
        endif()
    endforeach()
endif()

# ==========================================
# 5. 生成与最终链接 (The Linkage)
# ==========================================

add_library(
        native-lib
        SHARED
        # --- 核心中枢 ---
        native-lib.cpp

        # --- 三大护法 (Old Guard) ---
        fuzzy_match.cpp     # 核武 (Fuzzy Match 纠错引擎)
        security_check.cpp  # 门禁 (Anti-Debug & 版权卫士)
        model_crypto.cpp    # 保险箱 (大模型流密码解密)

        # --- 两员大将 (New Recruits) ---
        assets_loader.cpp   # 物流中心 (分级词库加密装载)
        user_data_guard.cpp # 保镖 (用户隐私热词加解密)

        # --- AI 引擎源码 ---
        ${WHISPER_SOURCES}
        ${ALL_GGML_SOURCES}
)

# 基础系统库
find_library(log-lib log)
find_library(android-lib android)
set(LIBS_TO_LINK ${log-lib} ${android-lib})

# 🔗 链接 GPU 库 (Vulkan)
if(ENABLE_VULKAN)
    find_library(vulkan-lib vulkan)
    if(vulkan-lib)
        list(APPEND LIBS_TO_LINK ${vulkan-lib})
    endif()
endif()

# 🔗 链接 NPU 库 (QNN HTP)
if(ENABLE_QNN)
    # 强制物理链接 libQnnHtp.so
    list(APPEND LIBS_TO_LINK "${QNN_LIB_PATH}/libQnnHtp.so")
    message(STATUS "🔗 [Linker] 已将 Qualcomm QNN HTP 驱动注入 native-lib。")
endif()

# 执行终极链接
target_link_libraries(native-lib ${LIBS_TO_LINK})