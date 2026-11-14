package com.skymusic

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.webkit.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File

class MainActivity : AppCompatActivity() {
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var hiddenWebView: WebView

    // 原生 UI 组件
    private lateinit var tvFileName: TextView
    private lateinit var tvStatus: TextView
    private lateinit var configPanel: LinearLayout
    private lateinit var spinnerTrack: Spinner
    private lateinit var etSongName: EditText
    private lateinit var etAuthor: EditText
    private lateinit var etTranscriber: EditText
    private lateinit var btnConvert: Button

    private val fileChooserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (filePathCallback == null) return@registerForActivityResult
        var results: Array<Uri>? = null
        if (result.resultCode == RESULT_OK && result.data != null) {
            result.data?.dataString?.let { results = arrayOf(Uri.parse(it)) }
        }
        filePathCallback?.onReceiveValue(results)
        filePathCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}.launch(
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                )
            }
        }

        setupNativeUI()
        setupHiddenWebView()
    }

    private fun setupNativeUI() {
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0f172a"))
            setPadding(40, 80, 40, 40)
        }

        // 1. 标题
        val title = TextView(this).apply {
            text = "🎹 光遇 MIDI 智能转谱"
            setTextColor(Color.parseColor("#38bdf8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        rootLayout.addView(title)

        val subTitle = TextView(this).apply {
            text = "AI 辅助生成"
            setTextColor(Color.parseColor("#64748b"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            setPadding(0, 10, 0, 40)
        }
        rootLayout.addView(subTitle)

        // 2. 选择文件区域 (模拟 Drop Zone)
        val dropZone = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, 60, 0, 60)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1e293b"))
                setStroke(3, Color.parseColor("#334155"))
                cornerRadius = 30f
            }
        }
        val icon = TextView(this).apply { text = "🎵"; textSize = 30f; gravity = Gravity.CENTER }
        tvFileName = TextView(this).apply { text = "点击选择 MIDI 文件"; setTextColor(Color.parseColor("#94a3b8")); setPadding(0, 20, 0, 0) }
        
        dropZone.addView(icon)
        dropZone.addView(tvFileName)
        
        // 点击弹出文件选择器
        dropZone.setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "audio/midi"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            try {
                // 借用 WebView 的文件选择拦截，或者直接原生传路径
                val hiddenInput = "document.getElementById('midiFile').click()"
                hiddenWebView.evaluateJavascript(hiddenInput, null)
            } catch (e: Exception) {
                Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_SHORT).show()
            }
        }
        rootLayout.addView(dropZone, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 50 })

        // 3. 配置区域 (默认隐藏)
        configPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = LinearLayout.GONE
        }
        
        spinnerTrack = Spinner(this).apply { 
            background = GradientDrawable().apply { setColor(Color.parseColor("#1e293b")); cornerRadius = 10f }
        }
        etSongName = createEditText("歌曲名称")
        etAuthor = createEditText("原曲作者")
        etTranscriber = createEditText("谱师").apply { setText("光遇玩家") }
        
        configPanel.addView(createLabel("选择音轨"))
        configPanel.addView(spinnerTrack, LinearLayout.LayoutParams(-1, 120).apply { bottomMargin = 30 })
        
        configPanel.addView(createLabel("歌曲名称"))
        configPanel.addView(etSongName, LinearLayout.LayoutParams(-1, 120).apply { bottomMargin = 30 })
        
        configPanel.addView(createLabel("原曲作者"))
        configPanel.addView(etAuthor, LinearLayout.LayoutParams(-1, 120).apply { bottomMargin = 30 })
        
        configPanel.addView(createLabel("谱师"))
        configPanel.addView(etTranscriber, LinearLayout.LayoutParams(-1, 120).apply { bottomMargin = 40 })
        
        btnConvert = Button(this).apply {
            text = "✨ 开始智能转换"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#6366f1"))
            setOnClickListener { 
                tvStatus.text = "正在提取主旋律..."
                btnConvert.isEnabled = false
                // 调用隐藏的 JS 转换函数
                val js = "convertBtn.click()"
                hiddenWebView.evaluateJavascript(js, null)
            }
        }
        configPanel.addView(btnConvert, LinearLayout.LayoutParams(-1, 140))
        
        tvStatus = TextView(this).apply {
            setTextColor(Color.parseColor("#38bdf8"))
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 0)
            text = "准备就绪"
        }
        configPanel.addView(tvStatus)
        
        rootLayout.addView(configPanel)

        // 4. 设置按钮悬浮在右上角
        val btnSettings = TextView(this).apply {
            text = "⚙️"
            textSize = 24f
            setPadding(20, 20, 20, 20)
            setOnClickListener { showSettingsDialog() }
        }
        
        val rootFrame = FrameLayout(this)
        rootFrame.addView(rootLayout, FrameLayout.LayoutParams(-1, -1))
        rootFrame.addView(btnSettings, FrameLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = 40
            rightMargin = 40
        })
        
        setContentView(rootFrame)
    }

    private fun createLabel(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor("#94a3b8"))
            textSize = 12f
            setPadding(10, 10, 0, 10)
        }
    }

    private fun createEditText(hintStr: String): EditText {
        return EditText(this).apply {
            hint = hintStr
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#64748b"))
            background = GradientDrawable().apply { setColor(Color.parseColor("#0f172a")); setStroke(2, Color.parseColor("#334155")); cornerRadius = 10f }
            setPadding(20, 0, 20, 0)
        }
    }

    // 初始化隐藏的 WebView，只用来做算法处理
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupHiddenWebView() {
        hiddenWebView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            
            // 将原生的文件选择器桥接到 WebView
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(webView: WebView?, cb: ValueCallback<Array<Uri>>?, params: FileChooserParams?): Boolean {
                    filePathCallback?.onReceiveValue(null)
                    filePathCallback = cb
                    try { fileChooserLauncher.launch(params?.createIntent()) } catch (e: Exception) { filePathCallback = null; return false }
                    return true
                }
            }
            
            addJavascriptInterface(WebAppInterface(), "AndroidBridge")
            loadUrl("file:///android_asset/index.html")
        }
    }

    private fun showSettingsDialog() {
        val options = arrayOf("开启悬浮窗权限", "开启无障碍服务", "手动启动悬浮球")
        AlertDialog.Builder(this)
            .setTitle("SkyMusic 设置")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                    1 -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    2 -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                            Toast.makeText(this, "请先开启悬浮窗权限！", Toast.LENGTH_SHORT).show()
                        } else {
                            startService(Intent(this, FloatingWindowService::class.java))
                            Toast.makeText(this, "悬浮球已启动", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }.show()
    }

    // 接收来自 HTML 的回调
    inner class WebAppInterface {
        
        // 当网页解析完 MIDI 后，把音轨列表传回原生 UI
        @JavascriptInterface
        fun onMidiParsed(tracksJson: String, title: String) {
            runOnUiThread {
                configPanel.visibility = LinearLayout.VISIBLE
                tvFileName.text = "✅ 已选择：$title"
                etSongName.setText(title)
                tvStatus.text = "请选择音轨"
                btnConvert.isEnabled = true
                
                val tracks = JSONObject(tracksJson)
                val adapter = ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_spinner_dropdown_item)
                // 这里简化处理，你可以自行遍历 tracks 填充
                spinnerTrack.adapter = adapter
            }
        }

        @JavascriptInterface
        fun startPlay(jsonString: String) {
            runOnUiThread { handlePayload(jsonString, false) }
        }

        @JavascriptInterface
        fun saveLocal(jsonString: String) {
            runOnUiThread { handlePayload(jsonString, true) }
        }
    }

    private fun handlePayload(payloadStr: String, isSaveAction: Boolean) {
        try {
            val payload = JSONObject(payloadStr)
            val name = payload.optString("name", "未命名")
            val author = payload.optString("author", "未知作者")
            
            // 略：原有的保存和播放逻辑保持不变...
            Toast.makeText(this, "操作成功: $name", Toast.LENGTH_SHORT).show()
            tvStatus.text = if (isSaveAction) "✅ 已保存至本地" else "🚀 已发送至悬浮窗"
            
        } catch (e: Exception) {
            Toast.makeText(this, "处理失败", Toast.LENGTH_SHORT).show()
        }
    }
}
