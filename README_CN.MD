# BRobotAssistantLib 项目说明

## 项目概述
`BRobotAssistantLib` 是针对PICO设备开发的Android图像处理核心库，提供OpenGL ES图像处理、H.264编解码、网络传输等核心功能。通过aar形式集成到Unity主工程。

## 功能特性
- **OpenGL ES图像处理**：支持FBO离屏渲染和纹理转换
- **H.264硬编码**：支持60FPS低延迟视频编码
- **网络流传输**：TCP实时视频流传输
- **多线程管理**：独立的编码线程和Socket连接池
- **相机控制**：集成PXRCamera SDK实现相机参数控制

## 目录结构

### app/src/main/java/com/picovr/robotassistantlib (TODO：改成包描述)
核心功能实现目录：
- **相机模块**
  - `CameraHandle.java`：相机生命周期管理/编码控制 
- **媒体模块**
  - `MediaDecoder.java`：H.264视频解码器实现
- **网络工具**
  - `ByteBufferUtils.java`：网络包协议封装工具

### fbolibrary
FBO核心实现模块：
- 包含OpenGL ES 3.0渲染管线实现
- 提供Unity纹理与Android SurfaceTexture的绑定接口

### 输出产物
- `robotassistant_lib-release.aar`：主库文件 (开源)
- `fbolibrary-release.aar`：FBO子模块 (非开源)

## 工程配置

### 环境要求
- Android Studio 2022.3.1+
- Gradle 8.0+
- Android SDK 34
- NDK 25.2.9519653
- PXRCamera SDK 0.0.7

### 依赖管理
```groovy
dependencies {
    implementation 'androidx.opengl:opengl:1.0.0'
    compileOnly files('libs/unity-classes.jar')
}

### 注意事项
- NDK版本需与Unity工程保持一致
- 视频编码线程需保持独立线程上下文
- OES纹理绑定需要EGLContext一致性验证
- 最低支持Android 9.0（API 24）

### 生成aar
- 生成主库
- ./gradlew :app:assembleRelease

- 生成fbolibrary
- ./gradlew :fbolibrary:assembleRelease

### 输出路径
app/build/outputs/aar/
└── app-release.aar        # 主库
fbolibrary/build/outputs/aar/
└── fbolibrary-release.aar # FBO子模块

### 版本管理
- 版本号格式：major.minor.buildcode
- major: 架构级改动
- minor: 功能新增
- buildcode: 日期+序列号（220101001)

### 集成说明
- 将aar文件放入Unity工程的Assets/Plugins/Android目录
- 需要Unity 2021.3.15f1及以上版本
- 需要开启Player Settings的Multithreaded Rendering
- 需要申请相机权限和网络权限

### xml
- <uses-permission android:name="android.permission.CAMERA"/>
- <uses-permission android:name="android.permission.INTERNET"/>

### 核心接口说明
- FBOPlugin (Unity交互入口)
  - `init`：初始化OpenGL上下文
  - `getSurface`：获取解码Surface
  - `BuildTexture`：绑定Unity纹理
  - `createFBO`：创建FBO纹理
  - `bindFBO`：绑定FBO纹理到Unity
  - `unbindFBO`：解绑FBO纹理
  - `releaseFBO`：释放FBO纹理
- CameraHandle (相机控制)
  - `startCamera`：启动相机
  - `stopCamera`：停止相机
  - `setCameraParam`：设置相机参数
  - `getCameraParam`：获取相机参数
  - `setCameraFocus`：设置相机对焦
  - `setCameraExposure`：设置相机曝光
  - `setCameraWhiteBalance`：设置相机白平衡
  - `StartPreview`：启动相机预览
  - `StartRecord`：开始本地录制
- MediaDecoder (视频解码)
  - `startDecoder`：启动解码器
  - `stopDecoder`：停止解码器
  - `setDecoderCallback`：设置解码回调
  - `setDecoderParam`：设置解码参数
  - `getDecoderParam`：获取解码参数
  - `getDecoderStatus`：获取解码状态
  - `getDecoderError`：获取解码错误
  - `initialize`：初始化解码器
  - `startDecoding`：启动解码线程
