package com.example.vescnavigator

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.location.Geocoder
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.google.android.gms.maps.*
import com.google.android.gms.maps.model.*
import com.google.maps.android.PolyUtil
import com.google.maps.android.SphericalUtil
import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.*

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    // =================================================
    // CONFIGURAZIONE
    // =================================================
    private val GOOGLE_API_KEY = "API" // <--- INSERISCI LA TUA KEY QUI

    private val DEVICE_NAME = "MotoNav_ESP32"
    private val SERVICE_UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
    private val CHAR_UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")

    // Velocità Simulazione: 60 km/h = ~16.6 m/s
    private val SIMULATION_SPEED_MPS = 16.6

    // Elementi UI
    private lateinit var mMap: GoogleMap
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var etAddress: EditText
    private lateinit var navPanel: LinearLayout
    private lateinit var bottomPanel: LinearLayout
    private lateinit var imgTurnIcon: ImageView
    private lateinit var tvNavDistance: TextView
    private lateinit var tvNavInstruction: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnConnectBle: Button
    private lateinit var imgCurrentMode: ImageView
    private lateinit var btnRecenter: ImageButton

    // Variabili di Stato
    private var bluetoothGatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var isConnected = false
    private var currentRoutePolyline: Polyline? = null
    private var destinationMarker: Marker? = null
    private var simulationMarker: Marker? = null

    private var travelMode = "DRIVE"
    private var isStyleOneActive = true
    private var isDemoMode = false
    private var isSimulating = false
    private var volumeClickCount = 0
    private val volumeResetHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Collegamento View XML
        etAddress = findViewById(R.id.etAddress)
        navPanel = findViewById(R.id.navPanel)
        bottomPanel = findViewById(R.id.bottomPanel)
        imgTurnIcon = findViewById(R.id.imgTurnIcon)
        tvNavDistance = findViewById(R.id.tvNavDistance)
        tvNavInstruction = findViewById(R.id.tvNavInstruction)
        tvStatus = findViewById(R.id.tvStatus)
        btnConnectBle = findViewById(R.id.btnConnectBle)
        imgCurrentMode = findViewById(R.id.imgCurrentMode)
        btnRecenter = findViewById(R.id.btnRecenter)

        // Inizializza Mappa
        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Listeners Bottoni
        findViewById<Button>(R.id.btnGo).setOnClickListener {
            val addr = etAddress.text.toString()
            if(addr.isNotEmpty()) calculateRoute(addr)
        }

        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener { view ->
            showSettingsMenu(view)
        }

        btnRecenter.setOnClickListener { recenterMap() }
        btnConnectBle.setOnClickListener { startBleScan() }
    }

    // =================================================
    // GESTIONE MAPPA
    // =================================================
    @SuppressLint("MissingPermission")
    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        applyTheme()

        if (checkPermissions()) {
            mMap.isMyLocationEnabled = true
            mMap.uiSettings.isMyLocationButtonEnabled = false // Usiamo il nostro bottone custom
            mMap.uiSettings.isCompassEnabled = false
            recenterMap()
        } else {
            requestPermissions()
        }
    }

    @SuppressLint("MissingPermission")
    private fun recenterMap() {
        if (!checkPermissions()) { requestPermissions(); return }
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                val currentPos = LatLng(location.latitude, location.longitude)
                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(currentPos, 16f))
            } else {
                Toast.makeText(this, "Posizione GPS non trovata", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun applyTheme() {
        if (!::mMap.isInitialized) return
        val styleRes = if (isStyleOneActive) R.raw.map_style_1 else R.raw.map_style_2
        mMap.setMapStyle(MapStyleOptions.loadRawResourceStyle(this, styleRes))

        if (isStyleOneActive) {
            navPanel.background.setTint(Color.parseColor("#EE08304B"))
            bottomPanel.background.setTint(Color.parseColor("#FF08304B"))
            tvNavInstruction.setTextColor(Color.parseColor("#00FFFF"))
        } else {
            navPanel.background.setTint(Color.parseColor("#EE221100"))
            bottomPanel.background.setTint(Color.parseColor("#FF221100"))
            tvNavInstruction.setTextColor(Color.parseColor("#FF9100"))
        }
        currentRoutePolyline?.color = if(isStyleOneActive) Color.CYAN else Color.parseColor("#FF4400")
    }

    // =================================================
    // MENU IMPOSTAZIONI
    // =================================================
    private fun showSettingsMenu(view: View) {
        val popup = PopupMenu(this, view)
        popup.menu.add("Mezzo: ${if(travelMode=="DRIVE") "Auto 🚗" else "Bici 🚲"}")
        popup.menu.add("Cambia Stile Mappa")

        popup.setOnMenuItemClickListener { item ->
            if (item.title.toString().contains("Mezzo")) {
                if (travelMode == "DRIVE") {
                    travelMode = "BICYCLE"
                    imgCurrentMode.setImageResource(R.drawable.ic_bike_mode)
                    Toast.makeText(this, "Modalità BICI", Toast.LENGTH_SHORT).show()
                } else {
                    travelMode = "DRIVE"
                    imgCurrentMode.setImageResource(R.drawable.ic_car_mode)
                    Toast.makeText(this, "Modalità AUTO", Toast.LENGTH_SHORT).show()
                }
            } else {
                isStyleOneActive = !isStyleOneActive
                applyTheme()
            }
            true
        }
        popup.show()
    }

    // =================================================
    // CALCOLO PERCORSO (Google Routes API)
    // =================================================
    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun calculateRoute(destName: String) {
        isSimulating = false
        simulationMarker?.remove()

        Thread {
            try {
                val geocoder = Geocoder(this, Locale.getDefault())
                val addrs = geocoder.getFromLocationName(destName, 1)
                if (addrs.isNullOrEmpty()) {
                    runOnUiThread { Toast.makeText(this, "Indirizzo non trovato", Toast.LENGTH_SHORT).show() }
                    return@Thread
                }
                val dest = addrs[0]

                if (!checkPermissions()) return@Thread

                fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                    if (location == null) return@addOnSuccessListener

                    val url = "https://routes.googleapis.com/directions/v2:computeRoutes"
                    val bodyJson = """
                    {
                      "origin": {"location": {"latLng": {"latitude": ${location.latitude}, "longitude": ${location.longitude}}}},
                      "destination": {"location": {"latLng": {"latitude": ${dest.latitude}, "longitude": ${dest.longitude}}}},
                      "travelMode": "$travelMode",
                      "routingPreference": "TRAFFIC_AWARE",
                      "polylineQuality": "HIGH_QUALITY"
                    }
                    """.trimIndent()

                    val request = Request.Builder()
                        .url(url)
                        .addHeader("X-Goog-Api-Key", GOOGLE_API_KEY)
                        .addHeader("X-Goog-FieldMask", "routes.polyline.encodedPolyline,routes.legs.steps")
                        .post(bodyJson.toRequestBody("application/json".toMediaType()))
                        .build()

                    OkHttpClient().newCall(request).enqueue(object : Callback {
                        override fun onFailure(call: Call, e: java.io.IOException) {
                            Log.e("NAV_DEBUG", "Errore Rete: ${e.message}")
                        }
                        override fun onResponse(call: Call, response: Response) {
                            val resData = response.body?.string() ?: return
                            val json = Gson().fromJson(resData, JsonObject::class.java)

                            if (json.has("routes") && json.getAsJsonArray("routes").size() > 0) {
                                val route = json.getAsJsonArray("routes")[0].asJsonObject
                                val poly = route.getAsJsonObject("polyline").get("encodedPolyline").asString
                                val steps = route.getAsJsonArray("legs")[0].asJsonObject.getAsJsonArray("steps")

                                runOnUiThread {
                                    val routePoints = drawRoute(poly)
                                    navPanel.visibility = View.VISIBLE

                                    // Aggiorna subito la prima istruzione
                                    if (steps.size() > 0) {
                                        val firstStep = steps[0].asJsonObject
                                        val dist = firstStep.getAsJsonObject("localizedValues").getAsJsonObject("distance").get("text").asString
                                        val inst = firstStep.getAsJsonObject("navigationInstruction").get("instructions").asString
                                        val maneuver = firstStep.getAsJsonObject("navigationInstruction").get("maneuver")?.asString ?: "STRAIGHT"
                                        updateUI(maneuver, dist, inst)
                                    }

                                    if (isDemoMode) {
                                        Toast.makeText(this@MainActivity, "Avvio Simulazione...", Toast.LENGTH_SHORT).show()
                                        runSimulation(routePoints, steps)
                                    }
                                }
                            } else {
                                Log.e("NAV_DEBUG", "Risposta Google Errata: $resData")
                            }
                        }
                    })
                }
            } catch (e: Exception) { Log.e("NAV", e.message ?: "") }
        }.start()
    }

    private fun drawRoute(poly: String): List<LatLng> {
        val path = PolyUtil.decode(poly)
        currentRoutePolyline?.remove()
        destinationMarker?.remove()

        val color = if(isStyleOneActive) Color.CYAN else Color.parseColor("#FF4400")
        currentRoutePolyline = mMap.addPolyline(PolylineOptions().addAll(path).color(color).width(15f))

        if (path.isNotEmpty()) {
            val arrivalLatLng = path.last()
            try {
                // Pin personalizzato (scalato 120x120)
                val pinIcon = BitmapDescriptorFactory.fromBitmap(getBitmapFromResource(R.drawable.dest_pin, 120, 120))
                destinationMarker = mMap.addMarker(MarkerOptions().position(arrivalLatLng).icon(pinIcon).anchor(0.5f, 1.0f).title("Arrivo"))
            } catch (e: Exception) {
                // Fallback pin default
                destinationMarker = mMap.addMarker(MarkerOptions().position(arrivalLatLng).title("Arrivo"))
            }
        }

        // Zoom dinamico che include tutto il percorso
        val builder = LatLngBounds.Builder()
        path.forEach { builder.include(it) }
        try {
            mMap.animateCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), 150))
        } catch (e: Exception) {
            mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(path[0], 15f))
        }

        return path
    }

    // =================================================
    // SIMULAZIONE
    // =================================================
    private fun runSimulation(polyLinePoints: List<LatLng>, steps: com.google.gson.JsonArray) {
        isSimulating = true
        Thread {
            runOnUiThread {
                mMap.isMyLocationEnabled = false
                simulationMarker?.remove()
                val startPos = polyLinePoints[0]
                // Marker blu per la simulazione
                simulationMarker = mMap.addMarker(MarkerOptions()
                    .position(startPos)
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
                    .anchor(0.5f, 0.5f)
                    .flat(true)
                    .title("Simulazione"))
            }

            var nextStepIndex = 0
            for (i in 0 until polyLinePoints.size - 1) {
                if (!isDemoMode || !isSimulating) break

                val p1 = polyLinePoints[i]
                val p2 = polyLinePoints[i+1]
                val distance = SphericalUtil.computeDistanceBetween(p1, p2)
                val durationMs = (distance / SIMULATION_SPEED_MPS * 1000).toLong()
                val bearing = SphericalUtil.computeHeading(p1, p2).toFloat()

                runOnUiThread {
                    simulationMarker?.position = p2
                    simulationMarker?.rotation = bearing
                    mMap.animateCamera(CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder().target(p2).zoom(18f).bearing(bearing).tilt(60f).build()
                    ), durationMs.toInt(), null)
                }

                if (nextStepIndex < steps.size()) {
                    val step = steps[nextStepIndex].asJsonObject
                    val endLat = step.getAsJsonObject("endLocation").getAsJsonObject("latLng").get("latitude").asDouble
                    val endLng = step.getAsJsonObject("endLocation").getAsJsonObject("latLng").get("longitude").asDouble
                    val stepPos = LatLng(endLat, endLng)
                    val distToTurn = SphericalUtil.computeDistanceBetween(p2, stepPos)

                    val distStr = if (distToTurn > 1000) String.format("%.1f km", distToTurn/1000) else "${distToTurn.toInt()} m"
                    val inst = step.getAsJsonObject("navigationInstruction").get("instructions").asString
                    val maneuver = step.getAsJsonObject("navigationInstruction").get("maneuver")?.asString ?: "STRAIGHT"

                    runOnUiThread { updateUI(maneuver, distStr, inst) }

                    if (distToTurn < 200 || i % 10 == 0) {
                        val code = mapManeuverToCode(maneuver)
                        sendBleData("$code;$distStr;$inst")
                    }
                    if (distToTurn < 20) nextStepIndex++
                }
                Thread.sleep(durationMs)
            }
            runOnUiThread {
                Toast.makeText(this, "Simulazione Terminata", Toast.LENGTH_LONG).show()
                isSimulating = false
                recenterMap()
            }
        }.start()
    }

    private fun mapManeuverToCode(maneuver: String): Int {
        return when(maneuver.replace("\"", "")) {
            "TURN_LEFT", "RAMP_LEFT" -> 0
            "TURN_RIGHT", "RAMP_RIGHT" -> 1
            "TURN_SHARP_LEFT" -> 2
            "TURN_SHARP_RIGHT" -> 3
            "TURN_SLIGHT_LEFT" -> 4
            "TURN_SLIGHT_RIGHT" -> 5
            "STRAIGHT", "MERGE" -> 6
            "ROUNDABOUT_RIGHT", "ROUNDABOUT_LEFT" -> 7
            "UTURN_LEFT", "UTURN_RIGHT" -> 11
            else -> 6
        }
    }

    private fun updateUI(maneuver: String, dist: String, inst: String) {
        val code = mapManeuverToCode(maneuver)
        tvNavDistance.text = dist
        tvNavInstruction.text = inst
        imgTurnIcon.rotation = when(code){ 0->-90f; 1->90f; 2->-120f; 3->120f; 4->-45f; 5->45f; 11->180f; else->0f }
    }

    // =================================================
    // BLUETOOTH E COMANDI MANUALI
    // =================================================
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            volumeClickCount++
            volumeResetHandler.removeCallbacksAndMessages(null)
            volumeResetHandler.postDelayed({ volumeClickCount = 0 }, 2000)
            if (volumeClickCount >= 3) {
                isDemoMode = !isDemoMode
                volumeClickCount = 0
                val status = if (isDemoMode) "ATTIVA" else "SPENTA"
                Toast.makeText(this, "DEMO: $status", Toast.LENGTH_SHORT).show()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter
        if (adapter == null || !adapter.isEnabled) { Toast.makeText(this, "Attiva Bluetooth!", Toast.LENGTH_SHORT).show(); return }
        tvStatus.text = "Scansione..."
        adapter.bluetoothLeScanner.startScan(object : ScanCallback() {
            @SuppressLint("MissingPermission")
            override fun onScanResult(ct: Int, r: ScanResult?) {
                if (r?.device?.name == DEVICE_NAME) {
                    adapter.bluetoothLeScanner.stopScan(this)
                    r.device.connectGatt(this@MainActivity, false, gattCallback)
                }
            }
        })
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, s: Int, ns: Int) {
            if (ns == BluetoothProfile.STATE_CONNECTED) {
                isConnected = true; g.discoverServices(); runOnUiThread { tvStatus.text = "CONNESSO"; tvStatus.setTextColor(Color.GREEN) }
            } else { isConnected = false; runOnUiThread { tvStatus.text = "DISCONNESSO"; tvStatus.setTextColor(Color.RED) } }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, s: Int) { writeChar = g.getService(SERVICE_UUID)?.getCharacteristic(CHAR_UUID) }
    }

    @SuppressLint("MissingPermission")
    private fun sendBleData(data: String) {
        // Log visibile sempre per debug
        Log.e("MOTO_NAV_LOG", ">>> BLE SEND: $data")

        if (!isConnected || writeChar == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            bluetoothGatt?.writeCharacteristic(writeChar!!, data.toByteArray(), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            writeChar?.value = data.toByteArray()
            bluetoothGatt?.writeCharacteristic(writeChar)
        }
    }

    private fun getBitmapFromResource(resId: Int, width: Int, height: Int): Bitmap {
        val drawable = ContextCompat.getDrawable(this, resId)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable?.setBounds(0, 0, canvas.width, canvas.height)
        drawable?.draw(canvas)
        return bitmap
    }

    private fun checkPermissions(): Boolean {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) { perms.add(Manifest.permission.BLUETOOTH_SCAN); perms.add(Manifest.permission.BLUETOOTH_CONNECT) }
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, gr: IntArray) {
        super.onRequestPermissionsResult(rc, p, gr)
        if (gr.isNotEmpty() && gr[0] == PackageManager.PERMISSION_GRANTED) onMapReady(mMap)
    }
}