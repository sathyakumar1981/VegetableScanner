# Vegetable Scanner (Android)

Two modes:

- **Teaching mode**: show a vegetable to the camera, type its name, tap "Learn this vegetable".
  Tap again from another angle to improve accuracy.
- **User mode**: show a vegetable to the camera and the app shows the name you taught it.

No training or PC is needed to teach the app. It learns on the phone and remembers
what you taught after you close it. The "Taught list" button lets you delete a vegetable.

## How it works
A small image model (MobileNet V3, via MediaPipe) turns each camera frame into a "fingerprint".
Teaching saves fingerprints with your name for the vegetable. User mode compares the live
fingerprint with the saved ones and shows the closest name.

The model (about 4 MB) is downloaded once on first launch, so the phone needs internet the first
time. After that everything works offline. If you prefer to bundle it, put the file as
`app/src/main/assets/embedder.tflite` and skip the download.

## Getting the APK

### Option A: Android Studio (PC)
1. Open the `VegetableScanner` folder in Android Studio and let Gradle sync.
2. Build > Build APK(s). The file is `app/build/outputs/apk/debug/app-debug.apk`.

### Option B: No PC, build in the cloud with GitHub (free)
1. Create a free GitHub account and a new repository.
2. Upload all files of this project, including the hidden `.github` folder, to the repository.
3. Open the "Actions" tab, run "Build APK" (it also runs on every upload).
4. When it finishes, download `VegetableScanner-debug-apk` from the run page, unzip it,
   and install `app-debug.apk` on your phone (allow "install unknown apps").

## Tuning
- `SIMILARITY_THRESHOLD` in `MainActivity.kt` (default 0.65): raise it if the app names
  the wrong vegetable, lower it if it says "Not sure" too often.
- `SAMPLES_PER_TEACH` (default 6): frames saved per Learn tap.
