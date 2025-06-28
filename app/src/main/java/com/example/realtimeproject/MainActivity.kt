package com.example.realtimeproject

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.TextureView
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.realtimeproject.ml.SsdMobilenetV11Metadata1
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.label.Category
import org.tensorflow.lite.support.image.ops.ResizeOp
import android.util.Log
import android.speech.tts.TextToSpeech
import java.util.Locale
import android.os.Vibrator
import android.os.VibrationEffect
import android.os.Looper

class MainActivity : AppCompatActivity() {

    // Kamera ve görüntü işleme için gerekli değişkenler
    private lateinit var bitmap: Bitmap
    private lateinit var imageView: ImageView
    private lateinit var cameraDevice: CameraDevice
    private lateinit var handler: Handler
    private lateinit var cameraManager: CameraManager
    private lateinit var textureView: TextureView
    private lateinit var handlerThread: HandlerThread
    private lateinit var model: SsdMobilenetV11Metadata1
    private lateinit var labels: List<String>
    private lateinit var overlayView: OverlayView
    private var cameraPermissionGranted = false
    
    // Sesli bildirim için TTS değişkenleri
    private lateinit var textToSpeech: TextToSpeech
    private var lastSpokenObject = ""
    private var speechCooldown = 0L
    private val SPEECH_COOLDOWN_TIME = 3000L // 3 saniye bekleme süresi
    private var voiceEnabled = true // Sesli bildirim açık/kapalı
    
    // Gelişmiş sesli bildirim kontrolleri
    private var lastDetectionTime = 0L
    private val DETECTION_COOLDOWN = 5000L // 5 saniye aynı nesne için bekleme
    private val MIN_CONFIDENCE_SCORE = 0.7f // Minimum güven skoru
    private var consecutiveSameObjectCount = 0
    private val MAX_CONSECUTIVE_SAME_OBJECT = 3 // Aynı nesne için maksimum ardışık bildirim
    
    // Önemli nesneler listesi - bunlar için daha sık bildirim
    private val importantObjects = setOf(
        "Kişi", "Araba", "Motosiklet", "Bisiklet", "Kedi", "Köpek", 
        "Trafik Işığı", "Dur Tabelası", "Merdiven", "Kapı"
    )
    
    // 3 kez dokunma için değişkenler
    private var tapCount = 0
    private var lastTapTime = 0L
    private val TAP_TIMEOUT = 1000L // 1 saniye içinde 3 dokunma
    private lateinit var vibrator: Vibrator

    companion object {
        private const val CAMERA_PERMISSION_REQUEST_CODE = 100
        private const val CAMERA_PERMISSION = Manifest.permission.CAMERA
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Uygulama başlatılırken gerekli bileşenleri hazırla
        initializeViews()
        setupHandler()
        checkAndRequestPermissions()
        model = SsdMobilenetV11Metadata1.newInstance(this)
        labels = FileUtil.loadLabels(this, "labelmap.txt")
        
        // TTS'i başlat
        initializeTextToSpeech()
    }

    private fun initializeViews() {
        // UI bileşenlerini başlat ve kamera servisini al
        imageView = findViewById(R.id.imageView)
        textureView = findViewById(R.id.textureView)
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        overlayView = findViewById(R.id.overlayView)
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        setupTextureViewListener()
    }

    private fun setupHandler() {
        // Arka plan işlemleri için handler thread oluştur
        handlerThread = HandlerThread("videoThread")
        handlerThread.start()
        handler = Handler(handlerThread.looper)
    }

