package com.aegis.voice.keyboard

import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.os.Handler
import android.os.Looper
import com.aegis.voice.AegisInputService
import com.aegis.voice.R

class KeyboardUIHelper(private val service: AegisInputService, private val rootView: View) {

    private val cachedQwertyButtons = ArrayList<Button>()
    private val deleteHandler = Handler(Looper.getMainLooper())

    // 🚀 长按连续空格的专属 Handler
    private val spaceHandler = Handler(Looper.getMainLooper())

    private val voiceSymbolHelper = SuperSymbolHelper(service, service.layoutVoiceMain)
    private val qwertySymbolHelper = SuperSymbolHelper(service, service.layoutQwerty)

    private var lastSlashClickTime: Long = 0
    private val DOUBLE_CLICK_DELAY = 300L

    private val deleteRunnable = object : Runnable {
        override fun run() {
            val eventDown = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DEL)
            val eventUp = android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DEL)
            service.currentInputConnection?.sendKeyEvent(eventDown)
            service.currentInputConnection?.sendKeyEvent(eventUp)
            service.lastChar = ' '
            deleteHandler.postDelayed(this, 50)
        }
    }

    private val spaceRunnable = object : Runnable {
        override fun run() {
            service.currentInputConnection?.commitText(" ", 1)
            service.lastChar = ' '
            spaceHandler.postDelayed(this, 50)
        }
    }

    private val keyClickListener = View.OnClickListener { v ->
        val btn = v as Button
        val text = btn.text.toString()
        service.currentInputConnection?.commitText(text, 1)
        if (text.isNotEmpty()) service.lastChar = text.last()
        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private val slashClickListener = View.OnClickListener { v ->
        val clickTime = System.currentTimeMillis()
        if (clickTime - lastSlashClickTime < DOUBLE_CLICK_DELAY) {

            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

            val eventDown = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DEL)
            val eventUp = android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DEL)
            service.currentInputConnection?.sendKeyEvent(eventDown)
            service.currentInputConnection?.sendKeyEvent(eventUp)

            if (service.layoutVoiceMain.visibility == View.VISIBLE) {
                val topBar = service.layoutVoiceMain.findViewById<View>(R.id.layoutTopStatusBar)
                voiceSymbolHelper.showPanel(topBar, "VOICE")
            } else if (service.layoutQwerty.visibility == View.VISIBLE) {
                qwertySymbolHelper.showPanel(null, "QWERTY")
            }

            lastSlashClickTime = 0L
        } else {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            service.currentInputConnection?.commitText("/", 1)
            service.lastChar = '/'
            lastSlashClickTime = clickTime
        }
    }

    fun setupAllKeys() {
        val numIds = listOf(R.id.btnNum1, R.id.btnNum2, R.id.btnNum3, R.id.btnNum4, R.id.btnNum5, R.id.btnNum6, R.id.btnNum7, R.id.btnNum8, R.id.btnNum9, R.id.btnNum0)
        numIds.forEach { rootView.findViewById<Button>(it)?.setOnClickListener(keyClickListener) }

        val symIds1 = listOf(R.id.btnSymComma, R.id.btnSymPeriod, R.id.btnSymQuestion, R.id.btnSymExclamation, R.id.btnSymApostrophe, R.id.btnSymQuote, R.id.btnSymDash)
        symIds1.forEach { rootView.findViewById<Button>(it)?.setOnClickListener(keyClickListener) }

        rootView.findViewById<Button>(R.id.btnSymSlash)?.setOnClickListener(slashClickListener)

        // 🚀 这里的残余空格旧代码已被彻底清除，全权交给 setupFunctionKeys 管理！
        setupQwertyPage()
        setupSymbolsPage()
        setupNumpadPage()
    }

    private fun setupQwertyPage() {
        val qwertyIds = listOf(
            R.id.keyQ, R.id.keyW, R.id.keyE, R.id.keyR, R.id.keyT,
            R.id.keyY, R.id.keyU, R.id.keyI, R.id.keyO, R.id.keyP,
            R.id.keyA, R.id.keyS, R.id.keyD, R.id.keyF, R.id.keyG,
            R.id.keyH, R.id.keyJ, R.id.keyK, R.id.keyL,
            R.id.keyZ, R.id.keyX, R.id.keyC, R.id.keyV, R.id.keyB, R.id.keyN, R.id.keyM
        )
        val numberRowMap = mapOf(
            R.id.keyQ to "1", R.id.keyW to "2", R.id.keyE to "3", R.id.keyR to "4", R.id.keyT to "5",
            R.id.keyY to "6", R.id.keyU to "7", R.id.keyI to "8", R.id.keyO to "9", R.id.keyP to "0"
        )
        cachedQwertyButtons.clear()
        qwertyIds.forEach { id ->
            val btn = rootView.findViewById<Button>(id)
            if (btn != null) {
                cachedQwertyButtons.add(btn)
                btn.setOnClickListener(keyClickListener)
                if (numberRowMap.containsKey(id)) {
                    btn.setOnLongClickListener { v ->
                        val number = numberRowMap[id] ?: return@setOnLongClickListener false
                        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        service.currentInputConnection?.commitText(number, 1)
                        if (number.isNotEmpty()) service.lastChar = number.last()
                        true
                    }
                }
            }
        }
        rootView.findViewById<View>(R.id.btnShift)?.setOnClickListener { it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); toggleCapsLockFast() }
        rootView.findViewById<Button>(R.id.btnComma)?.setOnClickListener(keyClickListener)

        rootView.findViewById<Button>(R.id.btnSlashQwerty)?.setOnClickListener(slashClickListener)
        // 🚀 此处的残余空格旧代码已被彻底清除！
    }

    private fun setupSymbolsPage() {
        val symIds2 = listOf(
            R.id.keyAt, R.id.keyHash, R.id.keyDollar, R.id.keyPercent, R.id.keyAmp, R.id.keyUnderscore, R.id.keyLeftParen, R.id.keyRightParen, R.id.keySlash, R.id.keyBackSlash,
            R.id.keyLeftBracket, R.id.keyRightBracket, R.id.keyLeftBrace, R.id.keyRightBrace, R.id.keyLess, R.id.keyMore, R.id.keyStar, R.id.keyCaret, R.id.keyPipe, R.id.keySemiColon,
            R.id.btnChComma, R.id.btnChPeriod, R.id.btnChQuestion, R.id.btnChExclamation, R.id.btnChDun, R.id.btnChLeftQuote, R.id.btnChRightQuote, R.id.btnChColon, R.id.btnChEllipsis, R.id.btnChWave
        )
        symIds2.forEach { rootView.findViewById<Button>(it)?.setOnClickListener(keyClickListener) }
    }

    private fun setupNumpadPage() {
        val numpadIds = listOf(
            R.id.keyNum1, R.id.keyNum2, R.id.keyNum3, R.id.keyNum4, R.id.keyNum5,
            R.id.keyNum6, R.id.keyNum7, R.id.keyNum8, R.id.keyNum9, R.id.keyNum0,
            R.id.key00Num, R.id.keyPercentNum, R.id.keyCommaNum, R.id.keyDotNum,
            R.id.keyPlusNum, R.id.keyMinusNum, R.id.keyMultNum, R.id.keyDivNum, R.id.keyEqualNum
        )
        numpadIds.forEach { rootView.findViewById<Button>(it)?.setOnClickListener(keyClickListener) }
        // 🚀 此处的残余空格旧代码已被彻底清除！
    }

    fun setupFunctionKeys() {
        rootView.findViewById<View>(R.id.btnSwitchToQwerty)?.setOnClickListener { it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); showPage(2) }

        listOf(R.id.btnDelete, R.id.btnDeleteQwerty, R.id.btnDeleteSymbols, R.id.btnDeleteNum).forEach { id -> bindDeleteAction(rootView.findViewById(id)) }
        listOf(R.id.btnEnter, R.id.btnEnterQwerty, R.id.btnEnterNum).forEach { id -> bindEnterAction(rootView.findViewById(id)) }

        // 🚀 核心修复点：涵盖了所有4个页面的空格键！包括刚才重命名的 btnSpaceSymbols！
        listOf(R.id.btnSymSpace, R.id.btnSpaceQwerty, R.id.btnSpaceNum, R.id.btnSpaceSymbols).forEach { id ->
            bindSpaceAction(rootView.findViewById(id))
        }

        rootView.findViewById<View>(R.id.btnSwitchToSymbols)?.setOnClickListener { it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); showPage(3) }
        rootView.findViewById<View>(R.id.btnSwitchToNumpadQwerty)?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            showPage(4)
        }
        rootView.findViewById<View>(R.id.btnSwitchToNumpad)?.setOnClickListener { it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); showPage(4) }
        listOf(R.id.btnBackToQwerty, R.id.btnBackToQwertyFromNum).forEach { id -> rootView.findViewById<View>(id)?.setOnClickListener { it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); showPage(2) } }
        listOf(R.id.btnBackToVoiceSym, R.id.btnBackToVoice, R.id.btnMicFromNum).forEach { id -> rootView.findViewById<View>(id)?.setOnClickListener { it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); showPage(1) } }
        rootView.findViewById<View>(R.id.btnBackToSymFromNum)?.setOnClickListener { it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); showPage(3) }
    }

    // 🚀 完美的空格长按连发逻辑：加入 isPressed 视觉反馈
    private fun bindSpaceAction(view: View?) {
        view?.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true // 触发按钮按下的视觉效果
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    service.currentInputConnection?.commitText(" ", 1)
                    service.lastChar = ' '
                    spaceHandler.postDelayed(spaceRunnable, 400)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false // 恢复按钮颜色
                    spaceHandler.removeCallbacks(spaceRunnable)
                    true
                }
                else -> false
            }
        }
    }

    // 🚀 完美的长按删除逻辑：同样加入 isPressed 视觉反馈
    private fun bindDeleteAction(view: View?) {
        view?.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true // 触发按钮按下的视觉效果
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    val eventDown = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DEL)
                    val eventUp = android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DEL)
                    service.currentInputConnection?.sendKeyEvent(eventDown)
                    service.currentInputConnection?.sendKeyEvent(eventUp)
                    service.lastChar = ' '
                    deleteHandler.postDelayed(deleteRunnable, 400)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false // 恢复按钮颜色
                    deleteHandler.removeCallbacks(deleteRunnable)
                    true
                }
                else -> false
            }
        }
    }

    private fun bindEnterAction(view: View?) {
        view?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            service.currentInputConnection?.performEditorAction(EditorInfo.IME_ACTION_SEND)
            service.currentInputConnection?.commitText("\n", 1)
            service.lastChar = '\n'
        }
    }

    fun toggleCapsLockFast() {
        service.isCapsLock = !service.isCapsLock
        for (btn in cachedQwertyButtons) {
            val char = btn.text.toString()
            if (service.isCapsLock) btn.text = char.uppercase() else btn.text = char.lowercase()
        }
    }

    fun showPage(pageIndex: Int) {
        service.layoutVoiceMain.visibility = if (pageIndex == 1) View.VISIBLE else View.GONE
        service.layoutQwerty.visibility = if (pageIndex == 2) View.VISIBLE else View.GONE
        service.layoutSymbols.visibility = if (pageIndex == 3) View.VISIBLE else View.GONE
        service.layoutNumpad.visibility = if (pageIndex == 4) View.VISIBLE else View.GONE
    }
}