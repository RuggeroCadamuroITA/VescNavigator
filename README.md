# VescNavigator Pro 🏍️⚡

**Developer:** Ruggero Cadamuro  
**Platform:** Android (Kotlin) + ESP32 S3 (C++/PlatformIO)  
**Core Tech:** Google Maps Routes API, Bluetooth Low Energy (BLE), VESC UART Telemetry.

---

## 📌 Project Overview
VescNavigator is a professional Smart HUD (Head-Up Display) designed for electric vehicles (E-Scooters, E-Bikes, Motorcycles) using **VESC controllers**. 
The system bridges a high-end Android Navigation App with an ESP32 S3 controller to provide real-time turn-by-turn directions and live motor telemetry (Voltage, Speed, Power) on a single compact display.

---

## 🛠️ Feature Checklist

### 📱 Android App (The Brain)
*   ✅ **Google Maps Integration:** Professional map rendering using Maps SDK.
*   ✅ **Routes API v2:** Real-time pathfinding with precise maneuver detection.
*   ✅ **Dynamic Theming:** Seamless switching between **Cyber Blue** and **Vintage Orange** styles.
*   ✅ **Total Dark Mode:** OLED-friendly UI with no intrusive colors.
*   ✅ **Bluetooth LE Engine:** Automatic scanning and secure data transmission to ESP32.
*   ✅ **Stealth Demo Mode:** Simulation mode (60 km/h) activated via **Volume Up x3**.
*   ✅ **Smart Rerouting:** Auto-recalculation when off-track (> 50m).
*   ✅ **Travel Profiles:** Toggle between Driving (Car 🚗) and Cycling (Bike 🚲).
*   ✅ **Custom UI:** Custom markers, navigation HUD, and mode indicators.

### 🧠 ESP32 S3 Firmware (The Hub)
*   ✅ **BLE GATT Server:** Handles secure connections with the Android smartphone.
*   ✅ **Data Parsing:** Decodes navigation packets (`DirectionCode;Distance;StreetName`).
*   ✅ **VESC UART Logic:** Hardware Serial communication (19200 baud) for motor data.
*   ✅ **Telemetry Calculations:** Real-time math for Wattage, Battery %, and Velocity.
*   ❌ **Graphics Engine:** Drawing arrows/text on the screen (Pending Display Hardware selection).
*   ❌ **Combined HUD:** Merging Navigation + VESC Data on one screen.

---

## ⚡ VESC Telemetry Logic
The ESP32 S3 core integrates the following telemetry calculations (ported from Atmega32u4):
- **Voltage:** Real-time battery voltage monitoring.
- **Current:** Live Amperage draw.
- **Wattage:** Calculated as `Voltage * Current`.
- **Battery %:** Smart estimation based on 3.4V (Empty) to 4.2V (Full) cell curve.
- **Velocity:** Precision speed tracking based on ERPM, Motor Poles, and Wheel Diameter.

---

## 🚀 Getting Started

### 1. Android Setup
1. Clone this repository.
2. Open in Android Studio.
3. Add your `GOOGLE_API_KEY` in `AndroidManifest.xml` and `MainActivity.kt`.
4. Ensure `map_style_1.json` and `map_style_2.json` are in `app/src/main/res/raw`.
5. Build and install the APK.

### 2. ESP32 S3 Setup
1. Open the project in **PlatformIO**.
2. Configure `platformio.ini` for ESP32 S3.
3. Flash the firmware and open Serial Monitor (115200 baud) to see incoming BLE data.

### 3. Usage
- Open the App and accept permissions.
- Click **"CONNETTI ESP32"**.
- Enter destination and click **"VAI"**.
- (Optional) Triple-click **Volume Up** to start the Simulation Mode.

---

## 📈 Roadmap
- [ ] Finalize Display Hardware (Round GC9A01 or Rectangular TFT).
- [ ] Implement graphical library (TFT_eSPI or LovyanGFX).
- [ ] Design 3D-printable handlebar mount.
- [ ] Add Haptic Feedback for upcoming turns.

---

**Ruggero Cadamuro** - *Innovative EV Solutions*
