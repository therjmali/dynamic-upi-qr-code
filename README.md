# Dynamic UPI QR Code  ·  v4.8

Premium UPI payment QR codes in seconds - for shops, freelancers, and credit card bill collection.
Create a QR with a pre-filled amount and note that works with every UPI app, share it as a polished card, and let payers open their UPI app in one tap.

**Web app:** https://therjmali.github.io/dynamic-upi-qr-code/  
**Android app:** [Download the latest APK](downloads/Dynamic-UPI-QR-Code-latest.apk) - works online and offline

## Highlights
- **UPI QR** and **Credit Card QR** (Axis, ICICI, SBI Card, IDFC FIRST, AU, American Express) with separate themes
- The BHIM mark sits in the centre of every QR (scan-tested), including exports
- Three premium card designs - swipe the card to switch
- Upload or paste an existing QR and every detail is filled in automatically
- Download as JPG or SVG, share the card with the real `upi://pay?...` link, or copy a web pay link
- Payer page that opens Google Pay, PhonePe, Paytm or any UPI app directly
- Quick amount chips, UPI handle recognition, saved QRs, clear / undo
- Private by design: everything runs on your device - no account, no tracking, nothing uploaded

## Deploy (GitHub Pages)
Settings -> Pages -> Deploy from a branch -> `main` / `(root)` -> Save.

## Android app
Install on Android 10 or newer (allow "install unknown apps" for your browser when asked).

- **Share a QR from Gallery:** open any QR image in Gallery / Photos -> Share -> **Dynamic UPI QR / Read UPI QR**. The app reads it and fills in the name, UPI ID, amount and note.
- **Online or offline:** everything is bundled in the app, so it works without internet. When online it also checks for updates.
- **Updates:** when a new version is released, an "Update available" banner appears inside the app. Tap **Update** to download, verify and install it (footer -> *Check for updates* for a manual check).
- Saves the JPG to Gallery, shares to WhatsApp, uploads or pastes a QR, and opens GPay / PhonePe / Paytm directly.

### Releasing a new version
1. Change the app (web files and/or `android/`).
2. From `android/` run `VNAME=<new name> VCODE=<higher build number> NOTES="note one|note two" KS=<keystore> KSPASS=<password> ./build-apk.sh`
   - this signs the APK with the same key and rewrites `downloads/latest.json`.
3. Commit and push. Installed apps read `downloads/latest.json` and offer the upgrade.

(c) 2026 Rana. All rights reserved.
