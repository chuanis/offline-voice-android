#ifndef AEGIS_MODEL_CRYPTO_H
#define AEGIS_MODEL_CRYPTO_H

#include <vector>
#include <cstdint>

/**
 * Aegis Security Module [The Shield]
 * 职责：负责核心资产的加密与解密。
 * 状态：REARMED (逻辑重构 - V13)
 * 算法：Dynamic-Domain-XOR + Position-Shift
 */
namespace AegisSecurity {

    class ModelCrypto {
    public:
        /**
         * 🔐 核心解密函数 (原地解密)
         * 算法：UnShift -> Sub(Pos) -> XOR(DomainKey)
         * 用途：加载 WH 模型、加载本地加密词库 (AssetsLoader)
         */
        static void decryptInPlace(std::vector<char>& data);

        /**
         * 🔒 核心加密函数 (原地加密)
         * 算法：XOR(DomainKey) -> Add(Pos) -> Shift
         * 用途：保存用户热词 (UserDataGuard)
         */
        static void encryptInPlace(std::vector<char>& data);

    private:
        // 🔑 [DECOY] 诱饵密钥声明
        // 为了保持接口二进制兼容性，保留此变量，但实际逻辑中不再使用它。
        static const uint8_t CORE_KEY[32];
    };

}

#endif // AEGIS_MODEL_CRYPTO_H