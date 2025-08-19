# BRobotAssistantLib Project Documentation

## Project Overview
`BRobotAssistantLib` is a core Android image processing library developed for PICO devices, providing key functionalities including OpenGL ES image processing, H.264 encoding/decoding, and network transmission. It is integrated into the main Unity project via an AAR package.

## Features
- **OpenGL ES Image Processing**: Supports FBO off-screen rendering and texture conversion
- **H.264 Hardware Encoding**: Enables 60 FPS low-latency video encoding
- **Network Streaming**: Real-time TCP video stream transmission
- **Multithread Management**: Dedicated encoding thread and socket connection pool
- **Camera Control**: Integrates PXRCamera SDK for camera parameter management

## Directory Structure

### `app/src/main/java/com/picovr/robotassistantlib`
Core implementation directory:
- **Camera Module**
  - `CameraHandle.java`: Camera lifecycle management and encoding control
- **Media Module**
  - `MediaDecoder.java`: H.264 video decoder implementation
- **Network Tools**
  - `ByteBufferUtils.java`: Network packet protocol utilities

## Build Artifacts
- `robotassistant_lib.aar`: Main library
> Renamed from `robotassistant_lib-i18n-release.aar`

## Project Configuration

### Requirements
- Android Studio 2022.3.1+
- Gradle 8.0+
- Android SDK 34
- NDK 25.2.9519653
- PXRCamera SDK 0.0.7

### Dependency Management
```groovy
dependencies {
    implementation 'androidx.opengl:opengl:1.0.0'
    compileOnly files('libs/unity-classes.jar')
}
```

### Notes
- NDK version must match Unity project configuration
- Video encoding requires dedicated thread context
- OES texture binding requires EGLContext consistency verification
- Minimum supported Android version: 9.0 (API 24)

## Build Commands
- **Build main library**:
  ```bash
  ./gradlew :localmaven:publish
  ./gradlew :app:assembleRelease
  ```

### Output Paths
```
app/build/outputs/aar/
└── robotassistant_lib-i18n-release.aar        # Main library
└── robotassistant_lib-cn-release.aar          # Main library
```

## Versioning
- Format: `major.minor.buildcode`
  - `major`: Architectural changes
  - `minor`: Feature additions
  - `buildcode`: Date + serial number (e.g., 220101001)

## Integration Guide
1. Rename `robotassistant_lib-i18n-release.aar` to `robotassistant_lib.aar`
2. Place `robotassistant_lib.aar` into Unity project's `Assets/Plugins/Android` directory
2. Requires Unity 2021.3.15f1 or later
3. Enable `Multithreaded Rendering` in Player Settings
4. Required permissions:
   ```xml
   <uses-permission android:name="android.permission.CAMERA"/>
   <uses-permission android:name="android.permission.INTERNET"/>
   ```

## Core API Reference

### `CameraHandle` (Camera Control)
- `startCamera`: Start camera
- `stopCamera`: Stop camera
- `setCameraParam`: Configure camera parameters
- `getCameraParam`: Retrieve camera parameters
- `setCameraFocus`: Adjust focus
- `setCameraExposure`: Set exposure
- `setCameraWhiteBalance`: Configure WB
- `StartPreview`: Enable preview
- `StartRecord`: Begin local recording

### `MediaDecoder` (Video Decoding)
- `startDecoder`: Initialize decoder
- `stopDecoder`: Terminate decoder
- `setDecoderCallback`: Set frame callback
- `setDecoderParam`: Configure decoder
- `getDecoderParam`: Get decoder config
- `getDecoderStatus`: Check decoder state
- `getDecoderError`: Retrieve error codes
- `initialize`: Setup decoder
- `startDecoding`: Launch decoding thread

## Citation

If you find this project useful, please consider citing it as follows.

```
@article{zhao2025xrobotoolkit,
      title={XRoboToolkit: A Cross-Platform Framework for Robot Teleoperation}, 
      author={Zhigen Zhao and Liuchuan Yu and Ke Jing and Ning Yang}, 
      journal={arXiv preprint arXiv:2508.00097},
      year={2025}
}
```
