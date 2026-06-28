plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    // 💡 开源规范：剥离硬编码的本地私钥与密码，改用标准环境变量或占位符引导
    signingConfigs {
        create("release_config") {
            // 开源提示：请在本地配置环境变量或准备您自己的 .jks 密钥文件
            storeFile = file(System.getenv("AEGIS_RELEASE_KEY_PATH") ?: "signature/aegis_release_key.jks")
            storePassword = System.getenv("AEGIS_STORE_PASSWORD") ?: "OPEN_SOURCE_PLACEHOLDER_PASSWORD"
            keyPassword = System.getenv("AEGIS_KEY_PASSWORD") ?: "OPEN_SOURCE_PLACEHOLDER_PASSWORD"
            keyAlias = System.getenv("AEGIS_KEY_ALIAS") ?: "aegis_key"
        }
    }

    namespace = "com.aegis.voice"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aegis.voice"

        // 🚀 [系统要求] 针对新一代旗舰芯片优化，仅限 Android 14 (API 34) 及以上运行环境
        minSdk = 34
        targetSdk = 36

        // 🌟 [版本架构] 16K 页大小双重签名版发布代号
        versionCode = 10
        versionName = "v2.0.1"

        // 🛡️ [版权追踪] Java层 隐形版权数字钢印
        buildConfigField("String", "COPYRIGHT_WATERMARK", "\"AEGIS_ARCHITECT_LUCAS_V9_OFFICIAL\"")

        ndk {
            // ✅ [架构锁定] 仅保留 64 位高效架构 (arm64-v8a)
            // 确保 Whisper FP16/Int8 高性能计算在纯 64 位环境下发挥极限硬件性能
            abiFilters += listOf("arm64-v8a")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                // 🛡️ [安全强化] 隐藏符号表以提升逆向工程难度，并向 C++ 底层注入 V9 架构专属版权钢印
                cppFlags += "-std=c++17 -fvisibility=hidden -DAEGIS_WATERMARK=\\\"AEGIS_ARCHITECT_LUCAS_V9_OFFICIAL\\\""
            }
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            // 优先采用经 16KB 页大小对齐优化的定制版底层动态库
            pickFirsts.add("lib/arm64-v8a/libvosk.so")
        }
    }

    // 💡 [大模型打包策略优化]
    // 已解除 aapt 对特定模型及配置文件的强制不压缩限制。
    // 构建系统将启用全量压缩算法，大幅精简 AAB 产物大小以满足 Google Play 200MB 的上架合规指标。
    /*
    aaptOptions {
        noCompress += listOf("bin", "uuid", "mdl", "fst", "conf", "json", "txt", "dic", "engine", "dat")
    }
    */

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            // 🛡️ [生产环境安全] 强制关闭运行期调试日志
            buildConfigField("boolean", "LOG_DEBUG", "false")

            // 🛡️ [反篡改防御] 移除 Native 调试符号 (Debug Symbols)，关闭崩溃堆栈的明文可读性
            ndk {
                debugSymbolLevel = "NONE"
            }

            // 🛡️ [防注入防御] 严格禁用调试模式，配合底层 C++ 签名校验校验项目完整性
            isDebuggable = false
            signingConfig = signingConfigs.getByName("release_config")
        }
        debug {
            buildConfigField("boolean", "LOG_DEBUG", "true")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // 🔄 [语音识别内核] 引入 Vosk 核心接口，排斥其内置的旧版 4K JNA 组件
    // 构建系统将自动检索并嵌入 jniLibs 目录下经过 16KB 适配优化的 libvosk.so
    implementation("com.alphacephei:vosk-android:0.3.47") {
        exclude(group = "net.java.dev.jna", module = "jna")
    }

    // 🛡️ [稳定性修复] 强制依赖官方高版本 JNA 库，彻底解决 16KB 页内存环境下的系统键盘崩溃问题
    implementation("net.java.dev.jna:jna:5.14.0@aar")

    implementation("com.google.code.gson:gson:2.10.1")
}