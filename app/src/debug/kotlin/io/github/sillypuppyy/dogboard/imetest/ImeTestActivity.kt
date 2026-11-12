package io.github.sillypuppyy.dogboard.imetest

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import io.github.sillypuppyy.dogboard.perf.Perf

/**
 * Debug-only screen with a single text field that opens the keyboard on launch. Used by
 * `tools/perf/ime_startup.py` to measure how long the keyboard takes to appear without touching
 * any other app.
 *
 * It logs keyboard-independent marks, so it can time any installed keyboard:
 * - "test field requests keyboard": the field asked for the keyboard
 * - "ime visible": the system first reports keyboard insets (keyboard window is up)
 * - "ime shown": the keyboard's slide-in animation finished
 */
class ImeTestActivity : Activity() {
    private var reportedVisible = false
    private var reportedShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        val field = EditText(this).apply {
            hint = "DogBoard test field"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        setContentView(field)
        field.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                android.util.Log.i("DogBoardText", "text=[${s ?: ""}]")
            }
        })

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.decorView.setOnApplyWindowInsetsListener { view, insets ->
                if (!reportedVisible && insets.isVisible(WindowInsets.Type.ime())) {
                    reportedVisible = true
                    Perf.mark("ime visible")
                }
                view.onApplyWindowInsets(insets)
            }
            window.decorView.setWindowInsetsAnimationCallback(
                object : WindowInsetsAnimation.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                    override fun onProgress(
                        insets: WindowInsets,
                        runningAnimations: MutableList<WindowInsetsAnimation>,
                    ): WindowInsets = insets

                    override fun onEnd(animation: WindowInsetsAnimation) {
                        val isIme = animation.typeMask and WindowInsets.Type.ime() != 0
                        val visible = window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
                        if (isIme && visible && !reportedShown) {
                            reportedShown = true
                            Perf.mark("ime shown")
                        }
                    }
                }
            )
        }

        field.requestFocus()
        field.post {
            Perf.mark("test field requests keyboard")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // waits for the keyboard service to connect, unlike showSoftInput on a cold start
                field.windowInsetsController?.show(WindowInsets.Type.ime())
            } else {
                getSystemService(InputMethodManager::class.java)?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }
}