    private fun setupTextureViewListener() {
        // Kamera görüntüsünü almak için TextureView listener'ını ayarla
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                if (cameraPermissionGranted) {
                    openCamera()
                }
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                // Yüzey boyutu değiştiğinde yapılacak işlemler
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                return false
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                // Her frame'de nesne tespiti yap - daha sık işleme için optimizasyon
                textureView.bitmap?.let {
                    bitmap = it
                    processImage(bitmap)
                }
            }
        }
        
        // 3 kez dokunma için touch listener ekle
        setupTripleTapListener()
    }

    private fun checkAndRequestPermissions() {
        // Kamera izni kontrol et ve gerekirse iste
        if (ContextCompat.checkSelfPermission(this, CAMERA_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(CAMERA_PERMISSION), CAMERA_PERMISSION_REQUEST_CODE)
        } else {
            cameraPermissionGranted = true
            if (textureView.isAvailable) {
                openCamera()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            CAMERA_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    cameraPermissionGranted = true
                    Toast.makeText(this, "Kamera izni verildi", Toast.LENGTH_SHORT).show()
                    if (textureView.isAvailable) {
                        openCamera()
                    }
                } else {
                    Toast.makeText(this, "Kamera izni gerekli", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        // Kamera cihazını aç ve başlat
        if (cameraManager.cameraIdList.isEmpty()) {
            Toast.makeText(this, "Kamera bulunamadı", Toast.LENGTH_SHORT).show()
            return
        }

        cameraManager.openCamera(cameraManager.cameraIdList[0], object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                createCameraPreviewSession()
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                Toast.makeText(this@MainActivity, "Kamera bağlantısı kesildi", Toast.LENGTH_SHORT).show()
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                Toast.makeText(this@MainActivity, "Kamera hatası: $error", Toast.LENGTH_SHORT).show()
            }
        }, handler)
    }

    private fun createCameraPreviewSession() {
        try {
            val surfaceTexture = textureView.surfaceTexture
            
            // SurfaceTexture boyutunu ayarla (daha uygun çözünürlük)
            surfaceTexture?.setDefaultBufferSize(1280, 720)
            
            val surface = Surface(surfaceTexture)

            val captureRequest = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            captureRequest.addTarget(surface)

            // Kamera önizleme oturumunu oluştur ve başlat
            cameraDevice.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        try {
                            session.setRepeatingRequest(captureRequest.build(), null, handler)
                            Log.d("Camera", "Kamera oturumu başarıyla başlatıldı")
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "Kamera başlatıldı", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            Log.e("Camera", "Kamera oturumu başlatılamadı: ${e.message}")
                            Toast.makeText(this@MainActivity, "Kamera oturumu başlatılamadı", Toast.LENGTH_SHORT).show()
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e("Camera", "Kamera oturumu yapılandırılamadı")
                        Toast.makeText(this@MainActivity, "Kamera oturumu yapılandırılamadı", Toast.LENGTH_SHORT).show()
                    }
                },
                handler
            )
        } catch (e: Exception) {
            Log.e("Camera", "Kamera oturumu oluşturulamadı: ${e.message}")
            Toast.makeText(this, "Kamera oturumu oluşturulamadı: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun processImage(bitmap: Bitmap) {
        // TensorFlow Lite modeli ile nesne tespiti yap
        val tensorImage = TensorImage.fromBitmap(bitmap)
        val outputs = model.process(tensorImage)

        val locations = outputs.locationsAsTensorBuffer.floatArray
        val classes = outputs.classesAsTensorBuffer.floatArray
        val scores = outputs.scoresAsTensorBuffer.floatArray
        val numberOfDetections = outputs.numberOfDetectionsAsTensorBuffer.floatArray[0].toInt()

        Log.d("TFLITE", "Detections: $numberOfDetections")
        val boxes = mutableListOf<OverlayView.RectFWithLabel>()

        // En yüksek skorlu tespitleri al ve kutuları çiz
        val sortedIndices = scores.withIndex().sortedByDescending { it.value }.map { it.index }
        val maxBoxes = 3
        for (i in sortedIndices.take(maxBoxes)) {
            val score = scores[i]
            Log.d("TFLITE", "Score: $score, Class: ${classes[i]}")
            if (score > 0.6 && classes[i].toInt() > 0) {
                val classIndex = classes[i].toInt()
                val label = if (classIndex in labels.indices) labels[classIndex] else "Bilinmeyen"

                // Bounding box koordinatlarını al (ymin, xmin, ymax, xmax)
                val ymin = locations[4 * i]
                val xmin = locations[4 * i + 1]
                val ymax = locations[4 * i + 2]
                val xmax = locations[4 * i + 3]

                // Koordinatları bitmap boyutuna çevir
                val left = xmin * bitmap.width
                val top = ymin * bitmap.height
                val right = xmax * bitmap.width
                val bottom = ymax * bitmap.height

                val rect = android.graphics.RectF(left, top, right, bottom)
                boxes.add(OverlayView.RectFWithLabel(rect, label))
            }
        }

        Log.d("TFLITE", "Tespit edilen nesne sayısı: ${boxes.size}")
        Log.d("TFLITE", "Tespit edilen nesneler: ${boxes.map { it.label }}")

        // UI thread'de kutuları çiz
        runOnUiThread {
            overlayView.setBoxes(boxes)
        }

        // Sesli bildirim işlemi - gelişmiş kontroller ile
        if (boxes.isNotEmpty() && ::textToSpeech.isInitialized && voiceEnabled) {
            val currentTime = System.currentTimeMillis()
            val bestDetection = boxes[0] // En yüksek skorlu tespit
            
            // Güven skoru kontrolü - sadece yüksek güvenilirlikli tespitler
            val confidenceScore = getConfidenceScore(bestDetection.label, scores, classes, labels)
            
            if (confidenceScore >= MIN_CONFIDENCE_SCORE) {
                val newSpokenObject = bestDetection.label
                
                // Aynı nesne kontrolü ve zaman aralığı kontrolü
                if (newSpokenObject != lastSpokenObject) {
                    // Farklı nesne tespit edildi
                    if (currentTime - speechCooldown > SPEECH_COOLDOWN_TIME) {
                        speakObjectDetection(newSpokenObject, confidenceScore)
                        lastSpokenObject = newSpokenObject
                        consecutiveSameObjectCount = 0
                        speechCooldown = currentTime
                    }
                } else {
                    // Aynı nesne tespit edildi
                    consecutiveSameObjectCount++
                    
                    // Önemli nesneler için daha kısa bekleme süresi
                    val baseCooldown = if (importantObjects.contains(newSpokenObject)) {
                        DETECTION_COOLDOWN / 2 // Önemli nesneler için 2.5 saniye
                    } else {
                        DETECTION_COOLDOWN // Normal nesneler için 5 saniye
                    }
                    
                    // Aynı nesne için daha uzun bekleme süresi
                    val sameObjectCooldown = baseCooldown + (consecutiveSameObjectCount * 2000L)
                    
                    if (currentTime - lastDetectionTime > sameObjectCooldown && 
                        consecutiveSameObjectCount <= MAX_CONSECUTIVE_SAME_OBJECT) {
                        
                        speakObjectDetection(newSpokenObject, confidenceScore)
                        lastDetectionTime = currentTime
                    }
                }
            }
        }
    }

    private fun initializeTextToSpeech() {
        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // Türkçe dil desteği ekle
                val turkishLocale = Locale("tr", "TR")
                val result = textToSpeech.setLanguage(turkishLocale)
                
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    // Türkçe yoksa varsayılan dili kullan
                    textToSpeech.setLanguage(Locale.getDefault())
                    Log.d("TTS", "Türkçe dil desteği bulunamadı, varsayılan dil kullanılıyor")
                } else {
                    Log.d("TTS", "Türkçe TTS başarıyla başlatıldı")
                }
                
                // Konuşma hızını ayarla
                textToSpeech.setSpeechRate(0.8f)
                textToSpeech.setPitch(1.0f)
                
                // Başlangıç talimatı ver
                Handler(Looper.getMainLooper()).postDelayed({
                    textToSpeech.speak("Nesne tespiti uygulaması başlatıldı. Sesli bildirimi açmak veya kapatmak için ekrana 3 kez dokunun.", TextToSpeech.QUEUE_FLUSH, null, null)
                }, 1000)
                
                Toast.makeText(this, "Sesli bildirim aktif", Toast.LENGTH_SHORT).show()
            } else {
                Log.e("TTS", "TTS başlatılamadı")
                Toast.makeText(this, "Sesli bildirim başlatılamadı", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTripleTapListener() {
        // 3 kez dokunma ile sesli bildirimi aç/kapat
        textureView.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    val currentTime = System.currentTimeMillis()
                    
                    // Zaman aşımını kontrol et
                    if (currentTime - lastTapTime > TAP_TIMEOUT) {
                        tapCount = 0
                    }
                    
                    tapCount++
                    lastTapTime = currentTime
                    
                    // 3 kez dokunulduğunda sesli bildirimi değiştir
                    if (tapCount >= 3) {
                        voiceEnabled = !voiceEnabled
                        tapCount = 0
                        
                        // Haptic feedback ver
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
                        } else {
                            @Suppress("DEPRECATION")
                            vibrator.vibrate(200)
                        }
                        
                        if (voiceEnabled) {
                            textToSpeech.speak("Sesli bildirim açıldı. Önemli nesneler için daha sık bildirim verilecek.", TextToSpeech.QUEUE_FLUSH, null, null)
                            Toast.makeText(this, "Sesli bildirim açıldı", Toast.LENGTH_SHORT).show()
                        } else {
                            textToSpeech.speak("Sesli bildirim kapatıldı", TextToSpeech.QUEUE_FLUSH, null, null)
                            Toast.makeText(this, "Sesli bildirim kapatıldı", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            true // Touch event'i işlendi
        }
    }

    private fun getConfidenceScore(label: String, scores: FloatArray, classes: FloatArray, labels: List<String>): Float {
        // En yüksek skorlu tespitin skorunu döndür
        return if (scores.isNotEmpty()) scores[0] else 0.0f
    }

    private fun speakObjectDetection(objectName: String, confidenceScore: Float) {
        try {
            // Güven skorunu yüzde olarak formatla
            val confidencePercent = (confidenceScore * 100).toInt()
            
            // Önemli nesneler için farklı mesaj
            val message = if (importantObjects.contains(objectName)) {
                "Dikkat! $objectName tespit edildi, güven: %$confidencePercent"
            } else {
                "$objectName tespit edildi, güven: %$confidencePercent"
            }
            
            textToSpeech.speak(message, TextToSpeech.QUEUE_FLUSH, null, null)
            Log.d("TTS", "Sesli bildirim: $message")
        } catch (e: Exception) {
            Log.e("TTS", "Sesli bildirim hatası: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Kaynakları temizle
        model.close()
        handlerThread.quitSafely()
        try {
            handlerThread.join()
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
        
        // TTS'i kapat
        if (::textToSpeech.isInitialized) {
            textToSpeech.stop()
            textToSpeech.shutdown()
        }
    }
}

