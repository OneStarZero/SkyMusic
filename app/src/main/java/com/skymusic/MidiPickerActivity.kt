package com.skymusic

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Base64
import android.widget.Toast
import java.io.InputStream

class MidiPickerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*" // 允许所有，靠下面拦截
        }
        
        try {
            startActivityForResult(intent, 1001)
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开文件管理器", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == 1001 && resultCode == RESULT_OK) {
            val uri: Uri? = data?.data
            if (uri != null) {
                try {
                    var fileName = "未知文件.mid"
                    contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (nameIndex != -1) fileName = cursor.getString(nameIndex)
                        }
                    }

                    // 🌟 严格拦截非 MIDI 文件
                    if (!fileName.lowercase().endsWith(".mid") && !fileName.lowercase().endsWith(".midi")) {
                        Toast.makeText(this, "请选择 .mid 或 .midi 文件！", Toast.LENGTH_SHORT).show()
                        finish()
                        return
                    }

                    // 瞬间读取并转为 Base64
                    val inputStream: InputStream? = contentResolver.openInputStream(uri)
                    val bytes = inputStream?.readBytes()
                    inputStream?.close()

                    if (bytes != null) {
                        val base64Data = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        val resultIntent = Intent().apply {
                            putExtra("base64Data", base64Data)
                            putExtra("fileName", fileName)
                        }
                        setResult(Activity.RESULT_OK, resultIntent)
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "读取失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
        finish()
    }
}