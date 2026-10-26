# RK Camera AI v1.1 — Android 8.0+

A real Android camera app project (Java + CameraX) for GitHub Actions builds. This is application source code, not a prebuilt APK. Build it on GitHub Actions before installing it on a phone.

## Current implementation

- Real CameraX preview and photo capture.
- Real MP4 video recording through CameraX Recorder.
- Adaptive quality selection: queries the camera's supported qualities and tries HD/SD before larger modes instead of hard-coding HD.
- Photo mode is bound separately from video mode. A video-quality setup failure should not leave the photo preview black.
- Flash/torch control when available, digital 1x/2x zoom, tap-to-focus, front/back switch.
- A restrained natural-tone curve is run on the saved photo in a background executor. The unprocessed photo is kept if processing fails.
- Video preview intentionally remains unstabilized/lightweight. After a recording ends, OpenCV analyzes sparse frames for global camera motion and Media3 Transformer creates a stabilized copy with smoothing, a crop margin, and per-frame corrections. The original video is deleted only after the processed copy was successfully exported and published; if processing fails, the original is retained.
- Custom adaptive launcher icon.
- GitHub Actions workflow outputs `app-debug.apk` as artifact `RK-Camera-AI-APK`.

## Stabilization limitations

The software stabilization is a real post-processing attempt, not a promise of iPhone-equivalent footage. Image-based global motion estimation works best with visible detail, modest motion, and steady lighting; extreme blur, large parallax, rolling shutter, or a featureless scene can defeat it. Post-processing re-encodes the clip and may take longer than the recording, especially on older devices. If analysis or encoding fails, the raw recording is kept. This app does not add optical stabilization and cannot create LiDAR, Apple's ISP, or Apple's Neural Engine on Android.

The photo tone curve is a light generic adjustment, not Apple's proprietary color pipeline. Results must be assessed on the specific phone camera.

## Build APK using GitHub

1. Create a repository at https://github.com/new.
2. Upload all files inside this folder, including the hidden `.github` directory and workflow.
3. Open **Actions** → **Build RK Camera AI APK** → **Run workflow** (or push to `main`/`master`).
4. Open the completed run and download artifact **RK-Camera-AI-APK**.
5. Extract the artifact ZIP and install `app-debug.apk`.

## Install update

Android may reject an update if it has a different signing key from an installed debug APK. Remove the older RK Camera AI build before installing a newly signed debug build. This removes that app's local data but does not delete photos or videos saved in the Gallery.

## Technical stack

- Android minSdk 26 (Android 8 Oreo), compileSdk 35
- Java, CameraX 1.4.2
- Media3 Transformer 1.5.1
- OpenCV 4.10.0
- Gradle 8.7 / Android Gradle Plugin 8.6.1
