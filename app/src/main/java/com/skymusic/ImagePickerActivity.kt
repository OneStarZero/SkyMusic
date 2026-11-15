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
                    // 1. 第一遍：只读边界，不加载像素
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    var inputStream = contentResolver.openInputStream(uri)
                    BitmapFactory.decodeStream(inputStream, null, options)
                    inputStream?.close()

                    // 2. 计算安全压缩比 (目标最大宽度 800px，防止 Base64 太大)
                    var scale = 1
                    val maxWidth = 800
                    if (options.outWidth > maxWidth) {
                        scale = options.outWidth / maxWidth
                    }

                    // 3. 第二遍：读取像素并压缩
                    val bmpOptions = BitmapFactory.Options().apply { inSampleSize = scale }
                    inputStream = contentResolver.openInputStream(uri)
                    var bitmap = BitmapFactory.decodeStream(inputStream, null, bmpOptions)
                    inputStream?.close()

                    if (bitmap != null) {
                        if (bitmap.width > maxWidth) {
                            val ratio = maxWidth.toFloat() / bitmap.width
                            val scaledBitmap = Bitmap.createScaledBitmap(bitmap, maxWidth, (bitmap.height * ratio).toInt(), true)
                            if (scaledBitmap != bitmap) {
                                bitmap.recycle()
                                bitmap = scaledBitmap
                            }
                        }

                        // 4. 压缩为 JPEG 并转 Base64
                        val outputStream = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 75, outputStream)
                        val bytes = outputStream.toByteArray()
                        val base64Image = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        
                        bitmap.recycle()

                        // 5. 回传 Base64
                        val resultIntent = Intent().apply {
                            putExtra("imageBase64", base64Image)
                        }
                        setResult(Activity.RESULT_OK, resultIntent)
                    } else {
                        Toast.makeText(this, "图片解码失败", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: OutOfMemoryError) {
                    Toast.makeText(this, "图片太大，手机内存不足", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "读取图片异常: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
        finish()
    }
}