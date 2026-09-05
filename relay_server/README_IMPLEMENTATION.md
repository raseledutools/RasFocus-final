# RasFocus Remote Desktop — Full Implementation Guide
**RustDesk-style PC↔Phone Control with Relay Server**

---

## Architecture Overview

```
┌──────────────────────────────────────────────────────────────────────┐
│                        RasFocus Remote                               │
│                                                                      │
│  PC (Windows EXE)           Relay Server          Phone (Android)   │
│  ─────────────────         ─────────────         ──────────────────  │
│  1. Generate Code           Firebase              1. Open app       │
│     → upload to ──────────► Firestore ◄────────── 2. Lookup code   │
│       Firebase                                    3. Get PC IP+port │
│  2. Start WS server                               4. Connect        │
│     port 9224          (if different network)                        │
│     ──────────────────► relay.rasfocus.com ◄───── Relay fallback   │
│                            /relay/<code>                             │
│  3. Stream H264 ────────────────────────────────► Decode + show    │
│  4. Receive input ◄─────────────────────────────  Touch events      │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Files Created

### EXE Side (C++ Windows)

| File | Description |
|------|-------------|
| `EXE_tab_phone_remote.cpp` | **Replace** existing `tab_phone_remote.cpp` — adds relay registration, copy code button, improved UI |
| `EXE_tab_phone_remote_relay.h` | Relay signaling header — Firebase REST + relay URL config |
| `EXE_tab_phone_remote_relay.cpp` | Firebase Firestore REST upload/delete via WinINet (no extra SDK) |

### APK Side (Kotlin Android)

| File | Description |
|------|-------------|
| `APK_RemoteDesktopScreen.kt` | **Replace** existing `RemoteDesktopScreen.kt` — RustDesk-style UI matching screenshot |
| `APK_RdSignaling.kt` | **Replace** existing `RdSignaling.kt` — adds 6-digit code lookup from `rd_sessions` |
| `APK_RemoteDesktopService_additions.kt` | **Merge into** `RemoteDesktopService.kt` — `connectToPC()`, `sendMouseEvent()`, relay fallback |

### Relay Server (Node.js)

| File | Description |
|------|-------------|
| `relay_server.js` | Standalone WebSocket relay — bridges PC and Phone by 6-digit code |

---

## Step 1: EXE Changes

### 1a. Add relay files to your project

Copy these to your EXE project root:
- `tab_phone_remote_relay.h`
- `tab_phone_remote_relay.cpp`

Add both to your Visual Studio project.

### 1b. Configure Firebase credentials

In `tab_phone_remote_relay.h`, update:
```cpp
static const std::string FIREBASE_PROJECT = "rasfocus-app";   // your Firebase project ID
static const std::string FIREBASE_API_KEY = "AIzaSy...";       // your Web API key
```

Find these in Firebase Console → Project Settings → General.

### 1c. Replace tab_phone_remote.cpp

The new version calls `RelayRegisterSession()` on code generation and `RelayUnregisterSession()` on stop.

**Changes from old version:**
- Calls Firebase on generate/stop
- Adds "Copy Code" button
- Shows "Relay active" in status
- Improved instruction text

---

## Step 2: APK Changes

### 2a. Replace RemoteDesktopScreen.kt

The new UI matches the RustDesk screenshot:
- **Left panel**: "Your Desktop" with ID, online dot, Share Screen button
- **Right panel**: "Control Remote Desktop" with 6-digit code input + Connect
- **Bottom grid**: Recent connections cards (Android=blue, Windows=purple)

### 2b. Replace RdSignaling.kt

Updated to look up `rd_sessions/<code>` (PC codes) in addition to `rd_devices/<id>` (phone IDs).

### 2c. Merge additions into RemoteDesktopService.kt

Add these methods to the service class:
- `connectToPC(devInfo, code)` — direct WS → relay fallback
- `disconnectFromPC()`
- `attachPcView(view)`
- `sendMouseEvent(nx, ny, mask)`
- `sendKeyEvent(vk, action)`
- `sendScrollEvent(nx, ny, dir)`

---

## Step 3: Deploy the Relay Server

### Option A: Any Linux VPS (cheapest)

```bash
# Install Node.js
curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -
sudo apt install -y nodejs

# Copy relay_server.js to server
scp relay_server.js user@your-server:/opt/rasfocus-relay/

