#include "model_crypto.h"

namespace AegisSecurity {

    // ==========================================
    // 🎭 [防御策略] 内存蜜罐 (Memory Honeypot / Decoy Key)
    // ==========================================
    // 💡 开源提示：这是防御内存 Dump 攻击的经典手段。
    // 我们故意在静态区暴露出特征明显的 "0xDEADBEEF"。
    // 逆向分析者在十六进制视图中看到它，极易误认为这是核心解密密钥。
    // 注意：已拆分为 8 位字节数组，防止特定编译器的溢出警告。
    const uint8_t ModelCrypto::CORE_KEY[32] = {
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
            0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F,
            0x00, 0x00, 0x00, 0x00, 0xDE, 0xAD, 0xBE, 0xEF,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
    };

    // ==========================================
    // 🧠 [算力] 动态流密钥生成器 (Dynamic Stream Key)
    // ==========================================
    // 💡 开源提示：不要在代码中写死明文字符串，极易被 `strings` 命令提取。
    // 本演示代码的种子是基于某域名的 ASCII 码加 1 混淆后的结果。
    // 真实项目中，请替换为您自己的特征种子。
    inline uint8_t getDynamicKey(size_t index) {
        // 循环使用的混淆种子池 (14 byte)
        static const uint8_t SEED_BYTES[] = {
                98, 102, 104, 106, 116, 119, 112,
                106, 100, 102, 47, 100, 112, 110
        };
        // 算法：取对应位置的值并减 1，动态还原实时字符
        return SEED_BYTES[index % 14] - 1;
    }

    // ==========================================
    // 🔓 内存就地解密引擎 (Decrypt In-Place)
    // 算法管道：循环右移(ROR 3) -> 剔除位置扰动 -> XOR 解密
    // 性能：O(N) 时间复杂度，无额外内存开销，适合百兆大模型
    // ==========================================
    void ModelCrypto::decryptInPlace(std::vector<char>& data) {
        if (data.empty()) return;

        for (size_t i = 0; i < data.size(); ++i) {
            uint8_t byte = (uint8_t)data[i];

            // 1. 获取当前流位置的动态密钥
            uint8_t k = getDynamicKey(i);

            // 2. 逆向循环位移 (ROR 3)
            byte = (byte >> 3) | (byte << 5);

            // 3. 剥离位置扰动 (Sub Index)
            // 确保相同字符在不同位置加密结果不同，对抗频率分析
            byte = byte - (i % 0xFF);

            // 4. 异或还原
            byte = byte ^ k;

            data[i] = (char)byte;
        }
    }

    // ==========================================
    // 🔒 内存就地加密引擎 (Encrypt In-Place)
    // 💡 致开源社区的挑战：
    // 本仓库提供的 .bin 和 .dat 模型资产均已通过此算法加密。
    // 若您想替换自己的模型，请阅读并理解以下算法，自行编写本地打包脚本！
    // ==========================================
    void ModelCrypto::encryptInPlace(std::vector<char>& data) {
        if (data.empty()) return;

        for (size_t i = 0; i < data.size(); ++i) {
            uint8_t byte = (uint8_t)data[i];

            // 1. 获取当前流位置的动态密钥
            uint8_t k = getDynamicKey(i);

            // 2. 异或加密
            byte = byte ^ k;

            // 3. 注入位置扰动 (Add Index)
            byte = byte + (i % 0xFF);

            // 4. 正向循环位移 (ROL 3)
            byte = (byte << 3) | (byte >> 5);

            data[i] = (char)byte;
        }
    }

}