# UltraWatch

<img src="app/src/main/res/drawable-nodpi/hvt_logo.png" width="96" alt="Aitecsek & Harshvardhan Technologies logo">

**Created by Harshvardhan Technologies**

A premium smartwatch app for Wear OS, plus a phone companion app that brings alerts, health data
and watch faces to watches that can't run apps (Realme, Nothing/CMF, Honor, Vivo, Coros, Mi Band...).

## Project layout

| Module | What it is |
|---|---|
| `core/` | Pure Kotlin logic shared by both apps — health maths, vitals interpretation, Bluetooth parsers, markets and alerts, fall detection, GPS, Track Back, voice commands, face designer, app lock, battery protocol. **74 unit tests.** |
| `app/` | Wear OS watch app (Kotlin, Jetpack Compose for Wear OS) with watch face, tiles and complications |
| `mobile/` | Android phone companion app |

## Features

**Watch app (Wear OS 3+)**
- Health: live heart rate and zones, steps and distance, stress estimate, VO2 max estimate, breathing pacer
- Energy Score (0–100) every morning from sleep, resting heart rate and training load; cardio load (TRIMP) and 7/28-day load ratio
- Workouts with GPS route, pace, calories; Multisport/triathlon mode; Track Back to retrace your route
- Sleep tracking with sleep score
- Vitals from **any standard Bluetooth device** (no brand SDK): BP cuffs, pulse oximeters (SpO2 + overnight ODI apnea screening), chest straps (HRV, irregular rhythm indicator), weight and body-composition scales; manual BP entry; body-composition estimates from published formulas. Screening only, not diagnosis.
- Calls: favourites, contacts, keypad, voice ("call mom", "mummy ko call karo")
- Messages: read and reply to SMS, WhatsApp, Telegram etc. from notifications; smart quick replies (incl. Hindi greetings); voice, keyboard; send new SMS on LTE watches
- Music: on-watch library, Bluetooth headphones, background playback, shuffle/repeat, stream URLs
- Video: on-watch videos or URLs (MP4/HLS/DASH)
- Markets: NSE watchlist from your own endpoint (e.g. SLBA PRO) or labelled demo data; price/percent alerts checked every 15 min; NIFTY complication and tile
- AI: Gemini assistant (your API key), voice commands, Maps navigation/WhatsApp shortcuts, face-to-face translator (17 languages, on-device after download), live transcription
- GPS: fix quality, satellites per constellation, dual-frequency (L1+L5) detection, coordinates (decimal/DMS), speed, compass, waypoints with arrow navigation
- Safety: hold-to-SOS (texts location + calls), siren, fall detection with 30 s "Are you OK?", safety check-in timer, location sharing
- Water mode: water lock, pressure-based depth estimate (not a dive computer), altitude
- Batteries: watch (with time estimate), phone (via companion app), Bluetooth earbuds/headphones/sensors; low-battery alerts; 80 % charge reminder; Find my phone
- Display: night vision red mode (auto 8 pm–6 am), outdoor high-contrast, power saver
- Watch face designer (5 styles, colours, ticks, logo, 4 widget slots) + UltraWatch Luxe watch face
- App lock PIN for messages, markets and settings
- Tiles: Energy, Markets, SOS. Complications: NIFTY 50, Energy

**Phone companion app**
- Sends phone battery to the watch; rings the phone for Find my phone
- Wrist alerts for any brand: market alerts and morning Energy Score as phone notifications that mirror to any watch
- Health Connect: reads steps, heart rate and sleep written by realme Link, Nothing X, Zepp, Huawei Health, Honor Health, Mi Fitness etc., and computes the Energy Score
- Photo watch face export (PNG) for brand apps that support photo faces
- Phone SOS for watches that can't run apps

## Supported watches

| Watch | Support |
|---|---|
| Samsung Galaxy Watch 4 and newer, Google Pixel Watch, OnePlus Watch 2/3, Xiaomi Watch 2 / 2 Pro, Oppo Watch X, Mobvoi TicWatch, Fossil, Montblanc | Full watch app |
| Amazfit (Zepp OS), Huawei (HarmonyOS) | Phone companion features; a native mini-app would be separate code |
| Realme, Nothing/CMF, Honor, Vivo, Coros, Redmi/Mi Band | Phone companion features (wrist alerts, Health Connect, photo face, phone SOS) |

## Build and test

Requirements: Android Studio (Ladybug or newer) or JDK 17 + Android SDK 35.

```bash
# 1. Backtest: run all core logic tests (no device needed)
./gradlew :core:test

# 2. Build the APKs
./gradlew :app:assembleDebug :mobile:assembleDebug
```

APKs: `app/build/outputs/apk/debug/app-debug.apk` (watch) and `mobile/build/outputs/apk/debug/mobile-debug.apk` (phone).

**Or build on GitHub:** push this folder to a GitHub repository. The included workflow (`.github/workflows/build.yml`) runs the tests first and only then builds both APKs; download them from the run's *Artifacts*.

If Android Studio suggests newer library versions, accepting them is fine.

## Try it on an emulator first

1. Android Studio → Device Manager → create a **Wear OS Large Round** emulator (API 33+).
2. Run the `app` configuration. Use *Extended controls → Virtual sensors* to change heart rate, and *Location* to test GPS, Track Back and SOS.
3. Run `mobile` on a phone emulator and pair the two with the *Wear OS pairing assistant* to test phone battery and Find my phone.

## Install on a real watch

1. On the watch: Settings → System → About → tap *Build number* 7 times → Developer options → turn on **ADB debugging** and **Debug over Wi-Fi**.
2. Note the IP:port shown, then:
   ```bash
   adb pair <ip>:<pair-port>        # Wear OS 3+: use the pairing code shown on the watch
   adb connect <ip>:<port>
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```
3. Install `mobile-debug.apk` on your phone the usual way.
4. On first launch, allow the permissions you want to use. For messages, allow *notification access*.

## Configuration (watch → Settings)

- **Quotes URL**: an HTTPS endpoint returning JSON like `[{"symbol":"NIFTY 50","ltp":24412.3,"prevClose":24351.4}]` (also accepts `{"data":[...]}` and Kite-style field names). Broker keys stay on your server, never on the watch.
- **Gemini API key**: from Google AI Studio. The model name is editable.
- **Profile**: age, height, weight, resting heart rate improve every health estimate.

## Honest limits

- No app can add hardware: SpO2/ECG/BP/body composition need a Bluetooth device (or are clearly labelled estimates). Samsung's BioActive sensor data is not available to other apps.
- Live phone-call translation isn't possible on Android (apps can't access call audio); the translator works face to face.
- Satellite SOS needs special hardware; UltraWatch uses data, SMS or the paired phone.
- Watch-face publishing on Google Play now requires Watch Face Format; the included Kotlin watch face is for sideloading.
- Some Bluetooth earbuds report battery only to their own app.
- Background Health Connect reads on Android 15 may need the brand app or UltraWatch to be opened occasionally.
- Health readings are for wellness and screening only. Market data is not investment advice.
