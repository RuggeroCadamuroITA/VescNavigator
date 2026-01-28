#include <Arduino.h> // Obbligatorio su PlatformIO
#include <BLEDevice.h>
#include <BLEUtils.h>
#include <BLEServer.h>

// Definisco il nome del dispositivo
#define DEVICE_NAME "MotoNav_ESP32"

// UUID univoci
#define SERVICE_UUID        "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
#define CHARACTERISTIC_UUID "beb5483e-36e1-4688-b7f5-ea07361b26a8"

BLECharacteristic *pCharacteristic;
bool deviceConnected = false;

// Callback ricezione dati
class MyCallbacks: public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic *pCharacteristic) {
      std::string value = pCharacteristic->getValue();

      if (value.length() > 0) {
        String receivedData = String(value.c_str());
        
        Serial.println("========= DATI RICEVUTI =========");
        Serial.println(receivedData); 

        // Parsing: "CODICE;DISTANZA;VIA"
        int firstSemi = receivedData.indexOf(';');
        int secondSemi = receivedData.indexOf(';', firstSemi + 1);

        if (firstSemi > 0 && secondSemi > 0) {
          String iconCode = receivedData.substring(0, firstSemi);
          String distance = receivedData.substring(firstSemi + 1, secondSemi);
          String streetName = receivedData.substring(secondSemi + 1);

          Serial.print("Direzione: "); Serial.println(iconCode);
          Serial.print("Distanza: "); Serial.println(distance);
          Serial.print("Strada: "); Serial.println(streetName);
        }
        Serial.println("=================================\n");
      }
    }
};

// Callback connessione
class ServerCallbacks: public BLEServerCallbacks {
    void onConnect(BLEServer* pServer) {
      deviceConnected = true;
      Serial.println(">> Telefono CONNESSO <<");
    };

    void onDisconnect(BLEServer* pServer) {
      deviceConnected = false;
      Serial.println(">> Telefono DISCONNESSO <<");
      BLEDevice::startAdvertising(); // Riavvia la visibilità
    }
};

void setup() {
  // Ritardo di sicurezza per dare tempo all'USB di attivarsi
  delay(2000); 
  
  Serial.begin(115200);
  Serial.println("Avvio Navigatore ESP32 (S3)...");

  BLEDevice::init(DEVICE_NAME);
  BLEServer *pServer = BLEDevice::createServer();
  pServer->setCallbacks(new ServerCallbacks());

  BLEService *pService = pServer->createService(SERVICE_UUID);

  pCharacteristic = pService->createCharacteristic(
                      CHARACTERISTIC_UUID,
                      BLECharacteristic::PROPERTY_READ |
                      BLECharacteristic::PROPERTY_WRITE
                    );

  pCharacteristic->setCallbacks(new MyCallbacks());
  pService->start();

  BLEAdvertising *pAdvertising = BLEDevice::getAdvertising();
  pAdvertising->addServiceUUID(SERVICE_UUID);
  pAdvertising->setScanResponse(true);
  pAdvertising->setMinPreferred(0x06);  
  pAdvertising->setMinPreferred(0x12);
  BLEDevice::startAdvertising();
  
  Serial.println("In attesa di connessione Bluetooth...");
}

void loop() {
  delay(1000);
}