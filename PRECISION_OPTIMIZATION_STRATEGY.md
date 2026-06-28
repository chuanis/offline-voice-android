# AegisVoice 精度优化策略（75% → 85%+）

## 📊 当前状态分析

- **字面准确率**：75%
- **语义理解率**：92%
- **主要错误类型**：
  - 专有名词错误（未输入热词）
  - 常见词汇混淆（there/their, its/it's）
  - 结构错误（语法、标点）

## 🎯 核心问题诊断

### 问题1：FuzzyMatchUtils 只处理用户热词
- **现状**：`correct()` 方法只接收 `userHotwords`
- **损失**：系统词库（lexicon_base.dat, lexicon_law.dat等）未被利用
- **影响**：大量常见专有名词无法纠错

### 问题2：缺少常见错误模式库
- **现状**：没有内置常见混淆词库
- **损失**：there/their, its/it's, affect/effect 等无法自动纠错
- **影响**：约5-8%的准确率损失

### 问题3：未利用上下文信息
- **现状**：`previousContext` 存在但未用于纠错
- **损失**：上下文相关的纠错能力缺失
- **影响**：约2-3%的准确率损失

## 🚀 优化方案（V5.0）

### 方案1：扩展 FuzzyMatchUtils 支持系统词库（+5-7%）

**核心改动**：
1. `correct()` 方法增加 `systemWords` 参数
2. 多轮纠错：先系统词库，再用户热词
3. 优先级：用户热词 > 系统词库

**实现要点**：
```kotlin
fun correct(text: String, userHotwords: List<String>, systemWords: List<String>): String {
    // 第一轮：系统词库纠错（常见专有名词）
    var processed = correctWithWordList(text, systemWords)
    // 第二轮：用户热词纠错（个性化）
    processed = correctWithWordList(processed, userHotwords)
    return processed
}
```

### 方案2：内置常见错误模式库（+3-5%）

**核心改动**：
1. 新增 `CommonErrorPatterns` 对象
2. 内置500+常见混淆词对
3. 在系统词库纠错之前执行

**错误模式示例**：
- there → their, they're
- its → it's
- affect → effect
- principle → principal
- complement → compliment

**实现要点**：
```kotlin
object CommonErrorPatterns {
    private val ERROR_MAP = mapOf(
        "there" to listOf("their", "they're"),
        "its" to listOf("it's"),
        // ... 500+ 常见错误
    )
    
    fun correct(text: String): String {
        // 基于上下文判断正确词汇
    }
}
```

### 方案3：上下文感知纠错（+2-3%）

**核心改动**：
1. `correct()` 方法增加 `context: String` 参数
2. 利用上下文判断词汇正确性
3. 例如："I went there" → "I went there"（不是"their"）

**实现要点**：
```kotlin
fun correct(text: String, userHotwords: List<String>, 
             systemWords: List<String>, context: String = ""): String {
    // 使用上下文信息辅助判断
    // 例如：检测 "there/their" 时，查看上下文是地点还是所有格
}
```

### 方案4：音素相似度匹配（+1-2%）

**核心改动**：
1. 新增音素相似度算法（Soundex/Metaphone）
2. 处理发音相似但拼写不同的词
3. 例如："Sam Altman" vs "Sam Oppmann"

**实现要点**：
```kotlin
private fun phoneticSimilarity(s1: String, s2: String): Double {
    // Soundex 或 Metaphone 算法
    // 返回相似度分数 0.0-1.0
}
```

### 方案5：优化 Prompt 策略（+1-2%）

**核心改动**：
1. 动态调整 Prompt 长度（当前350字符可能不够）
2. 根据场景更精准地注入词库
3. 优化词库排序（高频词优先）

**实现要点**：
- 将 Prompt 长度从350扩展到500字符
- 优先注入高频专有名词
- 根据 `previousContext` 动态调整 Prompt

## 📈 预期效果

| 优化项 | 预期提升 | 累计准确率 |
|--------|---------|-----------|
| 当前基线 | - | 75% |
| 系统词库纠错 | +5-7% | 80-82% |
| 常见错误模式 | +3-5% | 83-87% |
| 上下文感知 | +2-3% | 85-90% |
| 音素匹配 | +1-2% | 86-92% |
| Prompt优化 | +1-2% | **87-94%** |

**保守估计：75% → 85%+**
**理想情况：75% → 90%+**

## ⚡ 实施优先级

### Phase 1（立即实施，预期+5-7%）
1. ✅ 扩展 FuzzyMatchUtils 支持系统词库
2. ✅ 修改 AegisInputService 传递系统词库

### Phase 2（1周内，预期+3-5%）
3. ✅ 内置常见错误模式库
4. ✅ 优化 Prompt 策略

### Phase 3（2周内，预期+2-3%）
5. ✅ 上下文感知纠错
6. ✅ 音素相似度匹配

## 🔧 技术实现细节

### 修改点1：FuzzyMatchUtils.kt
- `correct()` 方法签名扩展
- 新增 `correctWithWordList()` 辅助方法
- 新增 `CommonErrorPatterns` 对象

### 修改点2：AegisInputService.kt
- `processCurrentChunk()` 中调用 `FuzzyMatchUtils.correct()` 时传递系统词库
- 从 `AegisGrammarHelper` 获取系统词库

### 修改点3：AegisGrammarHelper.kt
- 新增 `getSystemWords(scenarioCode: String)` 方法
- 返回当前场景的系统词库列表

## ⚠️ 注意事项

1. **性能影响**：系统词库可能很大（1000+词），需要优化匹配算法
2. **误伤风险**：过度纠错可能误改正确词汇，需要严格阈值
3. **内存占用**：系统词库缓存可能增加内存，需要监控

## 📝 测试策略

1. **单元测试**：FuzzyMatchUtils 各场景
2. **集成测试**：完整录音→转录→纠错流程
3. **A/B测试**：对比优化前后准确率



