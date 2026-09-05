package com.example.deadnavs

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Environment
import android.widget.Toast
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import android.animation.ValueAnimator
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.View
import android.widget.LinearLayout
import com.google.android.material.card.MaterialCardView
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), SensorEventListener, OnMapReadyCallback {
    private lateinit var rootLayout: LinearLayout
    private lateinit var cardAccel: MaterialCardView
    private lateinit var cardGyro: MaterialCardView
    private lateinit var cardMap: MaterialCardView
    private lateinit var btnExpandMap: MaterialCardView
    private var mMap: GoogleMap? = null
    private var currentMarker: Marker? = null

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var gravitySensor: Sensor? = null
    private var linearAccelSensor: Sensor? = null
    private var magneticSensor: Sensor? = null

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    // Sensor state storage
    private var accelValues = FloatArray(3)
    private var gravityValues = FloatArray(3)
    private var linearAccelValues = FloatArray(3)
    private var gyroValues = FloatArray(3)
    private var magneticValues = FloatArray(3)
    private var orientationValues = FloatArray(3)
    private val rotationMatrix = FloatArray(9)

    // Location state storage
    private var latestLat = 0.0
    private var latestLon = 0.0
    private var latestAlt = 0.0
    private var latestSpeed = 0.0f
    private var latestAccuracy = 0.0f
    private var latestBearing = 0.0f
    private var satelliteCount = 0

    private var csvWriter: OutputStreamWriter? = null
    private var isLogging = false
    private var loggingStartTime = 0L

    // UI Elements
    private lateinit var tvAccelData: TextView
    private lateinit var tvGyroData: TextView
    private lateinit var btnToggleLogging: Button

    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false) ||
            permissions.getOrDefault(Manifest.permission.ACCESS_COARSE_LOCATION, false)) {
            startLocationUpdates()
        }
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        mMap?.uiSettings?.isZoomControlsEnabled = false
        mMap?.isBuildingsEnabled = true // Forces 3D buildings to render
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        var mapFrag = supportFragmentManager.findFragmentById(R.id.mapContainer) as? SupportMapFragment
        if (mapFrag == null) {
            mapFrag = SupportMapFragment.newInstance()
            supportFragmentManager.beginTransaction()
                .add(R.id.mapContainer, mapFrag)
                .commit()
        }
        mapFrag.getMapAsync(this)

        // Bind UI Elements
        tvAccelData = findViewById(R.id.tvAccelData)
        tvGyroData = findViewById(R.id.tvGyroData)
        btnToggleLogging = findViewById(R.id.btnToggleLogging)
        rootLayout = findViewById(R.id.rootLayout)
        cardAccel = findViewById(R.id.cardAccel)
        cardGyro = findViewById(R.id.cardGyro)
        cardMap = findViewById(R.id.cardMap)
        btnExpandMap = findViewById(R.id.btnExpandMap)

        // Expand Map Logic
        btnExpandMap.setOnClickListener {
            val transition = AutoTransition()
            transition.duration = 200
            TransitionManager.beginDelayedTransition(rootLayout, transition)

            tvAccelData.visibility = View.GONE
            tvGyroData.visibility = View.GONE
            btnExpandMap.visibility = View.GONE

            val screenHeight = resources.displayMetrics.heightPixels
            val params = cardMap.layoutParams as LinearLayout.LayoutParams
            params.height = (screenHeight * 0.55).toInt()
            cardMap.layoutParams = params
        }

        // Restore Layout Logic
        val restoreLayoutListener = View.OnClickListener {
            if (tvAccelData.visibility == View.GONE) {
                val transition = AutoTransition()
                transition.duration = 200
                TransitionManager.beginDelayedTransition(rootLayout, transition)

                tvAccelData.visibility = View.VISIBLE
                tvGyroData.visibility = View.VISIBLE
                btnExpandMap.visibility = View.VISIBLE

                val params = cardMap.layoutParams as LinearLayout.LayoutParams
                params.height = (200 * resources.displayMetrics.density).toInt()
                cardMap.layoutParams = params
            }
        }

        cardAccel.setOnClickListener(restoreLayoutListener)
        cardGyro.setOnClickListener(restoreLayoutListener)

        // Handle Button Click
        btnToggleLogging.setOnClickListener {
            isLogging = !isLogging
            if (isLogging) {
                loggingStartTime = System.currentTimeMillis()
                btnToggleLogging.text = "Stop Logging"
                btnToggleLogging.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#CCFFCDD2"))
                btnToggleLogging.setTextColor(Color.parseColor("#C62828"))

                val publicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "DEADNAVS")
                if (!publicDir.exists()) publicDir.mkdirs()

                val file = File(publicDir, "sensor_log_${System.currentTimeMillis()}.csv")
                csvWriter = OutputStreamWriter(FileOutputStream(file, true))

                // Exact Target CSV Header Format
                csvWriter?.append("GPS LATITUDE (degrees),GPS LONGITUDE (degrees),GPS ALTITUDE (m),GPS SPEED (Kmh),GPS ACCURACY (m),GPS ORIENTATION (TA),GPS SATELLITES IN RANGE,TIME SINCE START (ms),DATE (YYYY-MO-DD HH-MI-SS_SSS),ACCELEROMETER X (m/s²),ACCELEROMETER Y (m/s²),ACCELEROMETER Z (m/s²),GRAVITY X (m/s²),GRAVITY Y (m/s²),GRAVITY Z (m/s²),GYROSCOPE Yaw (rad/s),GYROSCOPE Pitch (rad/s),GYROSCOPE Roll (rad/s),MAGNETIC FIELD X (μT),MAGNETIC FIELD Y (μT),MAGNETIC FIELD Z (μT),ORIENTATION (Yaw) (TA),ORIENTATION (Pitch) (TA),ORIENTATION (Roll) (TA)\n")
            } else {
                btnToggleLogging.text = "Start Logging"
                btnToggleLogging.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#CCC8E6C9"))
                btnToggleLogging.setTextColor(Color.parseColor("#2E7D32"))

                csvWriter?.flush()
                csvWriter?.close()
                csvWriter = null

                Toast.makeText(this, "Saved to Downloads/DEADNAVS", Toast.LENGTH_LONG).show()
            }
        }

        // Initialize Sensors
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        linearAccelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        magneticSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    latestLat = location.latitude
                    latestLon = location.longitude
                    latestAlt = location.altitude
                    latestSpeed = location.speed * 3.6f // Convert m/s to Km/h to match target format
                    latestAccuracy = location.accuracy
                    latestBearing = location.bearing

                    if (location.extras != null) {
                        satelliteCount = location.extras?.getInt("satellites", 0) ?: 0
                    }

                    val currentLatLng = LatLng(latestLat, latestLon)
                    if (currentMarker == null) {
                        currentMarker = mMap?.addMarker(
                            MarkerOptions()
                                .position(currentLatLng)
                                .icon(getBlueDot())
                                .anchor(0.5f, 0.5f)
                        )
                        mMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(currentLatLng, 18f))
                    } else {
                        animateMarker(currentMarker!!, currentLatLng)
                        mMap?.animateCamera(CameraUpdateFactory.newLatLng(currentLatLng))
                    }
                }
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            locationPermissionRequest.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        } else {
            startLocationUpdates()
        }
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000).build()
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        gyroscope?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        gravitySensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        linearAccelSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        magneticSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates()
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        fusedLocationClient.removeLocationUpdates(locationCallback)
        csvWriter?.flush()
    }

    override fun onDestroy() {
        super.onDestroy()
        csvWriter?.close()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                accelValues = event.values.clone()
                tvAccelData.text = "X: ${accelValues[0]}\nY: ${accelValues[1]}\nZ: ${accelValues[2]}"
            }
            Sensor.TYPE_GYROSCOPE -> {
                gyroValues = event.values.clone()
                tvGyroData.text = "X: ${gyroValues[0]}\nY: ${gyroValues[1]}\nZ: ${gyroValues[2]}"
            }
            Sensor.TYPE_GRAVITY -> {
                gravityValues = event.values.clone()
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                magneticValues = event.values.clone()
                // Compute orientation angles (Azimuth, Pitch, Roll) if we have gravity + magnetic data
                SensorManager.getRotationMatrix(rotationMatrix, null, gravityValues, magneticValues)
                SensorManager.getOrientation(rotationMatrix, orientationValues)
            }
        }

        // Write row on Accelerometer event tick when logging is active
        if (isLogging && event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val currentTimeMillis = System.currentTimeMillis()
            val timeSinceStart = currentTimeMillis - loggingStartTime

            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss_SSS", Locale.getDefault())
            val dateStr = dateFormat.format(Date(currentTimeMillis))

            val csvRow = "$latestLat,$latestLon,$latestAlt,$latestSpeed,$latestAccuracy,$latestBearing,$satelliteCount," +
                    "$timeSinceStart,$dateStr," +
                    "${accelValues[0]},${accelValues[1]},${accelValues[2]}," +
                    "${gravityValues[0]},${gravityValues[1]},${gravityValues[2]}," +
                    "${gyroValue(gyroValues[2])},${gyroValue(gyroValues[0])},${gyroValue(gyroValues[1])}," + // Yaw, Pitch, Roll mapping convention
                    "${magneticValues[0]},${magneticValues[1]},${magneticValues[2]}," +
                    "${Math.toDegrees(orientationValues[0].toDouble())}," + // Yaw (Azimuth)
                    "${Math.toDegrees(orientationValues[1].toDouble())}," + // Pitch
                    "${Math.toDegrees(orientationValues[2].toDouble())}\n"   // Roll

            try {
                csvWriter?.append(csvRow)
            } catch (e: Exception) {
                Log.e("CSV_Error", "Failed to write row", e)
            }
        }
    }

    private fun gyroValue(v: Float): Float = v

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun animateMarker(marker: Marker, toPosition: LatLng) {
        val startPosition = marker.position
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = 1000
        animator.addUpdateListener { animation ->
            val v = animation.animatedFraction
            val lng = v * toPosition.longitude + (1 - v) * startPosition.longitude
            val lat = v * toPosition.latitude + (1 - v) * startPosition.latitude
            marker.position = LatLng(lat, lng)
        }
        animator.start()
    }

    private fun getBlueDot(): BitmapDescriptor {
        val size = 60
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val fillPaint = Paint().apply {
            color = Color.parseColor("#4285F4")
            isAntiAlias = true
            style = Paint.Style.FILL
        }

        val strokePaint = Paint().apply {
            color = Color.WHITE
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeWidth = 8f
        }

        val radius = size / 2f
        canvas.drawCircle(radius, radius, radius - 4f, fillPaint)
        canvas.drawCircle(radius, radius, radius - 4f, strokePaint)

        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }
}

class TouchableMapContainer(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs) {
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent.requestDisallowInterceptTouchEvent(false)
            }
        }
        return super.dispatchTouchEvent(ev)
    }
}