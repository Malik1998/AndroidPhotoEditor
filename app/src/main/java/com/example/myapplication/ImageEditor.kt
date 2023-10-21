package com.example.myapplication

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.util.Log
import androidx.core.graphics.get
import androidx.core.graphics.set
import com.google.mlkit.vision.face.FaceLandmark
import com.google.mlkit.vision.pose.PoseLandmark
import java.lang.Math.abs
import java.nio.ByteBuffer
import java.nio.FloatBuffer

class ImageEditor {

    companion object {

        val EYE_LASH = "eye_lash"
        val GLASSES = "glasses"
        val EARS = "ears"
        val ASS = "ass"
        val NONE = "none"
        val LIPS = "lips"

        val MASK_CONFIDENSE = 0.99f

        val useFullLandmarks = listOf<Int>(FaceLandmark.LEFT_EAR, FaceLandmark.RIGHT_EAR,
            FaceLandmark.LEFT_EYE, FaceLandmark.RIGHT_EYE, FaceLandmark.MOUTH_LEFT, FaceLandmark.MOUTH_BOTTOM,
            FaceLandmark.MOUTH_RIGHT)

        val bodyLandmarks = listOf<Int>(PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP,
            PoseLandmark.LEFT_KNEE, PoseLandmark.RIGHT_KNEE)

        private val mapImgs = hashMapOf<String, Int>(
            EYE_LASH to R.mipmap.eye_lash,
            GLASSES to R.mipmap.glasses,
            EARS to R.mipmap.ears)

        fun drawImgPoint(
            left: PointF, right: PointF, canvas: Canvas,
            img: Bitmap,
            mirror: Boolean = false,
            imgWidth: Int = 0, imgHeight: Int = 0,
            leftXPrev: Float = 0f, leftYPrev: Float = 0f
        ) {
            val msg = "object = ${left}, object Inner = ${right}"
            Log.i("detection object", msg)
            val imgScaled = Bitmap.createScaledBitmap(
                img,
                imgWidth, imgHeight, true
            )


            val leftX = if (leftXPrev == 0f) left.x - imgWidth / 4f else leftXPrev
            val leftY = if (leftYPrev == 0f) right.y - imgHeight / 3.5f else leftYPrev
            val m = Matrix()
            if (mirror) {
                m.preScale(-1f, 1f)
                canvas.drawBitmap(
                    Bitmap.createBitmap(
                        imgScaled, 0, 0,
                        imgWidth, imgHeight, m, true
                    ), leftX,
                    leftY, Paint()
                )
            } else {
                Log.i("nen", "${leftX} ${leftY}")
                canvas.drawBitmap(
                    imgScaled, leftX,
                    leftY, Paint()
                )
            }


        }

        fun processRequestEditing(context: Context, curImgText: String,
                                  map: Map<Int, PointF>, bitMap: Bitmap,
                                  canvas: Canvas, resources: Resources,
                                  heightFace: Int, widthFace: Int, currentImage: Bitmap,
                                  mask: ByteBuffer? = null, maskWidth: Int = 128,
                                  maskHeight: Int = 128) {
            val leftEyePoint = map.getOrDefault(FaceLandmark.LEFT_EYE, null)
            val rightEyePoint = map.getOrDefault(FaceLandmark.RIGHT_EYE, null)
            val leftEarPoint = map.getOrDefault(FaceLandmark.LEFT_EAR, null)
            val rightEarPoint = map.getOrDefault(FaceLandmark.RIGHT_EAR, null)
            val mouthBottomPoint = map.getOrDefault(FaceLandmark.MOUTH_BOTTOM, null)
            val mouthLeftPoint = map.getOrDefault(FaceLandmark.MOUTH_LEFT, null)
            val mouthRightPoint = map.getOrDefault(FaceLandmark.MOUTH_RIGHT, null)

            val leftHipPoint = map.getOrDefault(PoseLandmark.LEFT_HIP, null)
            val rightHipPoint = map.getOrDefault(PoseLandmark.RIGHT_HIP, null)
            val leftKneePoint = map.getOrDefault(PoseLandmark.LEFT_KNEE, null)
            val rightKneePoint = map.getOrDefault(PoseLandmark.RIGHT_KNEE, null)


            synchronized(context) {
                when (curImgText) {
                    EYE_LASH -> {
                        if (leftEyePoint != null && rightEyePoint != null) {
                            bitMap.eraseColor(Color.TRANSPARENT)
                            drawImgPoint(
                                leftEyePoint,
                                rightEyePoint,
                                canvas,
                                BitmapFactory.decodeResource(
                                    resources,
                                    mapImgs.get(curImgText)!!
                                ),
                                imgWidth = Math.min(
                                    Math.max(
                                        Math.abs(leftEyePoint.x - rightEyePoint.x) * 0.6f,
                                        10f
                                    ), bitMap.width / 1.5f
                                ).toInt(),
                                imgHeight = heightFace / 20,
                                leftXPrev = leftEyePoint.x - Math.abs(leftEyePoint.x - rightEyePoint.x) * 0.3f,
                                leftYPrev = leftEyePoint.y - heightFace * 0.025f,
                            )

                            drawImgPoint(
                                leftEyePoint,
                                rightEyePoint,
                                canvas,
                                BitmapFactory.decodeResource(
                                    resources,
                                    mapImgs.get(curImgText)!!
                                ),
                                imgWidth = Math.min(
                                    Math.max(
                                        Math.abs(leftEyePoint.x - rightEyePoint.x) * 0.6f,
                                        10f
                                    ), bitMap.width / 1.5f
                                ).toInt(),
                                imgHeight = heightFace / 20,
                                leftXPrev = rightEyePoint.x - Math.abs(leftEyePoint.x - rightEyePoint.x) * 0.3f,
                                leftYPrev = rightEyePoint.y - heightFace * 0.025f,
                                mirror = true
                            )
                        }

                    }

                    GLASSES -> {
                        if (leftEyePoint != null && rightEyePoint != null) {

                            bitMap.eraseColor(Color.TRANSPARENT)
                            val imgWidth = Math.min(
                                Math.max(
                                    Math.abs(leftEyePoint.x - rightEyePoint.x) * 2.2f,
                                    20f
                                ), widthFace.toFloat()
                            ).toInt()
                            val imgHeight = heightFace * 2 / 5
                            drawImgPoint(
                                leftEyePoint,
                                rightEyePoint,
                                canvas,
                                BitmapFactory.decodeResource(
                                    resources,
                                    mapImgs.get(curImgText)!!
                                ),
                                imgWidth = imgWidth,
                                imgHeight = imgHeight,
                                leftYPrev = rightEyePoint.y - imgHeight * 0.5f,
                                leftXPrev = leftEyePoint.x - imgWidth / 4,
                            )
                        }
                    }

                    EARS -> {
                        if (leftEarPoint != null && rightEarPoint != null) {
                            bitMap.eraseColor(Color.TRANSPARENT)
                            val earWidth = Math.min(
                                Math.max(
                                    Math.abs(leftEarPoint.x - rightEarPoint.x) * 0.3f,
                                    10f
                                ), bitMap.width / 1.5f
                            ).toInt()
                            val earHeight = heightFace / 2
                            val leftXPrevOffset = (earWidth / 1.1).toInt()
                            val leftYPrevOffset = earHeight
                            drawImgPoint(
                                leftEarPoint,
                                rightEarPoint,
                                canvas,
                                BitmapFactory.decodeResource(
                                    resources,
                                    mapImgs.get(curImgText)!!
                                ),
                                imgWidth = earWidth,
                                imgHeight = earHeight,
                                leftXPrev = leftEarPoint.x - leftXPrevOffset,
                                leftYPrev = leftEarPoint.y - leftYPrevOffset,
                                mirror = false
                            )

                            drawImgPoint(
                                leftEarPoint,
                                rightEarPoint,
                                canvas,
                                BitmapFactory.decodeResource(
                                    resources,
                                    mapImgs.get(curImgText)!!
                                ),
                                imgWidth = earWidth,
                                imgHeight = earHeight,
                                leftXPrev = rightEarPoint.x,//+ leftXPrevOffset,
                                leftYPrev = rightEarPoint.y - leftYPrevOffset,
                                mirror = true
                            )
                        }
                    }

                    LIPS -> {
                        if (mouthBottomPoint != null
                            && mouthLeftPoint != null
                            && mouthRightPoint != null
                        ) {
                            bitMap.eraseColor(Color.TRANSPARENT)


                            val mouthHeight =
                                (mouthBottomPoint.y - mouthLeftPoint.y).toInt()
                            val mouthWidth =
                                (mouthRightPoint.x - mouthLeftPoint.x).toInt()
                            val mouthImg = Bitmap.createBitmap(
                                currentImage,
                                mouthLeftPoint.x.toInt(),
                                mouthLeftPoint.y.toInt() - mouthHeight,
                                mouthWidth,
                                mouthHeight * 2
                            );

//                            if(mask != null) {
//                                for (x in 0..mouthWidth - 1) {
//                                    for(y in 0..mouthHeight - 1) {
//                                        val x1 = ( mouthLeftPoint.x.toInt() + x) * maskWidth / currentImage.width
//                                        val y1 = (mouthLeftPoint.y.toInt() - mouthHeight + y) * maskHeight / currentImage.height
//                                        if (mask[(x1 * maskWidth + y1)] >= MASK_CONFIDENSE) {
//                                            mouthImg.set(x, y, Color.GREEN)
//                                        }
//                                    }
//                                }
//                                Log.i("mask debug", "${mask}, ${currentImage.width}, ${currentImage.height} ${currentImage.width * currentImage.height / 16}")
//                            }

//                            val imgWidth = mouthWidth
//                            val imgHeight = (mouthHeight).toInt()

                            val imgWidth = Math.min(
                                Math.max(
                                    mouthWidth * 1.5f,
                                    20f
                                ), widthFace.toFloat() * 0.6f
                            ).toInt()
                            val imgHeight = (mouthHeight * 2.5).toInt()
                            drawImgPoint(
                                mouthLeftPoint,
                                mouthRightPoint,
                                canvas,
                                mouthImg,
                                imgWidth = imgWidth,
                                imgHeight = imgHeight,
                                leftYPrev = mouthLeftPoint.y - imgHeight / 2,
                                leftXPrev = mouthLeftPoint.x - imgWidth / 8,
                            )
                        }
                    }

                    ASS -> {

                        if (leftHipPoint != null
                            && rightHipPoint != null
                            && leftKneePoint != null
                            && rightKneePoint != null
                        ) {
                            if(mask == null) {
                                return
                            }
                            bitMap.eraseColor(Color.TRANSPARENT)
//
//                            val ratioWidth = currentImage.width / maskWidth
//                            val ratioHeight = currentImage.height / maskHeight
//                            val ASS_MULT = 4
                            val assWidth = abs(leftHipPoint.x - rightHipPoint.x).toInt() * 2
                            val assHeight = abs(leftHipPoint.y - leftKneePoint.y).toInt()

                            Log.i("debug", "assWidth ${assWidth}, assHeight ${assHeight}")
                            Log.i("debug", "rightHipPoint ${rightHipPoint}, leftHipPoint ${leftHipPoint}")
                            Log.i("debug", "currentImage.width ${currentImage.width}, currentImage.height ${currentImage.height}")
                            val lefterPoint = if(leftHipPoint.x < rightHipPoint.x) leftHipPoint else rightHipPoint

                            val leftPointX = lefterPoint.x
                            val topPointY = lefterPoint.y - assHeight / 4
                            val assImg = Bitmap.createBitmap(currentImage,
                                leftPointX.toInt(), topPointY.toInt(), assWidth, assHeight)
                            Log.i("debug", "${maskWidth}, ${maskHeight}, ${mask}")
                            val tempMask = Bitmap.createBitmap(maskWidth , maskHeight , Bitmap.Config.ARGB_8888)
                            mask.rewind()
                            tempMask.copyPixelsFromBuffer(mask)
                            for (x in 0..assWidth - 1) {
                                for(y in 0..assHeight - 1) {
                                    val x1 = (leftPointX + x) * maskWidth / (currentImage.width)
                                    val y1 = (topPointY + y) * maskHeight / (currentImage.height)
                                   // Log.i("color", "${mask[(x1 * maskWidth + y1).toInt()]}")

                                    if (tempMask.get(x1.toInt(), y1.toInt()) != Color.TRANSPARENT) {
//                                        assImg.set(x, y, Color.RED)
                                    } else {
                                        assImg.set(x, y, Color.TRANSPARENT)
                                    }
                                }
                            }
                            Log.i("mask debug", "${mask}, ${currentImage.width}, ${currentImage.height} ${currentImage.width * currentImage.height / 16}")
                            Log.i("debug", "width ${assImg.width}, height ${assImg.height}, mask width ${maskWidth}, mask height ${maskHeight}")
                            val imgWidth = Math.min(
                                Math.max(
                                    assWidth.toFloat() * 2f,
                                    20f
                                ), currentImage.width / 2f
                            ).toInt()
                            val imgHeight = (assHeight).toInt()
                            drawImgPoint(
                                lefterPoint,
                                rightHipPoint,
                                canvas,
                                assImg,
                                imgWidth = imgWidth,
                                imgHeight = imgHeight,
                                leftYPrev = topPointY.toFloat(),
                                leftXPrev = leftPointX.toFloat(),
                            )
                        }
                    }

                    else -> { // Note the block

                    }
                }
            }
        }
    }
}