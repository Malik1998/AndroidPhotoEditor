package com.example.myapplication

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.graphics.get
import androidx.core.graphics.set
import com.google.mlkit.vision.face.FaceLandmark
import com.google.mlkit.vision.pose.PoseLandmark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.lang.Math.abs
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.text.SimpleDateFormat
import java.util.Locale

class ImageSaver {

    companion object {
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"



        fun savePhotoWithBackground(curImage: Bitmap, upperBitmap: Bitmap, contentResolver: ContentResolver) {
            val canvas = Canvas(curImage)
            canvas.drawBitmap(upperBitmap, 0F,
                0F, Paint()
            )
            saveMediaToStorage(curImage, contentResolver)
        }

        fun saveMediaToStorage(bitmap: Bitmap, contentResolver: ContentResolver) {
            //Generating a file name

            val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
                .format(System.currentTimeMillis()) + "_modified.jpg"


            //Output stream
            var fos: OutputStream? = null

            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    if(Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/DeveloperApp")
                    }
                }
                // RELATIVE_PATH and IS_PENDING are introduced in API 29.

                val uri: Uri? =
                    contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    saveImageToStream(bitmap, contentResolver.openOutputStream(uri))
                    values.put(MediaStore.Images.Media.IS_PENDING, false)
                    contentResolver.update(uri, values, null, null)
                }
            } else {
                //For devices running android >= Q
                val imagesDir =
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val image = File(imagesDir, name)
                fos = FileOutputStream(image)
            }

            fos?.use {
                //Finally writing the bitmap to the output stream that we opened

                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)

            }
        }

        private fun saveImageToStream(bitmap: Bitmap, outputStream: OutputStream?) {
            if (outputStream != null) {
                try {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                    outputStream.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

    }
}