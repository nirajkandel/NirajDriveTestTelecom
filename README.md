# Niraj Drive Test

Android drive-test and cell-monitor app (like Net Monitor): live serving/neighbour cell info, GPS route on a map coloured by signal, live graphs, and Start/Stop drive-test logging with CSV export for Excel.

- Package: `com.niraj.drivetest` · minSdk 26 (Android 8.0) · targetSdk 36 (Android 16)
- Kotlin, no Google Play Services, no Maps API key (OpenStreetMap via osmdroid)

## Features
- **CELL** – 2G/3G/4G/5G: operator, MCC-MNC, TAC/LAC, ECI/NCI/CID, eNB/gNB ID, sector, PCI/PSC/BSIC, (E)ARFCN, band, bandwidth, RSRP/RSCP, RSRQ/EcNo, SINR, RSSI, CQI, TA, 5G NSA (EN-DC) SS-RSRP/SINR, neighbour list, GPS.
- **MAP** – route dots coloured by RSRP, live position, follow mode.
- **GRAPHS** – RSRP, RSRQ, SINR for the last 5 minutes with quality bands; dashed lines mark cell changes (handovers).
- **LOGS** – every drive test saved as `NDT_yyyyMMdd_HHmmss.csv` (1 sample/second). Share or save to phone; opens in Excel.
- Dual-SIM: tap the SIM chip (top-right) to choose which SIM to measure.
- Keeps recording with the screen off (foreground service + notification with a Stop button).

## Build the APK

### Option A — Android Studio (easiest)
1. Install Android Studio (Ladybug or newer).
2. File → Open → select this `NirajDriveTest` folder. Let Gradle sync.
3. Build → Build App Bundle(s) / APK(s) → **Build APK(s)**.
4. APK: `app/build/outputs/apk/debug/app-debug.apk` — copy to the phone and install.

### Option B — GitHub (no install needed)
1. Create a new GitHub repository and upload this folder.
2. Open the **Actions** tab → "Build APK" runs automatically.
3. When it finishes, download **NirajDriveTest-debug-apk** from the run page.

## Publish on Google Play
Play Store needs a **signed AAB** (not an APK).

1. Create an upload key once (keep it safe — you need it for every update):
   ```
   keytool -genkeypair -v -keystore niraj-upload.jks -alias niraj -keyalg RSA -keysize 2048 -validity 10000
   ```
2. Either
   - Android Studio: Build → Generate Signed App Bundle / APK → Android App Bundle, choose the key; or
   - create `keystore.properties` in the project root:
     ```
     storeFile=niraj-upload.jks
     storePassword=YOUR_PASSWORD
     keyAlias=niraj
     keyPassword=YOUR_PASSWORD
     ```
     then run `./gradlew bundleRelease` → `app/build/outputs/bundle/release/app-release.aab`; or
   - GitHub: add secrets `KEYSTORE_BASE64` (`base64 -w0 niraj-upload.jks`), `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`; the workflow then outputs a signed AAB.
3. In Play Console:
   - Host `PRIVACY_POLICY.md` somewhere public (Google Sites, GitHub Pages) and paste the URL.
   - **Data safety**: Location (precise) — collected, not shared, processed on device, used for app functionality. No other data collected.
   - **Foreground service declaration** (location): explain "Records network measurements with GPS position during a user-started drive test; a notification with Stop is shown."
   - Location is only collected while the app or its visible drive-test notification is active, so `ACCESS_BACKGROUND_LOCATION` is **not** requested.
   - New personal developer accounts must run a closed test with at least 12 testers for 14 days before production.
4. Bump `versionCode` in `app/build.gradle.kts` for each upload.

## CSV columns
Date, Time, Latitude, Longitude, GPS_Accuracy_m, Speed_kmh, Altitude_m, Bearing_deg, SIM, Operator, Network_Type, RAT, MCC, MNC, TAC_LAC, Cell_ID_ECI_NCI_CID, eNB_gNB_RNC_ID, Sector_LCID, PCI_PSC_BSIC, ARFCN, Band, BW_MHz, RSRP_RSCP_dBm, RSRQ_EcNo_dB, SINR_dB, RSSI_dBm, CQI, TA, Level, NR_NSA_SS_RSRP, NR_NSA_SS_RSRQ, NR_NSA_SS_SINR, Neighbor_Count, Neighbors

## Notes
- Which fields appear depends on the phone's modem/firmware: some phones don't report SINR, CQI or bandwidth, or neighbours.
- gNB ID assumes a 24-bit gNB length (NCI >> 12).
- Map tiles come from openstreetmap.org (fine for normal use; for heavy fleet use switch to a commercial tile provider).
