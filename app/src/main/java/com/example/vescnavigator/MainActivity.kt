package com.example.vescnavigator

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.location.Geocoder
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
import com.google.android.gms.location.*
import com.google.android.gms.maps.*
import com.google.android.gms.maps.model.*
import com.google.maps.android.PolyUtil
import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.*

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    // --- CONFIG (Metti la tua API KEY qui) ---
    private val GOOGLE_API_KEY = "LA_TUA_API_KEY_QUI"
    private val DEVICE_NAME = "MotoNav_ESP32"
    private val SERVICE_UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
    private val CHAR_UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")

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

    private var bluetoothGatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var isConnected = false
    private var currentRoutePolyline: Polyline? = null

    private var travelMode = "DRIVE"
    private var isStyleOneActive = true
    private var isDemoMode = false
    private var volumeClickCount = 0
    private val volumeResetHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etAddress = findViewById(R.id.etAddress)
        navPanel = findViewById(R.id.navPanel)
        bottomPanel = findViewById(R.id.bottomPanel)
        imgTurnIcon = findViewById(R.id.imgTurnIcon)
        tvNavDistance = findViewById(R.id.tvNavDistance)
        tvNavInstruction = findViewById(R.id.tvNavInstruction)
        tvStatus = findViewById(R.id.tvStatus)
        btnConnectBle = findViewById(R.id.btnConnectBle)

        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        findViewById<Button>(R.id.btnGo).setOnClickListener {
            val addr = etAddress.text.toString()
            if(addr.isNotEmpty()) calculateRoute(addr)
        }

        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener { view ->
            showSettingsMenu(view)
        }

        btnConnectBle.setOnClickListener { startBleScan() }
    }

    // --- FIX ZOOM: Centra la camera appena la mappa è pronta ---
    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        applyTheme()

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            mMap.isMyLocationEnabled = true
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    val currentPos = LatLng(location.latitude, location.longitude)
                    mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(currentPos, 16f))
                }
            }
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
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

    private fun showSettingsMenu(view: View) {
        val popup = PopupMenu(this, view)
        popup.menu.add("Mezzo: ${if(travelMode=="DRIVE") "Auto 🚗" else "Bici 🚲"}")
        popup.menu.add("Cambia Stile Mappa")
        popup.setOnMenuItemClickListener { item ->
            if (item.title.toString().contains("Mezzo")) {
                travelMode = if(travelMode == "DRIVE") "BICYCLE" else "DRIVE"
                Toast.makeText(this, "Mezzo cambiato in $travelMode", Toast.LENGTH_SHORT).show()
            } else {
                isStyleOneActive = !isStyleOneActive
                applyTheme()
            }
            true
        }
        popup.show()
    }

    private fun calculateRoute(destName: String) {
        Thread {
            try {
                val geocoder = Geocoder(this, Locale.getDefault())
                val addrs = geocoder.getFromLocationName(destName, 1)
                if (addrs.isNullOrEmpty()) {
                    runOnUiThread { Toast.makeText(this, "Indirizzo non trovato", Toast.LENGTH_SHORT).show() }
                    return@Thread
                }
                val dest = addrs[0]

                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return@Thread

                fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                    if (location == null) return@addOnSuccessListener
                    val url = "https://routes.googleapis.com/directions/v2:computeRoutes"
                    val bodyJson = """{"origin":{"location":{"latLng":{"latitude":${location.latitude},"longitude":${location.longitude}}}},"destination":{"location":{"latLng":{"latitude":${dest.latitude},"longitude":${dest.longitude}}}},"travelMode":"$travelMode","routingPreference":"TRAFFIC_AWARE"}"""

                    val request = Request.Builder()
                        .url(url)
                        .addHeader("X-Goog-Api-Key", GOOGLE_API_KEY)
                        .addHeader("X-Goog-FieldMask", "routes.polyline.encodedPolyline,routes.legs.steps")
                        .post(bodyJson.toRequestBody("application/json".toMediaType()))
                        .build()

                    OkHttpClient().newCall(request).enqueue(object : Callback {
                        override fun onFailure(call: Call, e: java.io.IOException) {
                            Log.e("NAV_DEBUG", "Fallimento rete: ${e.message}")
                        }
                        override fun onResponse(call: Call, response: Response) {
                            val resData = response.body?.string() ?: ""
                            Log.d("NAV_DEBUG", "Risposta: $resData")

                            val json = Gson().fromJson(resData, JsonObject::class.java)

                            // --- FIX CRASH: Controllo se 'routes' esiste ed è pieno ---
                            if (json.has("routes") && json.getAsJsonArray("routes").size() > 0) {
                                val route = json.getAsJsonArray("routes")[0].asJsonObject
                                val poly = route.getAsJsonObject("polyline").get("encodedPolyline").asString
                                val steps = route.getAsJsonArray("legs")[0].asJsonObject.getAsJsonArray("steps")
                                runOnUiThread { drawRoute(poly); navPanel.visibility = View.VISIBLE; if(isDemoMode) runDemo(steps) }
                            } else {
                                runOnUiThread { Toast.makeText(this@MainActivity, "Google Error: Controlla API Key o Billing", Toast.LENGTH_LONG).show() }
                            }
                        }
                    })
                }
            } catch (e: Exception) { Log.e("NAV_DEBUG", "Errore critico: ${e.message}") }
        }.start()
    }

    private fun drawRoute(poly: String) {
        val path = PolyUtil.decode(poly)
        currentRoutePolyline?.remove()
        val color = if(isStyleOneActive) Color.CYAN else Color.parseColor("#FF4400")
        currentRoutePolyline = mMap.addPolyline(PolylineOptions().addAll(path).color(color).width(15f))
        mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(path[0], 16f))
    }

    private fun runDemo(steps: com.google.gson.JsonArray) {
        Thread {
            for (i in 0 until steps.size()) {
                if (!isDemoMode) break
                val step = steps[i].asJsonObject
                val dist = step.getAsJsonObject("localizedValues").getAsJsonObject("distance").get("text").asString
                val inst = step.getAsJsonObject("navigationInstruction").get("instructions").asString
                val maneuver = step.getAsJsonObject("navigationInstruction").get("maneuver")?.asString ?: "STRAIGHT"
                val code = when(maneuver) { "TURN_LEFT"->0; "TURN_RIGHT"->1; "TURN_SHARP_LEFT"->2; "TURN_SHARP_RIGHT"->3; else->6 }
                runOnUiThread {
                    tvNavDistance.text = dist
                    tvNavInstruction.text = inst
                    imgTurnIcon.rotation = when(code){0->-90f;1->90f;2->-120f;3->120f;else->0f}
                }
                sendBleData("$code;$dist;$inst")
                Thread.sleep(4000)
            }
        }.start()
    }

    // --- BLUETOOTH ---
    private fun startBleScan() {
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter
        if (adapter == null || !adapter.isEnabled) return
        tvStatus.text = "Ricerca ESP32..."
        adapter.bluetoothLeScanner.startScan(object : ScanCallback() {
            override fun onScanResult(ct: Int, r: ScanResult?) {
                if (r?.device?.name == DEVICE_NAME) {
                    adapter.bluetoothLeScanner.stopScan(this)
                    r.device.connectGatt(this@MainActivity, false, gattCallback)
                }
            }
        })
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, s: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                isConnected = true; bluetoothGatt = g; g.discoverServices()
                runOnUiThread { tvStatus.text = "CONNESSO"; tvStatus.setTextColor(Color.GREEN) }
            }
            else { isConnected = false; runOnUiThread { tvStatus.text = "DISCONNESSO"; tvStatus.setTextColor(Color.RED) } }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, s: Int) {
            writeChar = g.getService(SERVICE_UUID)?.getCharacteristic(CHAR_UUID)
        }
    }

    private fun sendBleData(data: String) {
        if (!isConnected || writeChar == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            bluetoothGatt?.writeCharacteristic(writeChar!!, data.toByteArray(), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            writeChar?.value = data.toByteArray()
            bluetoothGatt?.writeCharacteristic(writeChar)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            volumeClickCount++
            volumeResetHandler.removeCallbacksAndMessages(null)
            volumeResetHandler.postDelayed({ volumeClickCount = 0 }, 2000)
            if (volumeClickCount >= 3) {
                isDemoMode = !isDemoMode
                volumeClickCount = 0
                Toast.makeText(this, "DEMO: ${if(isDemoMode) "ATTIVA" else "SPENTA"}", Toast.LENGTH_SHORT).show()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}