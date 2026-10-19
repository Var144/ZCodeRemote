package com.ccwork.zcoderemote

import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/**
 * 配置页：手动填写或扫码填入 ZCode Remote URL，保存后返回。
 * 纯代码构建 UI（与原始实现一致，无 XML layout）。
 */
class ConfigActivity : AppCompatActivity() {

    private lateinit var urlEditText: EditText

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { contents ->
            urlEditText.setText(contents.trim())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val density = resources.displayMetrics.density
        val padding = (32 * density).toInt()
        val match = LinearLayout.LayoutParams.MATCH_PARENT
        val wrap = LinearLayout.LayoutParams.WRAP_CONTENT

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        TextView(this).apply {
            text = getString(R.string.config_title)
            textSize = 18f
            setPadding(0, 0, 0, padding)
            container.addView(this)
        }

        urlEditText = EditText(this).apply {
            hint = "粘贴 ZCode Remote URL"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(UrlStore.read(this@ConfigActivity))
            isSingleLine = false
            layoutParams = LinearLayout.LayoutParams(match, wrap).apply { bottomMargin = padding }
            container.addView(this)
        }

        Button(this).apply {
            text = "扫一扫"
            setOnClickListener { startScan() }
            layoutParams = LinearLayout.LayoutParams(match, wrap).apply { bottomMargin = padding / 2 }
            container.addView(this)
        }

        Button(this).apply {
            text = "保存"
            setOnClickListener { saveAndExit() }
            layoutParams = LinearLayout.LayoutParams(match, wrap).apply { bottomMargin = padding / 2 }
            container.addView(this)
        }

        TextView(this).apply {
            text = "提示：在桌面端 ZCode 左下角点手机图标 → 扫码或复制链接 → 粘贴到这里\n" +
                "v4 链接首次打开若要求登录：在弹出的登录窗口完成 Z.ai / BigModel 登录，返回后自动继续远控"
            textSize = 13f
            setTextColor(0xFF888888.toInt())
            setPadding(0, padding, 0, 0)
            container.addView(this)
        }

        setContentView(container)
    }

    private fun startScan() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("将 ZCode 二维码对准框内")
            setBeepEnabled(true)
            setOrientationLocked(false)
        }
        scanLauncher.launch(options)
    }

    private fun saveAndExit() {
        val url = urlEditText.text.toString().trim()
        if (!url.startsWith("http", ignoreCase = true)) {
            Toast.makeText(this, "链接需以 http 或 https 开头", Toast.LENGTH_LONG).show()
            return
        }
        UrlStore.write(this, url)
        setResult(RESULT_OK)
        finish()
    }
}
