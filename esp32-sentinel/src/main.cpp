// WakePilot ESP32-C3 Drop-in Hardware Sentinel (<0.5W, ~15 RMB)
// Speaks the EXACT same WakePilot v1 Blind-Relay WebSocket + HKDF-SHA256 + AES-256-GCM protocol!
// Swap out your spare Android phone anytime with ZERO changes to the Main Phone Controller App.

#include <Arduino.h>
#include <WiFi.h>
#include <WiFiUdp.h>
#include <WebSocketsClient.h>
#include <ArduinoJson.h>
#include <mbedtls/sha256.h>
#include <mbedtls/md.h>
#include <mbedtls/gcm.h>
#include <mbedtls/base64.h>

// ======== User Configuration ========
static const char* WIFI_SSID     = "YOUR_HOME_WIFI";
static const char* WIFI_PASS     = "YOUR_WIFI_PASS";
static const char* RELAY_HOST    = "YOUR_SERVER_IP";
static const uint16_t RELAY_PORT = 8443;
static const char* SENTINEL_ID   = "sp_esp32c3_01";
// Paste 32-byte base64url room_key (same secret shared via QR with Main Phone)
static const char* ROOM_KEY_B64  = "REPLACE_WITH_43_CHAR_BASE64URL_ROOM_KEY";
static const uint8_t PC_MAC[6]   = {0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF};
static const char* BCAST_IP      = "255.255.255.255";

WebSocketsClient ws;
WiFiUDP udp;
uint8_t g_roomKey[32];
uint8_t g_c2sKey[32];
char g_channelHex[65];

static size_t base64UrlDecode(const char* in, uint8_t* out, size_t maxOut) {
  String s(in);
  s.replace('-', '+');
  s.replace('_', '/');
  while (s.length() % 4 != 0) s += '=';
  size_t olen = 0;
  mbedtls_base64_decode(out, maxOut, &olen, (const unsigned char*)s.c_str(), s.length());
  return olen;
}

static void deriveKeys() {
  // 1) ChannelToken = hex(SHA-256("wakepilot-channel-v1:" || room_key))
  uint8_t digest[32];
  mbedtls_sha256_context ctx;
  mbedtls_sha256_init(&ctx);
  mbedtls_sha256_starts(&ctx, 0);
  const char* prefix = "wakepilot-channel-v1:";
  mbedtls_sha256_update(&ctx, (const unsigned char*)prefix, strlen(prefix));
  mbedtls_sha256_update(&ctx, g_roomKey, 32);
  mbedtls_sha256_finish(&ctx, digest);
  mbedtls_sha256_free(&ctx);
  for (int i = 0; i < 32; ++i) sprintf(g_channelHex + i * 2, "%02x", digest[i]);
  g_channelHex[64] = '\0';

  // 2) HKDF-SHA256(ikm=room_key, salt="wakepilot-e2ee-v1", info="controller->sentinel")
  const mbedtls_md_info_t* md = mbedtls_md_info_from_type(MBEDTLS_MD_SHA256);
  uint8_t prk[32];
  const char* salt = "wakepilot-e2ee-v1";
  mbedtls_md_hmac(md, (const unsigned char*)salt, strlen(salt), g_roomKey, 32, prk);

  const char* info = "controller->sentinel";
  uint8_t infoBlock[32];
  size_t infoLen = strlen(info);
  memcpy(infoBlock, info, infoLen);
  infoBlock[infoLen] = 0x01;
  mbedtls_md_hmac(md, prk, 32, infoBlock, infoLen + 1, g_c2sKey);
}

static void sendWolMagicPacket() {
  uint8_t pkt[102];
  memset(pkt, 0xFF, 6);
  for (int i = 0; i < 16; ++i) memcpy(pkt + 6 + i * 6, PC_MAC, 6);

  for (int b = 0; b < 3; ++b) {
    udp.beginPacket(BCAST_IP, 9);
    udp.write(pkt, sizeof(pkt));
    udp.endPacket();
    delay(150);
  }
  Serial.println("[WakePilot ESP32] WoL Magic Packet burst sent!");
}

static void handleEnvelope(const uint8_t* payload, size_t len) {
  JsonDocument doc;
  if (deserializeJson(doc, payload, len)) return;
  if (doc["v"] != 1 || strcmp(doc["dir"] | "", "c2s") != 0) return;

  const char* nB64 = doc["n"] | "";
  const char* ctB64 = doc["ct"] | "";
  long ts = doc["ts"] | 0L;

  uint8_t nonce[16];
  uint8_t ctBuf[512];
  size_t nLen = base64UrlDecode(nB64, nonce, sizeof(nonce));
  size_t ctLen = base64UrlDecode(ctB64, ctBuf, sizeof(ctBuf));
  if (nLen != 12 || ctLen <= 16) return;

  char aad[128];
  int aadLen = snprintf(aad, sizeof(aad), "1|%s|c2s|%ld", SENTINEL_ID, ts);

  uint8_t plain[512];
  size_t plainLen = ctLen - 16;
  mbedtls_gcm_context gcm;
  mbedtls_gcm_init(&gcm);
  mbedtls_gcm_setkey(&gcm, MBEDTLS_CIPHER_ID_AES, g_c2sKey, 256);
  int rc = mbedtls_gcm_auth_decrypt(
      &gcm, plainLen, nonce, 12,
      (const unsigned char*)aad, aadLen,
      ctBuf + plainLen, 16,
      ctBuf, plain);
  mbedtls_gcm_free(&gcm);
  if (rc != 0) {
    Serial.println("[WakePilot ESP32] AES-GCM auth failed, ignoring frame.");
    return;
  }
  plain[plainLen] = '\0';

  JsonDocument cmd;
  if (deserializeJson(cmd, (const char*)plain)) return;
  if (strcmp(cmd["type"] | "", "WAKE") == 0) {
    sendWolMagicPacket();
  }
}

void setup() {
  Serial.begin(115200);
  base64UrlDecode(ROOM_KEY_B64, g_roomKey, 32);
  deriveKeys();

  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASS);
  while (WiFi.status() != WL_CONNECTED) { delay(500); Serial.print("."); }
  Serial.println("\nWi-Fi connected.");

  String wsPath = String("/ws?role=sentinel&id=") + SENTINEL_ID + "&channel=" + g_channelHex;
  ws.begin(RELAY_HOST, RELAY_PORT, wsPath);
  ws.onEvent([](WStype_t type, uint8_t* payload, size_t len) {
    if (type == WStype_TEXT) handleEnvelope(payload, len);
  });
  ws.setReconnectInterval(3000);
  ws.enableHeartbeat(25000, 5000, 2);
}

void loop() {
  ws.loop();
}
