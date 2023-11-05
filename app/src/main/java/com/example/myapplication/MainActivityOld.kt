package com.example.myapplication

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PointF
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.myapplication.databinding.ActivityMainBinding
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.lang.Math.abs
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

typealias ImageListener = (img: Bitmap) -> Unit

class MainActivityOld : AppCompatActivity() {
    private lateinit var viewBinding: ActivityMainBinding

    private val defaultImgId = R.mipmap.female
    private val IS_DEBUG = false


    private val conf: Bitmap.Config = Bitmap.Config.ARGB_8888 // see other conf types

    @Volatile
    private var upperBitmap : Bitmap? = null




    @Volatile
    private var curImage: Bitmap? = null
    @Volatile
    private var curImageArray = Collections.synchronizedList(
        mutableListOf<
                Pair<Bitmap, Bitmap>
                >()
    )

    private val mapLandMarks = Collections.synchronizedMap(hashMapOf<Int, PointF>())

    @Volatile
    private var mask: ByteBuffer? = null
    private var maskWidth: Int  = 128
    private var maskHeight: Int = 128

    @Volatile
    private var curImgText = ImageEditor.NONE

    @Volatile
    private var lastUpdate: Long = 0
    private val UPDATE_LAG: Long = 1000
    private val LAG_POSE_DETECTION: Long = 100

    private val MIN_OFFSET = 1
    private val MIN_OFFSET_LANDMARKS = 20
    private val MULT_CHANGES = 0.97f

    private val MULT_IMAGE = 4

    @Volatile
    private var isChanged = false

    @Volatile
    private var isChangedDramatically = false

    @Volatile
    private var heightFace: Int = 0
    @Volatile
    private var widthFace: Int = 0


    @Volatile
    private var isBackCamera: Boolean = true

    private lateinit var cameraExecutor: ExecutorService

    private lateinit var faceDetector: FaceDetector
    private lateinit var poseDetector: PoseDetector
    private lateinit var segmenter: Segmenter

