package com.skymusic

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
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
                    // 1. 读取边界
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    var inputStream = contentResolver.openInputStream(uri)
                    BitmapFactory.decodeStream(inputStream, null, options)
                    inputStream?.close()

                    // 2. 计算压缩比 (最大宽度 1200)
                    var scale = 1
                    val maxDimension = 1200
                    val maxSide = Math.max(options.outWidth, options.outHeight)
                    if (maxSide > maxDimension) scale = maxSide / maxDimension

                    // 3. 读取像素 (RGB_565 省一半内存)
                    val bmpOptions = BitmapFactory.Options().apply { 
                        inSampleSize = scale
                        inPreferredConfig = Bitmap.Config.RGB_565 
                    }
                    inputStream = contentResolver.openInputStream(uri)
                    var bitmap = BitmapFactory.decodeStream(inputStream, null, bmpOptions)
                    inputStream?.close()

                    if (bitmap != null) {
                        if (Math.max(bitmap.width, bitmap.height) > maxDimension) {
                            val ratio = maxDimension.toFloat() / Math.max(bitmap.width, bitmap.height)
                            val scaledBitmap = Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt(), true)
                            if (scaledBitmap != bitmap) {
                                bitmap.recycle()
                                bitmap = scaledBitmap
                            }
                        }

                        // 🌟 4. 不转 Base64，直接存入 App 缓存目录
                        val cacheFile = File(cacheDir, "ocr_temp_${System.currentTimeMillis()}.jpg")
                        val outputStream = FileOutputStream(cacheFile)
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
                        outputStream.flush()
                        outputStream.close()
                        bitmap.recycle()

                        // 🌟 5. 把文件绝对路径传回给网页
                        val resultIntent = Intent().apply {
                            putExtra("imagePath", cacheFile.absolutePath)
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