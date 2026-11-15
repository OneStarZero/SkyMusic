package com.skymusic

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.io.InputStream

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
                    val inputStream: InputStream? = contentResolver.openInputStream(uri)
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(inputStream, null, options)
                    inputStream?.close()

                    // 计算缩放比例，防止 OOM 闪退
                    val maxWidth = 800
                    var scale = 1
                    if (options.outWidth > maxWidth) {
                        scale = options.outWidth / maxWidth
                    }

                    val inputStream2: InputStream? = contentResolver.openInputStream(uri)
                    val bmpOptions = BitmapFactory.Options().apply { inSampleSize = scale }
                    val bitmap = BitmapFactory.decodeStream(inputStream2, null, bmpOptions)
                    inputStream2?.close()

                    if (bitmap != null) {
                        val outputStream = ByteArrayOutputStream()
                        // 极限压缩质量，解决闪退
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
                        val bytes = outputStream.toByteArray()
                        val base64Image = Base64.encodeToString(bytes, Base64.NO_WRAP)

                        val resultIntent = Intent().apply {
                            putExtra("imageBase64", base64Image)
                        }
                        setResult(Activity.RESULT_OK, resultIntent)
                    } else {
                        Toast.makeText(this, "读取图片失败", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "读取图片异常: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
        finish()
    }
}