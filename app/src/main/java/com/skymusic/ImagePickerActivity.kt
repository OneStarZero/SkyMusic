package com.skymusic

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class ImagePickerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*" 
        }
        try { startActivityForResult(intent, 1002) } catch (e: Exception) { finish() }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1002 && resultCode == RESULT_OK) {
            val uri: Uri? = data?.data
            if (uri != null) {
                try {
                    val image = InputImage.fromFilePath(this, uri)
                    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                    recognizer.process(image)
                        .addOnSuccessListener { visionText ->
                            val safeText = visionText.text.replace("\\", "\\\\").replace("\n", "\\n").replace("'", "\\'")
                            val resultIntent = Intent().apply { putExtra("ocrText", safeText) }
                            setResult(Activity.RESULT_OK, resultIntent)
                        }
                        .addOnFailureListener { e ->
                            Toast.makeText(this, "识别失败: ${e.message}", Toast.LENGTH_SHORT).show()
                            finish()
                        }
                        .addOnCompleteListener { finish() }
                } catch (e: Exception) {
                    Toast.makeText(this, "读取图片失败", Toast.LENGTH_SHORT).show()
                    finish()
                }
            } else { finish() }
        } else { finish() }
    }
}