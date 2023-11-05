package com.example.myapplication

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PointF
import android.media.ExifInterface
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.core.content.ContextCompat
import com.example.myapplication.ImageEditor.Companion.drawImgPoint
import com.example.myapplication.databinding.ActivityEditorBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.Segmenter
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.nio.ByteBuffer
import java.util.Collections


class MainActivity : AppCompatActivity() {
    private lateinit var viewBinding: ActivityEditorBinding

    @Volatile
    private var curImage: Bitmap? = null

    @Volatile
    private var upperBitmap : Bitmap? = null

    @Volatile
    private var curImgText = ImageEditor.NONE

    private lateinit var faceDetector: FaceDetector
    private lateinit var poseDetector: PoseDetector
    private lateinit var segmenter: Segmenter

    private val defaultImgId = R.mipmap.person_photo
    private val IS_DEBUG = true

    private val MULT_IMAGE = 2

    private val conf: Bitmap.Config = Bitmap.Config.ARGB_8888 // see other conf types

    private val mapLandMarks = Collections.synchronizedMap(hashMapOf<Int, PointF>())


    private val drawedThings = Collections.synchronizedList(ArrayList<ImgInstance>())
    private var contextIndex = -1
    private var drawedIndex = -1

    private val OFFSET = 10

    private val mapImgs = hashMapOf<ExtraImgs, Int>(
        ExtraImgs.EYE_LASH to R.mipmap.eye_lash,
        ExtraImgs.GLASSES to R.mipmap.glasses,
        ExtraImgs.EARS to R.mipmap.ears,
        ExtraImgs.CONTEXT_INFO to R.mipmap.remove)


    @Volatile
    private var mask: ByteBuffer? = null
    private var maskWidth: Int  = 128
    private var maskHeight: Int = 128

    @Volatile
    private var heightFace: Int = 0
    @Volatile
    private var widthFace: Int = 0

    private val CONTEXT_INFO_SIZE = 90

    @Composable
    fun LoaderOfFolder() {
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            //When the user has selected a photo, its URI is returned here
            curImage =  MediaStore.Images.Media.getBitmap(this.getContentResolver(), uri)
            val exif = ExifInterface(PathUtil.getPath(baseContext, uri))
            val rotation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            val matrix = Matrix()
            if(rotation == 6) {
                matrix.postRotate(90f)
            } else if (rotation == 3) {
                matrix.postRotate(180f)
            } else if (rotation == 8) {
                matrix.postRotate(270f)
            }

            // TODO: save old size, and then reshape image to this, or even redraw on source image
            curImage = Bitmap.createScaledBitmap(
                curImage!!,
                viewBinding.imageViewEditor.width, viewBinding.imageViewEditor.height, true
            )
            curImage = Bitmap.createBitmap(
                curImage!!, 0, 0,
                curImage!!.width,
                curImage!!.height, matrix, true
            )
            upperBitmap = Bitmap.createBitmap(
                curImage!!, 0, 0,
                curImage!!.width,
                curImage!!.height, matrix, true
            ).copy(Bitmap.Config.ARGB_8888,true)
            upperBitmap!!.eraseColor(Color.TRANSPARENT)

            viewBinding.imageViewEditor.setImageBitmap(curImage)
            viewBinding.imageViewDraw.setImageBitmap(upperBitmap)

            processImage()
        }

