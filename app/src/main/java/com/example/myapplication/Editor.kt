package com.example.myapplication

import android.Manifest
import android.R.attr.orientation
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
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.core.content.ContextCompat
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


class Editor : AppCompatActivity() {
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

    private val defaultImgId = R.mipmap.female
    private val IS_DEBUG = false

    private val MULT_IMAGE = 2

    private val conf: Bitmap.Config = Bitmap.Config.ARGB_8888 // see other conf types

    private val mapLandMarks = Collections.synchronizedMap(hashMapOf<Int, PointF>())


    @Volatile
    private var mask: ByteBuffer? = null
    private var maskWidth: Int  = 128
    private var maskHeight: Int = 128

    @Volatile
    private var heightFace: Int = 0
    @Volatile
    private var widthFace: Int = 0

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
            if (curImage != null) {
                curImage = Bitmap.createBitmap(
                    curImage!!, 0, 0,
                    curImage!!.getWidth(), curImage!!.getHeight(), matrix, true
                )
            }
            if (upperBitmap != null) {
                upperBitmap!!.eraseColor(Color.TRANSPARENT)
                viewBinding.imageViewDraw.setImageBitmap(
                    upperBitmap!!.copy(upperBitmap!!.config, false)
                )
            }

            viewBinding.imageViewEditor.setImageBitmap(curImage)
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
        synchronized(context) {
            if (upperBitmap != null) {
                upperBitmap!!.eraseColor(Color.TRANSPARENT)
            }
            curImgText = if (curImgText != name) name else ImageEditor.NONE
            processImage()
        }
    }

    private fun processImage() {
        val person = BitmapFactory.decodeResource(resources, defaultImgId)
        val currentImage = if (IS_DEBUG) person.copy(person.config, true) else curImage
        curImage = currentImage!!.copy(currentImage.config, true)
        upperBitmap = Bitmap.createBitmap(
            currentImage.width,
            currentImage.height, conf
        )
        var canvas = Canvas(upperBitmap!!)
        if (curImgText == ImageEditor.NONE) {
            return
        }
        val resized =
            Bitmap.createScaledBitmap(
                currentImage, currentImage.width / MULT_IMAGE,
                currentImage.height / MULT_IMAGE, true
            )
        val imgML = InputImage.fromBitmap(resized, 0)

        if (curImgText != ImageEditor.ASS) {
            faceDetector.process(imgML)
                .addOnSuccessListener { faces ->
                    for (face in faces) {
                        Log.i("DEBUG", "face finding")
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
//        Toast.makeText(baseContext, "editing image", Toast.LENGTH_SHORT).show()
        ImageEditor.processRequestEditing(
            baseContext, curImgText,
            mapLandMarks, upperBitmap!!, canvas,
            resources, heightFace, widthFace, currentImage,
            mask = mask, maskWidth = maskWidth, maskHeight = maskHeight
        )
        viewBinding.imageViewDraw.setImageBitmap(
            upperBitmap!!.copy(upperBitmap!!.config, false)
        )
//        Toast.makeText(baseContext, "changed image", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityEditorBinding.inflate(layoutInflater)

        setContent{
            setContentView(viewBinding.root)
            viewBinding
            LoaderOfFolder()

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