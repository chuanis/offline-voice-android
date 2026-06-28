package com.aegis.voice.keyboard

import android.graphics.Color
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.aegis.voice.AegisInputService
import com.aegis.voice.R

// 数据结构：去掉了中文 name 属性，直接使用 tag 作为 UI 显示
data class SuperCategory(val tag: String, val symbols: List<String>)

class SuperSymbolHelper(private val service: AegisInputService, private val rootView: View) {

    // 🚀 终极 20 大金刚：UI 纯英文显示，中文作为注释永久保留！
    private val allCategories = listOf(
        // 原 zm (字母) - 首页拼写补救核心
        SuperCategory("ABC", listOf("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m", "n", "o", "p", "q", "r", "s", "t", "u", "v", "w", "x", "y", "z", "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z")),
        // 原 sz (数字) - 26键快速验证码核心
        SuperCategory("NUM", listOf("0", "1", "2", "3", "4", "5", "6", "7", "8", "9")),
        // 原 fh (符号)
        SuperCategory("SYM", listOf("@", "#", "&", "*", "_", "~", "/", "\\", "|", "`", "^", "$", "©", "®", "™")),
        // 原 bd (标点) - 欧美高级排版必备
        SuperCategory("PUN", listOf("—", "–", "•", "¿", "¡", "…", "„", "”", "“", "’", "‘", "«", "»", "‹", "›")),
        // 原 lm (罗马)
        SuperCategory("ROM", listOf("Ⅰ", "Ⅱ", "Ⅲ", "Ⅳ", "Ⅴ", "Ⅵ", "Ⅶ", "Ⅷ", "Ⅸ", "Ⅹ", "Ⅺ", "Ⅻ", "Ⅼ", "Ⅽ", "Ⅾ", "Ⅿ", "ⅰ", "ⅱ", "ⅲ", "ⅳ", "ⅴ", "ⅵ", "ⅶ", "ⅷ", "ⅸ", "ⅹ", "ⅺ", "ⅻ", "ⅼ", "ⅽ", "ⅾ", "ⅿ")),
        // 原 bq (表情)
        SuperCategory("EMO", listOf("😀", "😁", "😂", "🤣", "😃", "😄", "😅", "😆", "😉", "😊", "😋", "😎", "😍", "😘", "😗", "😙", "😚", "☺️", "🙂", "🤗", "🤩", "🤔", "🤨", "😐", "😑", "😶", "🙄", "😏", "😣", "😥", "😮", "🤐", "😯", "😪", "😫", "😴", "😌", "😛", "😜", "😝", "🤤", "😒", "😓", "😔", "😕", "🙃", "🤑", "😲", "☹️", "🙁", "😖", "😞", "😟", "😤", "😢", "😭", "😦", "😧", "😨", "😩", "🤯", "😬", "😰", "😱", "😳", "🤪", "😵", "😠", "😡", "🤬", "😷", "🤒", "🤕", "🤢", "🤮", "🤧", "😇", "🤠", "🤡", "🤥", "🤫", "🤭", "🧐", "🤓", "🍜", "🍚", "🍲", "🥘", "🥟", "🥠", "🥡", "🥢", "❤️", "🧡", "💛", "💚", "💙", "🩵", "🩷", "💜", "🤎", "🖤", "🩶", "🤍", "🐁", "🐀", "🐂", "🐃", "🐄", "🦬", "🐅", "🐆", "🐇", "🐉", "🐍", "🐎", "🐏", "🐐", "🐑", "🐒", "🦧", "🦍", "🦃", "🐓", "🐕", "🦮", "🐕‍🦺", "🐖", "🐗")),
        // 原 ss (手势)
        SuperCategory("HND", listOf("👌", "👍", "👎", "👊", "🤝", "✌️", "💪", "🙏", "✊", "👏", "👋", "🤞", "✋", "🤚", "🖖", "🤙", "🖕", "🤟", "☝️", "👆", "👇", "👈", "👉", "🤛", "🤜", "🤲", "🤘", "🙌", "👐", "✍️", "💅")),
        // 原 sx (数学)
        SuperCategory("MTH", listOf( "＋", "－", "×", "÷", "±", "∓", "＝", "≠", "≈", "π", "sin", "cos", "tan", "log", "ln", "lim","≡", "＜", "＞", "≤", "≥", "≮", "≯", "∈", "∉", "⊂", "⊃", "⊆", "⊇", "∪", "∩", "∑", "∏", "∫", "∬", "∭", "∮", "∇", "Δ", "∕", "√", "∛", "∜", "∝", "∞", "∟", "∠", "∥", "⊥", "∧", "∨", "∴", "∵", "∷", "∽", "≌", "≒", "⊕", "⊙", "⊿", "㏑", "㏒")),
        // 原 dw (单位)
        SuperCategory("UNT", listOf("℃", "℉", "㎡", "㎥", "％", "‰", "‱", "°", "㎝", "㎜", "㎞", "㎖", "㎎", "㎏", "㎐", "㎑", "㎒", "㎓", "㏈", "㎈", "㎉", "㎫", "㎪", "㎧", "㎨", "㎰", "㎱", "㎲", "㎳", "㎴", "㎵", "㎶", "㎷", "㎸", "㎹", "㎺", "㎻", "㎼", "㎽", "㎾", "㎿", "㏀", "㏁", "㏂")),
        // 原 hb (货币)
        SuperCategory("CUR", listOf("＄", "$","￥", "¥",  "€", "￡", "£", "￠", "¤", "৳", "฿", "₠", "₡", "₢", "₣", "₤", "₥", "₦", "₧", "₩", "₪", "₫", "₭", "₮", "₯", "₰", "₱", "₲", "₳", "₴", "₵", "₶", "₷", "₸", "₹", "₺", "₨")),
        // 原 jt (箭头)
        SuperCategory("ARR", listOf("→", "↑", "↓", "←", "↕", "↔", "↖", "↗", "↙", "↘", "↚", "↛", "↮", "↜", "↝", "↞", "↟", "↠", "↡", "↢", "↣", "↤", "↥", "↦", "↧", "↨", "↩", "↪", "↫", "↬", "↭", "↯", "↰", "↱", "↲", "↳", "↴", "↵", "↶", "↷", "↸", "↹", "↺", "↻", "↼", "↽", "↾", "↿", "⇀", "⇁", "⇂", "⇃", "⇄", "⇅", "⇆", "⇇", "⇈", "⇉", "⇊", "⇋", "⇌", "⇐", "⇍", "⇑", "⇒", "⇏", "⇓", "⇔", "⇎")),
        // 新增 (重音) - 欧美借词输入神器
        SuperCategory("ACC", listOf("á", "é", "í", "ó", "ú", "ñ", "ü", "ç", "à", "è", "ì", "ò", "ù", "â", "ê", "î", "ô", "û", "ä", "ë", "ï", "ö", "ÿ")),
        // 新增 (分数)
        SuperCategory("FRC", listOf("½", "¼", "¾", "⅓", "⅔", "⅕", "⅖", "⅗", "⅘", "⅙", "⅚", "⅛", "⅜", "⅝", "⅞")),
        // 原 sb (上标) - 极致对称版：15个数字符号 + 18个纯正小写字母 (剔除了下标没有的 b,c,d,f,g,q,w,z)
        SuperCategory("SUP", listOf("⁰", "¹", "²", "³", "⁴", "⁵", "⁶", "⁷", "⁸", "⁹", "⁺", "⁻", "⁼", "⁽", "⁾", "ᵃ", "ᵉ", "ʰ", "ⁱ", "ʲ", "ᵏ", "ˡ", "ᵐ", "ⁿ", "ᵒ", "ᵖ", "ʳ", "ˢ", "ᵗ", "ᵘ", "ᵛ", "ˣ", "ʸ")),
        // 原 xb (下标) - 极致对称版：15个数字符号 + 18个纯正小写字母 (与上标 100% 一一对应)
        SuperCategory("SUB", listOf("₀", "₁", "₂", "₃", "₄", "₅", "₆", "₇", "₈", "₉", "₊", "₋", "₌", "₍", "₎", "ₐ", "ₑ", "ₕ", "ᵢ", "ⱼ", "ₖ", "ₗ", "ₘ", "ₙ", "ₒ", "ₚ", "ᵣ", "ₛ", "ₜ", "ᵤ", "ᵥ", "ₓ", "ᵧ")),// 新增 (编程)
        // 编程
        SuperCategory("DEV", listOf("{", "}", "[", "]", "<", ">", "|", "\\", "~", "^", "_", "`")),
        // 原 yy (音乐)
        SuperCategory("MUS", listOf("♩", "♪", "♫", "♬", "♭", "♮", "♯")),
        // 原 dwj/zc (食物)
        SuperCategory("FOD", listOf("🍔", "🍕", "🍟", "🌭", "🍿", "🥓", "🥞", "🍞", "🥐", "🍺", "🍻", "🥂", "🍷", "🍹", "☕")),
        // 原 tq/zr (自然)
        SuperCategory("NAT", listOf("☀️", "🌤️", "⛅", "🌥️", "☁️", "🌦️", "🌧️", "⛈️", "🌩️", "🌨️", "❄️", "💨", "🌲", "🌳", "🌴", "🔥", "💧")),
        // 原 jh/fk (几何)
        SuperCategory("SHP", listOf("■", "□", "▢", "▣", "▤", "▥", "▦", "▧", "▨", "▩", "▪", "▫", "▬", "▭", "▮", "▯", "▰", "▱", "▲", "△", "▴", "▵", "▶", "▷", "▸", "▹", "►", "▻", "▼", "▽", "▾", "▿", "◀", "◁", "◂", "◃", "◄", "◅", "◆", "◇", "◈", "◉", "◊", "○", "◌", "◍", "◎", "●", "◐", "◑", "◒", "◓", "◔", "◕", "◖", "◗", "◘", "◙", "◚", "◛", "◜", "◝", "◞", "◟", "◠", "◡", "◢", "◣", "◤", "◥", "◦", "◧", "◨", "◩", "◪", "◫", "◬", "◭", "◮", "◯", "◰", "◱", "◲", "◳", "◴", "◵", "◶", "◷", "◸", "◹", "◺", "◻", "◼", "◽", "◾", "◿"))
    )