        viewBinding.folder.setOnClickListener{
            launcher.launch(
                PickVisualMediaRequest(
                    //Here we request only photos. Change this to .ImageAndVideo if
                    //you want videos too.
                    //Or use .VideoOnly if you only want videos.
                    mediaType = ActivityResultContracts.PickVisualMedia.ImageOnly
                )
            )
        }
    }

    private fun setFilter(context: Context, name: String) {
        val canvas = getCanvas()
        curImgText = if (curImgText != name) name else ImageEditor.NONE
        ImageEditor.processRequestEditing(
            baseContext, curImgText,
            mapLandMarks, upperBitmap!!, canvas,
            resources, heightFace, widthFace, curImage!!,
            mask = mask, maskWidth = maskWidth, maskHeight = maskHeight, drawedThings
        )
        reDraw(canvas)
    }


    private fun getCanvas(): Canvas {
        upperBitmap = Bitmap.createBitmap(
            upperBitmap!!.width,
            upperBitmap!!.height, conf
        )
        return Canvas(upperBitmap!!)
    }

    private fun processImage() {
        val resized =
            Bitmap.createScaledBitmap(
                curImage!!, curImage!!.width / MULT_IMAGE,
                curImage!!.height / MULT_IMAGE, true
            )
        val imgML = InputImage.fromBitmap(resized, 0)

        if (curImgText != ImageEditor.ASS) {
            faceDetector.process(imgML)
                .addOnSuccessListener { faces ->
                    for (face in faces) {
                        for (landmarkId in ImageEditor.useFullLandmarks) {
                            val landmarkObject = face.getLandmark(landmarkId)
                            if (landmarkObject != null) {
                                mapLandMarks[landmarkId] =
                                    landmarkObject.position
                                mapLandMarks[landmarkId]!!.x *= MULT_IMAGE
                                mapLandMarks[landmarkId]!!.y *= MULT_IMAGE
                                heightFace =
                                    face.boundingBox.height() * MULT_IMAGE
                                widthFace =
                                    face.boundingBox.width() * MULT_IMAGE
                            }
                        }
                    }
                }
                .addOnFailureListener { e ->
                    Toast.makeText(baseContext, "${e}", Toast.LENGTH_SHORT)
                        .show()
                }
        } else {
            poseDetector.process(imgML)
                .addOnSuccessListener { results ->
                    for (landmarkId in ImageEditor.bodyLandmarks) {
                        val landmarkObject = results.getPoseLandmark(landmarkId)
                        if (landmarkObject != null) {
                            mapLandMarks[landmarkId] =
                                landmarkObject.position
                            mapLandMarks[landmarkId]!!.x *= MULT_IMAGE
                            mapLandMarks[landmarkId]!!.y *= MULT_IMAGE
                        }
                    }
                }
                .addOnFailureListener { e ->
                    Toast.makeText(baseContext, "${e}", Toast.LENGTH_SHORT)
                        .show()
                }

            val imgMLSegmenter = InputImage.fromBitmap(curImage!!, 0)
            segmenter.process(imgMLSegmenter)
                .addOnSuccessListener { results ->
                    mask = results.buffer
                    maskWidth = results.width
                    maskHeight = results.height
                    if (mask != null) {
                        mask!!.rewind()
                    }
                }
                .addOnFailureListener { e ->
                    Toast.makeText(baseContext, "${e}", Toast.LENGTH_SHORT)
                        .show()
                }
        }
    }

    fun dist(instance: ImgInstance, event: MotionEvent): Float {
        return (event.x - instance.positionAndSize.leftX) *
                (event.x - instance.positionAndSize.leftX) +
                (event.y - instance.positionAndSize.leftY) *
                (event.y - instance.positionAndSize.leftY)
    }

    fun isCloseToBound(x: Float, leftX: Int, rightX: Int): Boolean {
        return (leftX - OFFSET <= x) && ( x <= rightX + OFFSET)
    }

    fun isClose(instance: ImgInstance, event: MotionEvent): Boolean {
        return isCloseToBound(event.x, instance.positionAndSize.leftX, instance.positionAndSize.leftX + instance.positionAndSize.width) &&
                isCloseToBound(event.y, instance.positionAndSize.leftY, instance.positionAndSize.leftY + instance.positionAndSize.height)
    }

    fun reDraw(canvas: Canvas) {
        for (elem in drawedThings) {
            val curPoint = PointF(elem.positionAndSize.leftX.toFloat(), elem.positionAndSize.leftY.toFloat())
            drawImgPoint(curPoint, curPoint, canvas,
                BitmapFactory.decodeResource(
                    resources,
                    mapImgs.get(elem.extraImg)!!
                ),
                elem.positionAndSize.mirror,
                elem.positionAndSize.width,
                elem.positionAndSize.height,
                elem.positionAndSize.leftX.toFloat(),
                elem.positionAndSize.leftY.toFloat()
            )
        }
        viewBinding.imageViewDraw.setImageBitmap(upperBitmap)
    }
    
    fun processContextInfo(event: MotionEvent): Boolean {
        if(event.action == MotionEvent.ACTION_DOWN) {
            return true
        }
        var processed = false
        if (contextIndex != -1) {
            val contInfo = drawedThings[contextIndex]
            if(isClose(drawedThings[contextIndex], event)) {
                val toDel = drawedThings[drawedIndex]
                drawedThings.remove(toDel)
                processed = true
            }
            drawedThings.remove(contInfo)
            drawedIndex = -1
            contextIndex = -1
        }
        if (!processed) {
            var minDist = -1f
            var index = -1
            for ((i, elem) in drawedThings.withIndex()) {
                if (isClose(elem, event)) {
                    val curDist = dist(elem, event)
                    if ((index == -1) || ( curDist < minDist)) {
                        minDist = curDist
                        index = i
                    }
                }
            }
            if (index != -1 ) {
                drawedThings.add(
                    ImgInstance(
                        ExtraImgs.CONTEXT_INFO, PositionAndSize(
                            drawedThings[index].positionAndSize.leftX,
                            drawedThings[index].positionAndSize.leftY, CONTEXT_INFO_SIZE, CONTEXT_INFO_SIZE
                        )
                    )
                )
                contextIndex = drawedThings.size - 1
                drawedIndex = index
            }
        }
        reDraw(getCanvas())
        return true
    }


    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityEditorBinding.inflate(layoutInflater)

        setContent{
            setContentView(viewBinding.root)
            viewBinding
            LoaderOfFolder()

            viewBinding.imageViewDraw.setOnTouchListener { v, motionEvent -> processContextInfo(motionEvent)}

            viewBinding.imageSaveButton.setOnClickListener {
                ImageSaver.savePhotoWithBackground(curImage!!, upperBitmap!!, contentResolver)
            }

            viewBinding.glassesAdd.setOnClickListener {
                setFilter(baseContext, ImageEditor.GLASSES)
            }

            viewBinding.eyeLashes.setOnClickListener {
                setFilter(baseContext, ImageEditor.EYE_LASH)
            }

            viewBinding.ears.setOnClickListener {
                setFilter(baseContext, ImageEditor.EARS)
            }

            viewBinding.betaVersion.setOnClickListener {
                setFilter(baseContext, ImageEditor.ASS)
            }

            viewBinding.lips.setOnClickListener {
                setFilter(baseContext, ImageEditor.LIPS)
            }

            val realTimeOpts = FaceDetectorOptions.Builder()
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
                .build()

            faceDetector = FaceDetection.getClient(realTimeOpts)

            val options = PoseDetectorOptions.Builder()
                .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
                .build()
            poseDetector = PoseDetection.getClient(options)

            val segmentOptions =
                SelfieSegmenterOptions.Builder()
                    .setDetectorMode(SelfieSegmenterOptions.STREAM_MODE)
                    .enableRawSizeMask()
                    .build()
            segmenter = Segmentation.getClient(segmentOptions)

        }


        if (!allPermissionsGranted()) {
            requestPermissions()
        }

    }

    private fun requestPermissions() {
        activityResultLauncher.launch(REQUIRED_PERMISSIONS)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it
        ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val TAG = "DeveloperApp"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private val REQUIRED_PERMISSIONS =
            mutableListOf (
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
    }

    private val activityResultLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        )
        { permissions ->
            // Handle Permission granted/rejected
            var permissionGranted = true
            permissions.entries.forEach {
                if (it.key in REQUIRED_PERMISSIONS && it.value == false)
                    permissionGranted = false
            }
            if (!permissionGranted) {
                Toast.makeText(
                    baseContext,
                    "Permission request denied",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
}