package com.picovr.robotassistantlib;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;

import com.pxr.capturelib.PXRCamera;
import com.pxr.capturelib.PXRCameraCallBack;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;

public class CameraHandleUDP {
    private final String TAG = "CameraHandleUDPe";
    private UseState useState = UseState.IDLE;
    private int mEnableMvHevc = 0;
    private int mVideoFps = 60;
    private int mBitrate = 768;
    private PXRCamera mPXRCamera = new PXRCamera();
    private int mCaptureState = 0;
    private int previewWidth;
    private int previewHeight;
    private int renderMode;
    private Thread encodeThread;
    private MediaCodec mediaEncode;
    private DatagramSocket udpSocket;
    private InetAddress targetAddress;
    private Surface surface;
    private String targetIp = "192.168.31.228"; // Target IP
    private int targetPort = 12345; // target port
    private MediaMuxer mediaMuxer;
    private int trackIndex;
    private boolean isMuxerStarted = false;
    private volatile boolean shouldStopEncoding = false; // Flag to signal encoding thread to stop

    public int GetCaptureState() {
        return mCaptureState;
    }

    public int OpenCamera(PXRCameraCallBack call) {

        Log.d(TAG, "OpenCamera - current mCaptureState: " + mCaptureState);

        if (mCaptureState > PXRCamera.PXRCaptureState.CAPTURE_STATE_IDLE.ordinal()) {
            Log.w(TAG, "Camera already open, mCaptureState: " + mCaptureState);
            return 0;
        }

        // mIsPreview = false;
        // Map<String, String> cameraParams = new HashMap<>();
        // cameraParams.put(PXRCamera.CAMERA_PARAMS_KEY_MCTF, PXRCamera.VALUE_TRUE);
        // cameraParams.put(PXRCamera.CAMERA_PARAMS_KEY_EIS, PXRCamera.VALUE_TRUE);
        // cameraParams.put(PXRCamera.CAMERA_PARAMS_KEY_MFNR, PXRCamera.VALUE_TRUE);
        try {
            int openRes = mPXRCamera.openCameraAsync();
            if (openRes != 0) {
                Log.e(TAG, "openCameraAsync failed with result: " + openRes);
            } else {
                Log.d(TAG, "openCameraAsync succeeded");
            }
        } catch (Exception ex) {
            Log.e(TAG, "Exception in openCameraAsync: " + ex.toString());
        }

        return mPXRCamera.setCallback(call);
    }

    public static String GetMovieSavePath() {
        return "/sdcard/Download/";
    }

    private final Object mediaEncodeLock = new Object(); // 线程锁

    public int StartPreview(int width, int height, int mode) {
        previewWidth = width;
        previewHeight = height;

        renderMode = mode;
        Log.i(TAG, "StartPreview " + " width:" + width + " height:" + height);

        useState = UseState.Preview;
        shouldStopEncoding = false; // Reset the stop flag

        try {

            Log.i(TAG, "StartPreview  mediaCodec int");
            // Create MediaCodex instance for H.264 encoding
            mediaEncode = MediaCodec.createEncoderByType("video/avc");
            MediaFormat format = MediaFormat.createVideoFormat("video/avc", width, height);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 5 * 1024 * 1024);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 60);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            // Set low latency mode
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            mediaEncode.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            Log.i(TAG, "StartPreview  setConfigure");
            surface = mediaEncode.createInputSurface();
            MediaCodecInfo.CodecCapabilities capabilities = mediaEncode.getCodecInfo()
                    .getCapabilitiesForType("video/avc");
            for (int colorFormat : capabilities.colorFormats) {
                Log.i(TAG, "Supported color format: " + colorFormat);
            }

