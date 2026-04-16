# 🕶️ CameraAccess — Meta Ray-Ban Live Camera Streaming

A working Android sample app that streams live video from **Meta Ray-Ban Gen 1** smart glasses to your Android device using the **Meta Wearables Device Access Toolkit (DAT)**.

---

## 📱 What it does

- Connects to Meta Ray-Ban glasses via Bluetooth
- Streams live camera feed from the glasses to your Android screen
- Captures photos directly from the glasses
- Shares captured photos

---

## ⚙️ Requirements

| Component | Version |
|---|---|
| Android Studio | Arctic Fox (2021.3.1) or newer |
| JDK | 11 or newer |
| Android SDK | 31+ (Android 12.0+) |
| Target device | Android 10+ with Bluetooth |
| Glasses | Meta Ray-Ban Gen 1 or Gen 2 |
| Meta AI app | Installed and glasses paired |

---

## 🗺️ Full Setup Guide

### Step 1 — Meta Developer Center

1. Go to [wearables.developer.meta.com](https://wearables.developer.meta.com)
2. Create an account or log in with your Meta account
3. Create a new **Organization**
4. Create a new **Project** and give it a name (e.g. `CameraAccessSample`)
5. Go to **App configuration** → **Android** → **+ Add app details**:
   - **Package name**: `com.meta.wearable.dat.externalsampleapps.cameraaccess`
   - **App signature**: see Step 3 below to get this value
6. Go to **Permissions** → **+ Request permission** → add **Camera** permission with a rationale
7. Go to **Distribute** → **+ New version** → **Create version** (1.0.0)
8. Go to **Release channels** → the Alpha channel is created automatically
9. Click **Edit** on the Alpha channel → **+ Invite users** → add your Meta account email
10. Accept the invite at [wearables.meta.com/invites](https://wearables.meta.com/invites)

---

### Step 2 — GitHub Personal Access Token

The Meta DAT SDK is distributed via GitHub Packages. You need a token to download it.

1. Go to [github.com](https://github.com) → **Settings** → **Developer settings**
2. Click **Personal access tokens** → **Tokens (classic)**
3. Click **Generate new token (classic)**
4. Give it a name (e.g. `MetaDAT`)
5. Set expiration to 90 days
6. Check only ✅ **read:packages**
7. Click **Generate token** and **copy it immediately**

---

### Step 3 — Get the App Signature

The sample uses a custom keystore (`sample.keystore`). Run this in PowerShell:

```powershell
# Add keytool to PATH
$env:PATH += ";C:\Program Files\Android\Android Studio\jbr\bin"

# Get SHA256 fingerprint
keytool -list -v -keystore app\sample.keystore -alias sample -storepass sample -keypass sample
```

Copy the **SHA256** line (format: `XX:XX:XX:...`), then convert it to base64url:

```powershell
# Replace the hex bytes from your SHA256 output
[Convert]::ToBase64String([byte[]](0x56,0x7C,0x39,...)).Replace('+','-').Replace('/','_').TrimEnd('=')
```

> For this repo, the pre-computed base64url signature is:
> ```
> Vnw5tMbqPlKg1kL4_po7CC05UjBt82T8JqLZYk5V924
> ```

Paste this value in the **App signature** field in the Meta Developer Center.

---

### Step 4 — Clone and Configure

```bash
git clone https://github.com/emmasali/CameraAccessSample.git
cd CameraAccessSample
```

Create a `local.properties` file in the project root:

```
sdk.dir=C\:\\Users\\YourName\\AppData\\Local\\Android\\Sdk
github_token=YOUR_GITHUB_TOKEN_HERE
```

Replace `YourName` with your Windows username and `YOUR_GITHUB_TOKEN_HERE` with the token from Step 2.

---

### Step 5 — Add Meta Credentials

Open `app/src/main/AndroidManifest.xml` and update with your credentials from the Meta Developer Center:

```xml
<meta-data
    android:name="com.meta.wearable.mwdat.APPLICATION_ID"
    android:value="YOUR_APPLICATION_ID" />
<meta-data
    android:name="com.meta.wearable.mwdat.CLIENT_TOKEN"
    android:value="YOUR_CLIENT_TOKEN" />
```

Find these values in the Meta Developer Center under **App configuration** → **Android integration**.

---

### Step 6 — Build and Run

1. Open the project in **Android Studio**
2. Click **File → Sync Project with Gradle Files**
3. Connect your Android device via USB with **Developer Mode** and **USB Debugging** enabled
4. Click **▶ Run** → select your device

---

### Step 7 — Pair the Glasses

1. Make sure **Meta View** app is installed on your Android device
2. Pair your Ray-Ban glasses via Meta View
3. Accept the camera permission request when prompted by the app
4. The live stream should appear on screen

---

## 🔧 Troubleshooting

### "Application package signature is not matching"
- The app signature in Meta Developer Center doesn't match your keystore
- Re-extract the SHA256 from `app/sample.keystore` (see Step 3)
- Update the signature in Meta Developer Center
- **Clear Meta View cache**: Settings → Apps → Meta View → Clear Cache → Force Stop
- Create a new version in Distribute and assign it to Alpha channel
- Re-run the app

### "Token not configured" / Registration fails
- Check that `APPLICATION_ID` and `CLIENT_TOKEN` are correctly set in `AndroidManifest.xml`
- Make sure your email is listed as an Active User in the Alpha release channel

### Gradle sync fails with 401 Unauthorized
- Your GitHub token may be incorrect or expired
- Check `local.properties` — make sure there's only ONE `github_token=` line
- Generate a new token with `read:packages` scope

---

## 📁 Project Structure

```
CameraAccess/
├── app/
│   ├── src/main/
│   │   ├── java/com/meta/wearable/dat/externalsampleapps/cameraaccess/
│   │   │   ├── MainActivity.kt
│   │   │   ├── HomeScreen.kt
│   │   │   ├── StreamScreen.kt
│   │   │   └── ...
│   │   └── AndroidManifest.xml
│   ├── sample.keystore          ← Custom keystore for this sample
│   └── build.gradle.kts
├── local.properties             ← NOT committed (add github_token here)
└── README.md
```

---

## 🔑 Credentials Summary

| Item | Where to find |
|---|---|
| APPLICATION_ID | Meta Developer Center → App configuration → Android integration |
| CLIENT_TOKEN | Meta Developer Center → App configuration → Android integration |
| App Signature | Extracted from `sample.keystore` (base64url SHA256) |
| GitHub Token | github.com → Settings → Developer settings → Personal access tokens |

---

## 👩‍💻 Author

**Imane** — Built with ❤️ in Montréal

---

## 📄 License

This project is based on the Meta Wearables DAT Android sample.
See [LICENSE](https://github.com/facebook/meta-wearables-dat-android/blob/main/LICENSE) for details.