# Install dependencies
cd /opt/rasfocus-relay && npm init -y && npm install ws

# Run with PM2 (persistent)
npm install -g pm2
pm2 start relay_server.js --name rasfocus-relay
pm2 save && pm2 startup
```

### Option B: Cloudflare Workers (free, no server)

The relay can also be deployed as a Cloudflare Worker with Durable Objects — ask for this version if needed.

### Option C: Railway.app (free tier, one click)

Deploy `relay_server.js` on Railway.app — free 500h/month.

### SSL Setup (nginx)

```nginx
server {
    listen 443 ssl;
    server_name relay.rasfocus.com;
    
    ssl_certificate     /etc/letsencrypt/live/relay.rasfocus.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/relay.rasfocus.com/privkey.pem;
    
    location /relay {
        proxy_pass http://localhost:9226;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_read_timeout 3600s;
    }
}
```

### Update relay URL in code

After you have your domain, update in:

**EXE** (`tab_phone_remote_relay.h`):
```cpp
static const std::string RELAY_SERVER_URL = "wss://relay.rasfocus.com";
```

**APK** (`RdSignaling.kt`):
```kotlin
private const val RELAY_WS_URL = "wss://relay.rasfocus.com"
```

---

## Step 4: Firestore Rules

In Firebase Console → Firestore → Rules:

```javascript
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    // rd_sessions: PC writes its code here
    match /rd_sessions/{code} {
      allow read: if true;   // phone needs to read
      allow write: if true;  // PC needs to write (code is secret enough)
      allow delete: if true; // PC deletes on stop
    }
    
    // rd_devices: phone writes its ID here
    match /rd_devices/{id} {
      allow read, write: if true;
    }
  }
}
```

---

## How It Works (User Flow)

### Phone → PC (most common use case)

```
PC side:
  1. Open RasFocus EXE
  2. Go to "Phone Remote" tab
  3. Click "Generate Code"
  4. 6-digit code appears (e.g. 123456)
  5. Code auto-uploaded to Firebase

Phone side:
  1. Open RasFocus app
  2. Go to "Remote Desktop" section
  3. Type the 6-digit code
  4. Tap "Connect"
  5. App looks up code in Firebase → gets PC's IP
  6. Tries direct LAN connect (ws://192.168.x.x:9224)
  7. If on different network → uses relay server
  8. Auth handshake → H264 video starts
  9. Touch phone screen → moves PC mouse
```

### PC → Phone (share phone screen)

```
Phone side:
  1. Tap "Share Screen"
  2. Grant MediaProjection permission
  3. Note your ID (e.g. 135 310 219)

PC side:
  1. Enter the 9-digit phone ID
  2. Or use the recent connections panel
```

---

## Protocol Reference

### Auth message (phone → PC)
```json
{"type":"auth","code":"123456","device":"Samsung Galaxy"}
```

### Ready response (PC → phone)
```json
{"type":"ready","width":1280,"height":720,"fps":30,"mode":"h264"}
```

### Mouse input (phone → PC)
```json
{"type":"mouse","nx":0.5,"ny":0.3,"mask":1}
```
mask: 0=move, 1=ldown, 2=lup, 4=rdown, 8=rup

### Key input (phone → PC)
```json
{"type":"key","vk":27,"action":"down"}
```

### Relay messages (relay server → clients)
```json
{"type":"relay_ready","role":"host","code":"123456"}
{"type":"peer_connected","role":"client"}
{"type":"peer_disconnected","role":"host"}
```

---

## Testing Without Relay

For LAN testing (same WiFi network), relay is not needed:
1. Make sure PC and phone are on same WiFi
2. PC generates code → phone types it → auto discovers IP from Firebase → direct connect
3. The relay server URL can be left as placeholder

---

## Troubleshooting

| Issue | Fix |
|-------|-----|
| "Code not found" | PC এ "Generate Code" click করেছে কিনা দেখো |
| "Connect করা যায়নি" | PC firewall এ port 9224 open করো |
| Relay not working | relay_server.js চলছে কিনা দেখো, SSL cert check করো |
| Black screen on phone | H264 decoder error — PcH264Decoder logs দেখো |
| High latency | RELAY_SERVER_URL এর পরিবর্তে নিজের দেশে server দাও |