    private fun setFilter(context: Context, name: String) {
        synchronized(context) {
            if (upperBitmap != null) {
                upperBitmap!!.eraseColor(Color.TRANSPARENT)
                Log.i("DEBUG", "erased")
            }
            curImgText = if (curImgText != name) name else ImageEditor.NONE
            isChanged = true
            isChangedDramatically = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissions()
        }

        // Set up the listeners for take photo and video capture buttons
        viewBinding.imageCaptureButton.setOnClickListener { takePhoto() }

        cameraExecutor = Executors.newSingleThreadExecutor()

        viewBinding.imageView.setOnClickListener {
            updateImg()
            isChanged = true
            isChangedDramatically = true
        }

        viewBinding.openEditor.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
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

        viewBinding.changeCamera.setOnClickListener {
            synchronized(baseContext) {
                if (upperBitmap != null) {
                    upperBitmap!!.eraseColor(Color.TRANSPARENT)
                    Log.i("DEBUG", "erased")
                }
                isChanged = true
                isChangedDramatically = true
            }
            isBackCamera = !isBackCamera
            startCamera()

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

    private fun takePhoto() {
        if(curImage != null) {
            synchronized(this) {
                curImageArray.add(
                    Pair(curImage!!.copy(curImage!!.config, true),
                        upperBitmap!!.copy(curImage!!.config, false)))
            }

            runBlocking {
                launch(Dispatchers.Default) {
                    val pairBitmap = curImageArray.removeLast()
                    ImageSaver.savePhotoWithBackground(pairBitmap.first, pairBitmap.second, contentResolver)
                }
            }
        }
    }

    private fun updateImg() {
        lastUpdate = 0
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, ImageAnalyzer(resources = resources, listener = { img ->
                        val person = BitmapFactory.decodeResource(resources, defaultImgId)
                        val currentImage = if (IS_DEBUG) person.copy(person.config, true) else img


                        synchronized(baseContext) {
                            curImage = currentImage.copy(currentImage.config, true)
                            if (upperBitmap == null) {
                                upperBitmap = Bitmap.createBitmap(
                                    currentImage.width,
                                    currentImage.height, conf
                                )
                            }
                        }

                        val mainHandlerBack = Handler(getMainLooper());

                        val myRunnableBack = Runnable() {
                            synchronized(baseContext) {
                                viewBinding.imageView.setImageBitmap(curImage)
                                if (upperBitmap == null && (isChanged || isChangedDramatically)) {
                                    viewBinding.imageViewDraw.setImageBitmap(upperBitmap)
                                    isChangedDramatically = false
                                }
                            }
                        }
                        mainHandlerBack.post(myRunnableBack)

                        var canvas = Canvas(upperBitmap!!)
                        synchronized(baseContext) {
                            canvas = Canvas(upperBitmap!!) //Canvas(currentImage)
                        }
                        Log.i("DEBUG", curImgText)
                        if (curImgText == ImageEditor.NONE) {
                            return@ImageAnalyzer
                        }
                        val currentMills = System.currentTimeMillis()
                        Log.i("DEBUG", "${currentMills - lastUpdate}")
                        if (currentMills - lastUpdate >= LAG_POSE_DETECTION) {
                            val resized =
                                Bitmap.createScaledBitmap(
                                    currentImage, currentImage.width / MULT_IMAGE,
                                    currentImage.height / MULT_IMAGE, true
                                )
                            val imgML = InputImage.fromBitmap(resized, 0)

                            if (curImgText != ImageEditor.ASS) {
                                faceDetector.process(imgML)
                                    .addOnSuccessListener { faces ->
//                                        Toast.makeText(baseContext, "HEUTA! ${faces.size}", Toast.LENGTH_SHORT).show()
                                        for (face in faces) {
                                            Log.i("DEBUG", "face finding")
                                            for (landmarkId in ImageEditor.useFullLandmarks) {
                                                val landmarkObject = face.getLandmark(landmarkId)
                                                if (landmarkObject != null) {
                                                    val prev = mapLandMarks[landmarkId]
                                                    mapLandMarks[landmarkId] =
                                                        landmarkObject.position
                                                    mapLandMarks[landmarkId]!!.x *= MULT_IMAGE
                                                    mapLandMarks[landmarkId]!!.y *= MULT_IMAGE
                                                    heightFace =
                                                        face.boundingBox.height() * MULT_IMAGE
                                                    widthFace =
                                                        face.boundingBox.width() * MULT_IMAGE
                                                    if (prev == null) {
                                                        isChangedDramatically = true
                                                    } else {
                                                        if (abs(prev.x - mapLandMarks[landmarkId]!!.x) >= MIN_OFFSET ||
                                                            abs(prev.y - mapLandMarks[landmarkId]!!.y) >= MIN_OFFSET
                                                        ) {
                                                            Log.i(
                                                                "debug",
                                                                "isChangedDramatically = ${isChangedDramatically}"
                                                            )
                                                            mapLandMarks[landmarkId]!!.x =
                                                                mapLandMarks[landmarkId]!!.x * MULT_CHANGES +
                                                                        (1 - MULT_CHANGES) * prev.x

                                                            mapLandMarks[landmarkId]!!.y =
                                                                mapLandMarks[landmarkId]!!.y * MULT_CHANGES +
                                                                        (1 - MULT_CHANGES) * prev.y
                                                            isChangedDramatically = true
                                                        }
                                                    }

                                                    isChanged = true
                                                    lastUpdate = currentMills
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
                                                val prev = mapLandMarks[landmarkId]
                                                mapLandMarks[landmarkId] =
                                                    landmarkObject.position
                                                mapLandMarks[landmarkId]!!.x *= MULT_IMAGE
                                                mapLandMarks[landmarkId]!!.y *= MULT_IMAGE
                                                if (prev == null) {
                                                    isChangedDramatically = true
                                                } else {
                                                    if (abs(prev.x - mapLandMarks[landmarkId]!!.x) >= MIN_OFFSET_LANDMARKS ||
                                                        abs(prev.y - mapLandMarks[landmarkId]!!.y) >= MIN_OFFSET_LANDMARKS
                                                    ) {
                                                        Log.i(
                                                            "debug",
                                                            "isChangedDramatically = ${isChangedDramatically}"
                                                        )
                                                        mapLandMarks[landmarkId]!!.x =
                                                            mapLandMarks[landmarkId]!!.x * MULT_CHANGES +
                                                                    (1 - MULT_CHANGES) * prev.x

                                                        mapLandMarks[landmarkId]!!.y =
                                                            mapLandMarks[landmarkId]!!.y * MULT_CHANGES +
                                                                    (1 - MULT_CHANGES) * prev.y
                                                        isChangedDramatically = true
                                                    }
                                                }

                                                isChanged = true
                                                lastUpdate = currentMills
                                            }
                                        }
                                    }
                                    .addOnFailureListener { e ->
                                        Toast.makeText(baseContext, "${e}", Toast.LENGTH_SHORT)
                                            .show()
                                    }

                                val imgMLSegmenter = InputImage.fromBitmap(currentImage, 0)
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
                        if (curImgText != ImageEditor.NONE && (isChanged || isChangedDramatically)) {
                            Log.i("DEBUG", "isChanged")
                            ImageEditor.processRequestEditing(
                                baseContext, curImgText,
                                mapLandMarks, upperBitmap!!, canvas,
                                resources, heightFace, widthFace, currentImage,
                                mask=mask, maskWidth = maskWidth, maskHeight = maskHeight
                            )
                        }

                        if (currentMills - lastUpdate >= UPDATE_LAG && !isChanged) {
                            Log.i("DEBUG", "erased")
                            for (landmarkId in ImageEditor.useFullLandmarks) {
                                mapLandMarks[landmarkId] = null
                            }
                            if (upperBitmap != null) {
                                upperBitmap!!.eraseColor(Color.TRANSPARENT)
                            }
                        }

                        val mainHandler = Handler(getMainLooper())

                        Log.i("debug", "isChangedDramatically = ${isChangedDramatically}")
                        if (isChangedDramatically) {
                            val myRunnable = Runnable() {
                                    synchronized(baseContext) {
                                        viewBinding.imageViewDraw.setImageBitmap(
                                            upperBitmap!!.copy(upperBitmap!!.config, false)
                                        )
                                        isChangedDramatically = false
                                }
                            }
                            mainHandler.post(myRunnable)
                            isChangedDramatically = false
                        }
                    }))
                }

            val cameraSelector = if(isBackCamera)  {CameraSelector.DEFAULT_BACK_CAMERA } else {CameraSelector.DEFAULT_FRONT_CAMERA}

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, imageAnalyzer)

            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun requestPermissions() {
        activityResultLauncher.launch(REQUIRED_PERMISSIONS)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
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
            ActivityResultContracts.RequestMultiplePermissions())
        { permissions ->
            // Handle Permission granted/rejected
            var permissionGranted = true
            permissions.entries.forEach {
                if (it.key in REQUIRED_PERMISSIONS && it.value == false)
                    permissionGranted = false
            }
            if (!permissionGranted) {
                Toast.makeText(baseContext,
                    "Permission request denied",
                    Toast.LENGTH_SHORT).show()
            } else {
                startCamera()
            }
        }

    private class ImageAnalyzer(private val listener: ImageListener, private val resources: Resources) : ImageAnalysis.Analyzer {

        @SuppressLint("UnsafeOptInUsageError")
        override fun analyze(image: ImageProxy) {

            val matrix = Matrix()


//            listener(Bitmap.createBitmap( BitmapFactory.decodeResource(resources, R.mipmap.person_photo), 0, 0,
//                image.width, image.height, matrix, true))

            matrix.postRotate(image.imageInfo.rotationDegrees.toFloat())


            listener(Bitmap.createBitmap( image.toBitmap(), 0, 0,
                image.width, image.height, matrix, true))

            image.close()

        }

    }
}