    // 🚀 双场景定制排序雷达！
    private val voiceOrder = listOf("ABC", "EMO", "PUN", "HND", "CUR", "SYM", "MTH", "UNT", "FRC", "ACC", "ARR", "ROM", "SUP", "SUB", "DEV", "MUS", "FOD", "NAT", "SHP", "NUM")
    private val qwertyOrder = listOf("NUM", "EMO", "ACC", "SYM", "PUN", "HND", "CUR", "MTH", "UNT", "FRC", "ARR", "ROM", "SUP", "SUB", "DEV", "MUS", "FOD", "NAT", "SHP", "ABC")

    private val activeCategories = mutableListOf<SuperCategory>()
    private var currentCategoryIndex = 0
    private var isLocked = false

    private val layoutSuperSymbol: View? = rootView.findViewById(R.id.layoutSuperSymbol)
    private val containerCategories: LinearLayout? = rootView.findViewById(R.id.containerCategories)
    private val containerSymbols: LinearLayout? = rootView.findViewById(R.id.containerSymbols)

    // 🚀 使用 ImageView 接收原生资源
    private val btnSuperLock: ImageView? = rootView.findViewById(R.id.btnSuperLock)
    private val btnSuperClose: ImageView? = rootView.findViewById(R.id.btnSuperClose)

