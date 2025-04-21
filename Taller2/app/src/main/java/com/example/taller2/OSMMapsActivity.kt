package com.example.taller2

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.TilesOverlay
import java.util.*
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import android.os.Environment
import androidx.appcompat.app.AlertDialog
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import android.location.Location


class OSMMapsActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var mapView: MapView
    private lateinit var mapController: org.osmdroid.views.MapController
    private lateinit var editTextAddress: EditText
    private lateinit var btnSearch: Button
    private lateinit var tvDistance: TextView
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var geocoder: Geocoder
    private lateinit var sensorManager: SensorManager

    private val LOCATION_PERMISSION_REQUEST_CODE = 1001
    private val REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    private var startPoint: GeoPoint? = null
    private var endPoint: GeoPoint? = null
    private var lightSensor: Sensor? = null
    private val LIGHT_THRESHOLD = 10.0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().apply {
            load(applicationContext, getPreferences(Context.MODE_PRIVATE))
            userAgentValue = packageName
            tileDownloadThreads = 4
            tileFileSystemThreads = 4
        }

        setContentView(R.layout.activity_osmmaps)

        initViews()
        setupMap()
        setupSearchControls()
        setupMapClickListener()
        checkAndRequestPermissions()
        setupLightSensor()
    }

    private fun initViews() {
        mapView = findViewById(R.id.osmMap)
        mapController = mapView.controller as org.osmdroid.views.MapController
        editTextAddress = findViewById(R.id.editTextAddress)
        btnSearch = findViewById(R.id.btnSearch)
        tvDistance = findViewById(R.id.tvDistance)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        geocoder = Geocoder(this, Locale.getDefault())
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }

    private fun setupMap() {
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.setTilesScaledToDpi(true)
        mapView.minZoomLevel = 12.0
        mapView.maxZoomLevel = 21.0
        mapController.setZoom(15.0)
        mapController.setCenter(GeoPoint(4.6283, -74.0659)) // Ubicación inicial (Javeriana)
    }

    private fun setupSearchControls() {
        btnSearch.setOnClickListener {
            val address = editTextAddress.text.toString().trim()
            if (address.isNotEmpty()) {
                searchAddress(address)
                hideKeyboard(editTextAddress)
            } else {
                showToast("Ingrese una dirección")
            }
        }

        editTextAddress.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val address = v.text.toString().trim()
                if (address.isNotEmpty()) {
                    searchAddress(address)
                    hideKeyboard(v)
                    true
                } else {
                    showToast("Ingrese una dirección")
                    false
                }
            } else {
                false
            }
        }
    }

    private fun setupMapClickListener() {
        val mapEventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean = false

            override fun longPressHelper(p: GeoPoint?): Boolean {
                p?.let { point ->
                    val projection = mapView.projection
                    val mapPoint = projection.fromPixels(
                        mapView.scrollX + mapView.width / 2,
                        mapView.scrollY + mapView.height / 2
                    ) as GeoPoint

                    if (startPoint == null) {
                        showToast("Primero establezca el punto inicial con el buscador")
                    } else {
                        handleMapClick(mapPoint)
                        saveLocationToJson(mapPoint)
                    }
                    return true
                }
                return false
            }
        })
        mapView.overlays.add(0, mapEventsOverlay)
    }

    private fun handleMapClick(point: GeoPoint) {
        mapView.overlays.removeAll { it is Marker && it.title?.startsWith("Punto final") == true }

        val marker = Marker(mapView).apply {
            position = point
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = "Punto final"
            try {
                icon = ContextCompat.getDrawable(this@OSMMapsActivity, R.drawable.ic_click_marker)?.apply {
                    setBounds(0, 0, intrinsicWidth / 2, intrinsicHeight / 2)
                }
            } catch (e: Exception) {
                Log.e("Marker", "Error cargando icono", e)
            }
        }

        mapView.overlays.add(marker)
        endPoint = point
        calculateDistance()
        mapController.animateTo(point)
        mapView.invalidate()

        Handler(Looper.getMainLooper()).post {
            findAddressFromLocation(point)
        }
    }
    // Variables necesarias
    private var lastRecordedPoint: GeoPoint? = null

    // Función para guardar ubicación
    private fun saveLocationToJson(point: GeoPoint) {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val currentTime = sdf.format(Date())

        val newEntry = JSONObject().apply {
            put("latitud", point.latitude)
            put("longitud", point.longitude)
            put("fecha_hora", currentTime)
        }

        val dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
        val file = File(dir, "ubicaciones.json")

        val jsonArray: JSONArray = if (file.exists()) {
            try {
                JSONArray(file.readText())
            } catch (e: Exception) {
                JSONArray()
            }
        } else {
            JSONArray()
        }

        // Evitar duplicados consecutivos
        val isRepeated = (jsonArray.length() > 0) && run {
            val last = jsonArray.getJSONObject(jsonArray.length() - 1)
            last.getDouble("latitud") == point.latitude &&
                    last.getDouble("longitud") == point.longitude
        }

        // Calcular distancia desde el último punto registrado
        var distance = 0f
        lastRecordedPoint?.let {
            val result = FloatArray(1)
            Location.distanceBetween(
                it.latitude, it.longitude,
                point.latitude, point.longitude,
                result
            )
            distance = result[0]
        }

        if (!isRepeated && (lastRecordedPoint == null || distance > 30)) {
            jsonArray.put(newEntry)

            try {
                val writer = FileWriter(file)
                writer.write(jsonArray.toString(4)) // formato indentado
                writer.close()
                Toast.makeText(this, "Ubicación guardada:\n${file.absolutePath}", Toast.LENGTH_SHORT).show()

                // Mostrar contenido en un diálogo solo si la distancia es mayor a 30 metros
                if (distance > 30 || lastRecordedPoint == null) {
                    AlertDialog.Builder(this)
                        .setTitle("Archivo JSON")
                        .setMessage(jsonArray.toString(4))
                        .setPositiveButton("Cerrar", null)
                        .show()
                }

                lastRecordedPoint = point

            } catch (e: IOException) {
                Toast.makeText(this, "Error al guardar ubicación", Toast.LENGTH_SHORT).show()
            }
        }
    }


    private fun searchAddress(addressString: String) {
        btnSearch.isEnabled = false
        btnSearch.text = "Buscando..."

        if (!Geocoder.isPresent()) {
            showToast("Geocoder no disponible")
            resetSearchUI()
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val addresses = withContext(Dispatchers.IO) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        geocoder.getFromLocationName(addressString, 1)
                    } else {
                        @Suppress("DEPRECATION")
                        geocoder.getFromLocationName(addressString, 1)
                    }
                }

                withContext(Dispatchers.Main) {
                    handleGeocodeResult(addresses ?: emptyList(), addressString)
                    resetSearchUI()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showToast("Error: ${e.message ?: "Desconocido"}")
                    resetSearchUI()
                }
            }
        }
    }

    private fun handleGeocodeResult(addresses: List<Address>, originalQuery: String) {
        if (addresses.isNotEmpty()) {
            val location = addresses[0]
            val point = GeoPoint(location.latitude, location.longitude)
            val addressName = location.getAddressLine(0) ?: originalQuery

            startPoint = point
            addMarker(point, "Punto inicial: $addressName", R.drawable.ic_start_marker)
            mapController.setCenter(point)

            endPoint?.let { calculateDistance() }
        } else {
            showToast("No se encontró '$originalQuery'")
        }
    }

    private fun findAddressFromLocation(point: GeoPoint) {
        endPoint = point
        calculateDistance()

        if (Geocoder.isPresent()) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val addresses = withContext(Dispatchers.IO) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            geocoder.getFromLocation(point.latitude, point.longitude, 1)
                        } else {
                            @Suppress("DEPRECATION")
                            geocoder.getFromLocation(point.latitude, point.longitude, 1)
                        }
                    }

                    withContext(Dispatchers.Main) {
                        handleReverseGeocodeResult(addresses ?: emptyList(), point)
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        addMarker(point, "Punto final (${point.latitude.roundTo(4)}, ${point.longitude.roundTo(4)})",
                            R.drawable.ic_click_marker)
                    }
                }
            }
        } else {
            addMarker(point, "Punto final (${point.latitude.roundTo(4)}, ${point.longitude.roundTo(4)})",
                R.drawable.ic_click_marker)
        }
    }

    private fun handleReverseGeocodeResult(addresses: List<Address>, point: GeoPoint) {
        val addressText = if (addresses.isNotEmpty()) {
            addresses[0].getAddressLine(0) ?: "Dirección desconocida"
        } else {
            "Dirección desconocida"
        }

        addMarker(point, "Punto final: $addressText", R.drawable.ic_click_marker)
    }

    @SuppressLint("SetTextI18n")
    private fun calculateDistance() {
        if (startPoint == null || endPoint == null) return

        val distance = startPoint!!.distanceToAsDouble(endPoint!!)
        val distanceText = if (distance < 1000) {
            "${distance.roundToInt()} metros"
        } else {
            "%.2f km".format(distance / 1000)
        }

        tvDistance.text = "Distancia: $distanceText"
        showToast("Distancia desde punto inicial: $distanceText")
    }

    private fun addMarker(point: GeoPoint, title: String, iconResId: Int) {
        runOnUiThread {
            mapView.overlays.removeAll {
                it is Marker && it.title?.startsWith(
                    if (title.startsWith("Punto inicial")) "Punto inicial"
                    else "Punto final"
                ) == true
            }

            val marker = Marker(mapView).apply {
                position = point
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                this.title = title
                try {
                    icon = ContextCompat.getDrawable(this@OSMMapsActivity, iconResId)?.apply {
                        setBounds(0, 0, intrinsicWidth / 2, intrinsicHeight / 2)
                    }
                } catch (e: Exception) {
                    Log.e("Marker", "Error cargando icono", e)
                }
            }

            mapView.overlays.add(marker)
            mapView.postInvalidate()
        }
    }

    private fun setupLightSensor() {
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        lightSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        } ?: run {
            Log.w("Sensor", "Sensor de luz no disponible")
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            if (it.sensor.type == Sensor.TYPE_LIGHT) {
                adjustMapTheme(it.values[0])
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No se necesita implementación específica
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        lightSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
        sensorManager.unregisterListener(this)
    }

    private fun adjustMapTheme(lightLevel: Float) {
        runOnUiThread {
            val tilesOverlay = mapView.overlayManager.tilesOverlay
            tilesOverlay.setColorFilter(if (lightLevel < LIGHT_THRESHOLD) {
                TilesOverlay.INVERT_COLORS
            } else {
                null
            })
            mapView.invalidate()
        }
    }

    private fun checkAndRequestPermissions() {
        val missingPermissions = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                missingPermissions.toTypedArray(),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                showToast("Permisos concedidos")
            } else {
                showToast("Algunos permisos fueron denegados. Funcionalidad limitada")
            }
        }
    }

    private fun resetSearchUI() {
        btnSearch.isEnabled = true
        btnSearch.text = "Buscar"
    }

    private fun hideKeyboard(view: android.view.View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun Double.roundTo(decimals: Int): Double {
        var multiplier = 1.0
        repeat(decimals) { multiplier *= 10 }
        return (this * multiplier).roundToInt() / multiplier
    }
}