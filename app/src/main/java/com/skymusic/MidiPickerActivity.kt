package com.skymusic

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.io.InputStream

class MidiPickerActivity : ComponentActivity() {

    private val pickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) {
            finish()
            return@registerForActivityResult
        }

        try {
            var fileName = "未知文件.mid"
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) fileName = cursor.getString(nameIndex)
                }
            }

            // 再次严苛拦截，如果系统硬塞 mp3 进来，直接拒绝
            if (!fileName.lowercase().endsWith(".mid") && !fileName.lowercase().endsWith(".midi")) {
                Toast.makeText(this, "请选择 .mid 或 .midi 文件！", Toast.LENGTH_SHORT).show()
                finish()
                return@registerForActivityResult
            }

            val inputStream: InputStream? = contentResolver.openInputStream(uri)
            val bytes = inputStream?.readBytes()
            inputStream?.close()

            if (bytes != null) {
                val base64Data = Base64.encodeToString(bytes, Base64.NO_WRAP)
                
                // 把数据广播给 WebView
                val resultIntent = Intent().apply {
                    putExtra("base64Data", base64Data)
                    putExtra("fileName", fileName)
                }
                setResult(Activity.RESULT_OK, resultIntent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "读取失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 只请求音频类型，但很多系统对 mid 类型支持不好，所以设为 */* 配合后缀强拦截
        pickerLauncher.launch("*/*")
    }
}