    private var originalTopBar: View? = null

    init {
        setupListeners()
    }

    private fun setupListeners() {
        btnSuperLock?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            isLocked = !isLocked

            // 🚀 这里的 R.drawable.ic_unlock 必须确保你已经放进了 drawable 文件夹！
            btnSuperLock.setImageResource(if (isLocked) R.drawable.ic_lock else R.drawable.ic_unlock)
        }

        btnSuperClose?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            hidePanel()
        }
    }

    // 🚀 核心大招：支持传入 pageMode ("VOICE" 或 "QWERTY")，瞬间完成降维重排！
    fun showPanel(topBarToHide: View?, pageMode: String) {
        originalTopBar = topBarToHide
        originalTopBar?.visibility = View.GONE
        layoutSuperSymbol?.visibility = View.VISIBLE

        // 根据页面模式，提取专属排序数组
        val targetOrder = if (pageMode == "VOICE") voiceOrder else qwertyOrder

        // 瞬间重排
        activeCategories.clear()
        activeCategories.addAll(allCategories.sortedBy { targetOrder.indexOf(it.tag) })
        currentCategoryIndex = 0

        renderCategories()
        renderSymbols()
    }

    private fun renderCategories() {
        containerCategories?.removeAllViews()
        val context = rootView.context

        activeCategories.forEachIndexed { index, cat ->
            val tv = TextView(context).apply {
                text = cat.tag // 🚀 永远显示 3 字母国际缩写
                textSize = 14f
                setPadding(18, 10, 18, 10)
                if (index == currentCategoryIndex) {
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.parseColor("#E53935")) // 高亮红色
                } else {
                    setTextColor(Color.parseColor("#666666"))
                    setBackgroundColor(Color.TRANSPARENT)
                }

                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    currentCategoryIndex = index
                    renderCategories()
                    renderSymbols()
                }
            }

            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(8, 0, 8, 0)
            }
            containerCategories?.addView(tv, params)
        }
    }

    private fun renderSymbols() {
        containerSymbols?.removeAllViews()
        val context = rootView.context
        val currentCat = activeCategories[currentCategoryIndex]

        currentCat.symbols.forEach { sym ->
            val tv = TextView(context).apply {
                text = sym
                textSize = 24f
                setTextColor(Color.BLACK)
                setPadding(30, 0, 30, 0)

                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    service.currentInputConnection?.commitText(sym, 1)
                    service.lastChar = if (sym.isNotEmpty()) sym.last() else ' '

                    if (!isLocked) {
                        hidePanel()
                    }
                }
            }

            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            containerSymbols?.addView(tv, params)
        }
    }

    private fun hidePanel() {
        layoutSuperSymbol?.visibility = View.GONE
        originalTopBar?.visibility = View.VISIBLE
    }
}