            Log.i(TAG, "StartPreview  mediaCodec start");
            mediaEncode.start();
            Log.i(TAG, "startPreview");
            mPXRCamera.startPreview(surface, renderMode, previewWidth, previewHeight);

        } catch (IOException | RuntimeException e) {
            e.printStackTrace();
            cleanup();
        }

        return 0;
    }

    public int startSendingImages(String ip, int port) {
        if (useState != UseState.Preview) {
            Log.e(TAG, "Cannot start sending images - preview not started");
            return -1;
        }
        return StartSendImage(ip, port);
    }

    private int StartSendImage(String ip, int port) {
        targetIp = ip;
        targetPort = port;
        Log.i(TAG, "StartSendImage " + " ip:" + ip + " port:" + port);
        encodeThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Log.i(TAG, "StartPreview UDP socket start " + targetIp + " " + targetPort);
                    // Create UDP socket without binding to a specific local port
                    // This allows the system to choose an available ephemeral port
                    udpSocket = new DatagramSocket();
                    // Enable socket reuse to prevent "Address already in use" errors
                    udpSocket.setReuseAddress(true);
                    targetAddress = InetAddress.getByName(targetIp);
                    EncodeStreaming(true);
                    Log.i(TAG, "startStreaming");
                } catch (IOException e) {
                    Log.e(TAG, "UDP socket creation failed", e);

                    // Properly handle cleanup on failure
                    useState = UseState.IDLE;
                    shouldStopEncoding = true;
                    try {
                        mPXRCamera.stopPreview();
                        synchronized (mediaEncodeLock) {
                            if (mediaEncode != null) {
                                mediaEncode.stop();
                            }
                        }
                    } catch (Exception cleanupException) {
                        Log.e(TAG, "Error during cleanup after UDP socket failure", cleanupException);
                    }
                    cleanup();
                }
            }
        });
        encodeThread.start();

        return 0;
    }

    private int StartRecord(String savePath) throws IOException {

        Log.i(TAG, "startRecord filePath: " + savePath);
        mediaMuxer = new MediaMuxer(savePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        trackIndex = -1;
        encodeThread = new Thread(new Runnable() {
            @Override
            public void run() {
                EncodeStreaming(false);
            }
        });
        encodeThread.start();

        return 0;
    }

    public void EncodeStreaming(boolean udpSend) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        Log.i(TAG, "startStreaming udpSend:" + udpSend + " useState:" + useState);
        while (useState == UseState.Preview && !Thread.currentThread().isInterrupted() && !shouldStopEncoding) {
            try {
                int outputBufferIndex;
                synchronized (mediaEncodeLock) {
                    if (mediaEncode == null || useState != UseState.Preview) {
                        Log.i(TAG, "MediaCodec is null or state changed, breaking encode loop");
                        break; // 避免 NullPointerException 和状态不一致
                    }
                    try {
                        outputBufferIndex = mediaEncode.dequeueOutputBuffer(info, 10000);
                    } catch (IllegalStateException e) {
                        Log.e(TAG, "MediaCodec dequeueOutputBuffer failed - codec may be stopped", e);
                        break; // Exit the loop if MediaCodec is in invalid state
                    }
                }
                if (outputBufferIndex >= 0) {
                    ByteBuffer encodedData;
                    // Obtain encoded data
                    synchronized (mediaEncodeLock) {
                        if (mediaEncode == null || useState != UseState.Preview) {
                            Log.i(TAG, "MediaCodec is null or state changed during buffer processing");
                            break;
                        }
                        try {
                            encodedData = mediaEncode.getOutputBuffer(outputBufferIndex);
                        } catch (IllegalStateException e) {
                            Log.e(TAG, "MediaCodec getOutputBuffer failed - codec may be stopped", e);
                            break;
                        }
                    }
                    if (udpSend) {
                        int bodyLength = encodedData.remaining();
                        byte[] data = new byte[bodyLength];
                        encodedData.get(data);

                        int length = data.length;

                        ByteBuffer buffer = ByteBuffer.allocate(4 + length);
                        buffer.order(ByteOrder.BIG_ENDIAN); // Set byte order (big endian)
                        buffer.putInt(length); // Write length header

                        buffer.put(data);
                        byte[] packet = buffer.array();
                        // Send UDP packet using same format as TCP
                        if (udpSocket != null && !udpSocket.isClosed() && targetAddress != null) {
                            // Check UDP packet size limitation (typical max is 65507 bytes)
                            final int MAX_UDP_PACKET_SIZE = 65507;
                            if (packet.length <= MAX_UDP_PACKET_SIZE) {
                                DatagramPacket udpPacket = new DatagramPacket(packet, packet.length, targetAddress,
                                        targetPort);
                                udpSocket.send(udpPacket);
                            } else {
                                Log.w(TAG, "Packet size " + packet.length + " exceeds UDP limit of "
                                        + MAX_UDP_PACKET_SIZE + " bytes, dropping packet");
                            }
                        } else {
                            Log.i(TAG, "UDP socket not available, trying to recreate...");
                            recreateUdpSocket();
                        }
                    } else {
                        if (encodedData != null && isMuxerStarted) {
                            // Log.i(TAG, "info.presentationTimeUs:" + info.presentationTimeUs);
                            encodedData = addSEI(encodedData, info.presentationTimeUs);// 微秒
                            mediaMuxer.writeSampleData(trackIndex, encodedData, info);
                        }
                    }
                    synchronized (mediaEncodeLock) {
                        if (mediaEncode == null || useState != UseState.Preview) {
                            Log.i(TAG, "MediaCodec is null or state changed during buffer release");
                            break;
                        }
                        try {
                            mediaEncode.releaseOutputBuffer(outputBufferIndex, false);
                        } catch (IllegalStateException e) {
                            Log.e(TAG, "MediaCodec releaseOutputBuffer failed - codec may be stopped", e);
                            break;
                        }
                    }
                } else if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // There is no output buffer, you may need to try again later
                    continue;
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // Output format changes (such as when the encoder is reinitialized)
                    if (!udpSend) {
                        synchronized (mediaEncodeLock) {
                            if (mediaEncode == null || useState != UseState.Preview) {
                                Log.i(TAG, "MediaCodec is null or state changed during format change");
                                break;
                            }
                            try {
                                MediaFormat newFormat = mediaEncode.getOutputFormat();
                                ByteBuffer bf1 = newFormat.getByteBuffer("csd-1");
                                ByteBuffer bf2 = newFormat.getByteBuffer("csd-0");
                                Log.i(TAG, "The output format has been changed:" + newFormat);

                                trackIndex = mediaMuxer.addTrack(mediaEncode.getOutputFormat());
                                mediaMuxer.start();
                                isMuxerStarted = true;
                            } catch (IllegalStateException e) {
                                Log.e(TAG, "MediaCodec getOutputFormat failed - codec may be stopped", e);
                                break;
                            }
                        }
                    }
                }

            } catch (IOException e) {
                e.printStackTrace();
                recreateUdpSocket(); // Attempt to recreate UDP socket when an exception occurs
            }
        }
        Log.i(TAG, "EncodeStreaming loop ended - useState: " + useState + ", shouldStopEncoding: " + shouldStopEncoding
                + ", interrupted: " + Thread.currentThread().isInterrupted());
    }

    // 在每帧中插入时间戳，此时间戳与操作录制文件中“predictTime”字段对应.
    private ByteBuffer addSEI(ByteBuffer encodedData, long timestamp) {
        byte[] seiHeader = new byte[] { 0x00, 0x00, 0x00, 0x01, 0x06 }; // SEI NALU 头部
        byte seiPayloadType = 0x05; // SEI 类型 5（User Data Unregistered）

        // 时间戳转换为字节数组
        byte[] timestampBytes = ByteBuffer.allocate(8).putLong(timestamp).array();

        // 计算 payload size（时间戳长度 + 1 字节 RBSP 结束位）
        byte seiPayloadSize = (byte) (timestampBytes.length + 1);

        // 构造完整的 SEI payload
        ByteBuffer seiBuffer = ByteBuffer.allocate(seiHeader.length + 2 + seiPayloadSize);
        seiBuffer.put(seiHeader);
        seiBuffer.put(seiPayloadType); // SEI 类型
        seiBuffer.put(seiPayloadSize); // SEI 负载长度
        seiBuffer.put(timestampBytes); // 时间戳数据
        seiBuffer.put((byte) 0x80); // RBSP Stop Bit，防止截断

        // 组合 SEI 数据和原始数据
        ByteBuffer outputBuffer = ByteBuffer.allocate(seiBuffer.remaining() + encodedData.remaining());
        outputBuffer.put(seiBuffer);
        outputBuffer.put(encodedData);
        outputBuffer.flip();

        return outputBuffer;
    }

    public int StopPreview() {
        Log.i(TAG, "StopPreview starting...");

        // First, set flags to signal encoding thread to stop
        useState = UseState.IDLE;
        shouldStopEncoding = true;

        // Stop camera preview first
        int res = mPXRCamera.stopPreview();
        Log.i(TAG, "Camera preview stopped");

        // Wait for encode thread to finish before stopping MediaCodec
        if (encodeThread != null && encodeThread.isAlive()) {
            encodeThread.interrupt(); // Interrupt the thread if it's blocking on I/O
            try {
                encodeThread.join(3000); // Wait for the thread to die for up to 3 seconds
                if (encodeThread.isAlive()) {
                    Log.w(TAG, "Encode thread did not terminate in time. Forcing shutdown...");
                } else {
                    Log.i(TAG, "Encode thread stopped successfully.");
                }
            } catch (InterruptedException e) {
                Log.e(TAG, "Interrupted while waiting for encode thread to stop.", e);
            }
            encodeThread = null;
        }

        // Now safely stop MediaCodec
        synchronized (mediaEncodeLock) {
            if (mediaEncode != null) {
                try {
                    Log.i(TAG, "Stopping MediaCodec...");
                    mediaEncode.stop();
                    Log.i(TAG, "MediaCodec stopped successfully");
                } catch (IllegalStateException e) {
                    Log.e(TAG, "MediaCodec stop failed", e);
                }
                mediaEncode.release();
                mediaEncode = null;
                Log.i(TAG, "MediaCodec released");
            }
        }

        if (mediaMuxer != null) {
            try {
                if (isMuxerStarted) {
                    mediaMuxer.stop();
                }
            } catch (IllegalStateException e) {
                Log.e(TAG, "MediaMuxer stop failed", e);
            }
            mediaMuxer.release();
            mediaMuxer = null;
        }

        // Clean up surface
        if (surface != null) {
            surface.release();
            surface = null;
        }

        // Clean up socket connection
        cleanupUdpSocket();

        // Reset state variables
        isMuxerStarted = false;
        trackIndex = -1;
        shouldStopEncoding = false; // Reset for next streaming session

        // IMPORTANT: Reset camera completely to allow reopening
        try {
            Log.i(TAG, "Resetting camera to ensure clean state...");
            mPXRCamera.reset();
            // Don't set mCaptureState manually - let the camera reset it properly
            Log.i(TAG, "Camera reset completed");
        } catch (Exception e) {
            Log.e(TAG, "Error during camera reset", e);
            // Force reset the state if camera reset fails
            mCaptureState = PXRCamera.PXRCaptureState.CAPTURE_STATE_IDLE.ordinal();
        }

        Log.i(TAG, "StopPreview completed, camera state should be reset");

        return res;
    }

    public void SetConfig(int enableMvHevc, int videoFps) {
        mEnableMvHevc = enableMvHevc;
        mVideoFps = videoFps;
        // mBitrate = bitrate;

        Log.i(TAG, "SetConfig: " + mEnableMvHevc + " " + mVideoFps + " " + mBitrate);
        Log.i(TAG, "SetConfig mCaptureState: " + mCaptureState);

        // Map<String, String> configureParams = new HashMap<>();
        // configureParams.put(PXRCapture.CONFIGURE_PARAMS_KEY_ENABLE_MVHEVC,
        // String.valueOf(enableMvHevc));// 0
        // configureParams.put(PXRCapture.CONFIGURE_PARAMS_KEY_VIDEO_FPS,
        // String.valueOf(videoFps));//60
        // configureParams.put(PXRCapture.CONFIGURE_PARAMS_KEY_VIDEO_WIDTH,
        // String.valueOf(videoWidth));//2048
        // configureParams.put(PXRCapture.CONFIGURE_PARAMS_KEY_VIDEO_HEIGHT,
        // String.valueOf(videoHeight));//1536 / 2
        // configureParams.put(PXRCapture.CONFIGURE_PARAMS_KEY_VIDEO_BITRATE,
        // String.valueOf(bitrate));//10 * 1024 * 1024 默认40M
        mPXRCamera.enableEIS(true);
        mPXRCamera.configure(enableMvHevc == 1, videoFps);
    }

    public String getCameraExtrinsics() {
        double[] leftCameraExtrinsics = new double[16];
        int[] leftCameraExtrinsicsLen = new int[1];
        double[] rightCameraExtrinsics = new double[16];
        int[] rightCameraExtrinsicsLen = new int[1];
        mPXRCamera.getCameraExtrinsics(leftCameraExtrinsics, leftCameraExtrinsicsLen,
                rightCameraExtrinsics, rightCameraExtrinsicsLen);

        return Arrays.toString(leftCameraExtrinsics) + "|" + Arrays.toString(rightCameraExtrinsics);
    }

    public String getCameraIntrinsics(int width, int height) {
        double[] cameraIntrinsics = new double[4];
        int[] len = new int[1];
        /**
         * Output Width: The width of the video that the business needs to output
         * Output Height: The height of the video that the business needs to output
         * OutputFovHorizontal: The horizontal FOV of the video that the business needs
         * to output Default 76.35f
         * OutputFovVertical: The vertical FOV of the video that the business needs to
         * output Default 61.05f
         * Camera Intrinsics: Distortion Parameters
         * Len: Distortion parameter length
         * Return: Whether the execution was successful. 1 success 0 failure
         */
        mPXRCamera.getCameraIntrinsics(width,
                height,
                76.35f,
                61.05f,
                cameraIntrinsics,
                len);

        // Log.i(tag, "cameraIntrinsics len: " + len[0] + " value: " +
        // Arrays.toString(cameraIntrinsics));
        return Arrays.toString(cameraIntrinsics);
    }

    /**
     * Reset all state variables to initial state for multiple streaming sessions
     */
    private void resetState() {
        useState = UseState.IDLE;
        shouldStopEncoding = false;
        mCaptureState = PXRCamera.PXRCaptureState.CAPTURE_STATE_IDLE.ordinal();
        encodeThread = null;
        mediaEncode = null;
        udpSocket = null;
        targetAddress = null;
        surface = null;
        mediaMuxer = null;
        trackIndex = -1;
        isMuxerStarted = false;
        targetPort = 0; // Reset to indicate no active connection
        Log.i(TAG, "State reset completed");
    }

    public int CloseCamera() {
        Log.i(TAG, "CloseCamera starting...");

        // Stop any ongoing preview first
        if (useState == UseState.Preview) {
            StopPreview();
        }

        int result = 0;
        try {
            // Close the camera properly
            result = mPXRCamera.closeCamera();
            Log.i(TAG, "Camera closed with result: " + result);

            // Reset camera to ensure clean state
            mPXRCamera.reset();
            Log.i(TAG, "Camera reset completed");
        } catch (Exception e) {
            Log.e(TAG, "Error during camera close/reset", e);
            result = -1;
        }

        // Clean up all resources
        resetState();
        cleanup();

        Log.i(TAG, "CloseCamera completed");
        return result;
    }

    public enum UseState {
        IDLE, Preview, Record
    }

    private void recreateUdpSocket() {
        if (useState != UseState.Preview) {
            return;
        }
        // Try recreating the UDP socket
        try {
            synchronized (mediaEncodeLock) {
                // Clean up existing socket
                if (udpSocket != null && !udpSocket.isClosed()) {
                    udpSocket.close();
                }

                // Create new UDP socket with better error handling
                udpSocket = new DatagramSocket();
                // Enable socket reuse to prevent "Address already in use" errors
                udpSocket.setReuseAddress(true);
                if (targetIp != null) {
                    targetAddress = InetAddress.getByName(targetIp);
                }
                Log.i(TAG, "UDP socket recreated successfully!");
            }
        } catch (IOException e) {
            Log.e(TAG, "UDP socket recreation failed, please check your network connection", e);
        }
    }

    private void cleanupUdpSocket() {
        try {
            if (udpSocket != null) {
                if (!udpSocket.isClosed()) {
                    udpSocket.close();
                }
                udpSocket = null;
            }
            targetAddress = null;
            Log.i(TAG, "UDP socket cleanup completed");
        } catch (Exception e) {
            Log.e(TAG, "Error during UDP socket cleanup", e);
        }
    }

    private void cleanup() {
        try {
            synchronized (mediaEncodeLock) {
                if (mediaEncode != null) {
                    try {
                        mediaEncode.stop();
                    } catch (IllegalStateException e) {
                        Log.e(TAG, "MediaCodec stop failed during cleanup", e);
                    }
                    mediaEncode.release();
                    mediaEncode = null;
                }
            }

            cleanupUdpSocket();

            if (surface != null) {
                surface.release();
                surface = null;
            }

        } catch (Exception e) {
            Log.e(TAG, "Error during cleanup", e);
        }
    }
}
