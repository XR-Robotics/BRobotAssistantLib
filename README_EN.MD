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
- **FBO Module**
  - `FBOUtils.java`: OpenGL ES utilities (shader compilation/FBO management)
  - `FBOPlugin.java`: Bridge between Unity textures and OES textures
  - `FBOTexture.java`: FBO texture rendering pipeline
- **Camera Module**
  - `CameraHandle.java`: Camera lifecycle management and encoding control
- **Media Module**
  - `MediaDecoder.java`: H.264 video decoder implementation
- **Network Tools**
  - `ByteBufferUtils.java**: Network packet protocol utilities

### `fbolibrary`
FBO core implementation module:
- OpenGL ES 3.0 rendering pipeline
- Provides binding interfaces between Unity textures and Android SurfaceTexture

## Build Artifacts
- `robotassistant_lib-release.aar`: Main library
- `fbolibrary-release.aar`: FBO submodule

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
  ./gradlew :app:assembleRelease
  ```
- **Build FBO submodule**:
  ```bash
  ./gradlew :fbolibrary:assembleRelease
  ```

### Output Paths
```
app/build/outputs/aar/
└── app-release.aar        # Main library
fbolibrary/build/outputs/aar/
└── fbolibrary-release.aar # FBO submodule
```

## Versioning
- Format: `major.minor.buildcode`
  - `major`: Architectural changes
  - `minor`: Feature additions
  - `buildcode`: Date + serial number (e.g., 220101001)

## Integration Guide
1. Place AAR files in Unity project's `Assets/Plugins/Android` directory
2. Requires Unity 2021.3.15f1 or later
3. Enable `Multithreaded Rendering` in Player Settings
4. Required permissions:
   ```xml
   <uses-permission android:name="android.permission.CAMERA"/>
   <uses-permission android:name="android.permission.INTERNET"/>
   ```

## Core API Reference
### `FBOPlugin` (Unity Interface)
- `init`: Initialize OpenGL context
- `getSurface`: Get decoding Surface
- `BuildTexture`: Bind Unity texture
- `createFBO`: Create FBO texture
- `bindFBO`: Bind FBO to Unity
- `unbindFBO`: Unbind FBO
- `releaseFBO`: Release FBO resources[README（EN）.MD](../BRobotAssistantU3d/README%EF%BC%88EN%EF%BC%89.MD)

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