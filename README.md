# Aham Vision

Offline real-time object detection and video recording for Android. CameraX keeps preview, frame analysis, and MP4 recording active at the same time; a YOLO26n LiteRT model is bundled in the APK and detects all 80 COCO classes, including people, birds, cats, dogs, livestock, wildlife, vehicles, and common objects.

## Run

1. Open this directory in Android Studio.
2. Let Gradle sync, connect an Android 8+ physical device, and run `app`.
3. Grant camera access (and microphone access if recordings should include audio).
4. Tap **Record** to start or stop an MP4. Recordings are stored in the app's external `Movies` directory and remain fully local.

No network permission is declared, so inference cannot send frames off-device.

## Google Play releases

The production package ID is `com.sri.ahamvision` and the Play listing name is **aham-vision**. Release versions are supplied through `VERSION_CODE` and `VERSION_NAME`; signing credentials come from an ignored `keystore.properties` file locally or GitHub Actions secrets in CI.

Pushing a semantic version tag such as `v1.0.0` runs the same release pattern as the Aham Mobile app: GitHub Actions builds a signed AAB and Fastlane uploads a draft to the Play internal-testing track. See [docs/PLAY_STORE_RELEASE.md](docs/PLAY_STORE_RELEASE.md) for setup and launch requirements.

## Model

The bundled asset is `app/src/main/assets/yolo26n_w8a32.tflite`, Ultralytics' official nano detection release with 640×640 input and the standard 80 COCO labels. The Kotlin decoder supports both NCHW/NHWC inputs and `[1,84,N]`/`[1,N,84]` outputs. If replacing it, keep the filename or update `YoloDetector.kt`, and use a non-NMS detection export.

Ultralytics models are distributed under AGPL-3.0 unless you obtain an Enterprise license. Review licensing before distributing a closed-source app.
