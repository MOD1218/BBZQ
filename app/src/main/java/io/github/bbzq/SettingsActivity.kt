package io.github.bbzq

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.TextView

class SettingsActivity : Activity() {
    private val prefs by lazy { getSharedPreferences(ModuleSettings.PREFS_NAME, MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F6F7F8"))
            addView(createToolbar())
            addView(
                SettingsContentFactory(this@SettingsActivity, prefs).createScrollView(),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }

        setContentView(root)
    }

    private fun createToolbar(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            elevation = dp(2).toFloat()

            addView(TextView(this@SettingsActivity).apply {
                text = "BBZQ 设置"
                textSize = 20f
                setTextColor(Color.parseColor("#18191C"))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })

            addView(TextView(this@SettingsActivity).apply {
                text = "完成"
                textSize = 15f
                setTextColor(Color.parseColor("#FB7299"))
                isClickable = true
                isFocusable = true
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener { finish() }
            })
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
