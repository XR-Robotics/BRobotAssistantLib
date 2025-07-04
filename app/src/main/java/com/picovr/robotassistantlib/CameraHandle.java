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
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;

public class CameraHandle {
    private final String TAG = "CameraHandle";
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
    private Socket socket;
    private OutputStream outputStream;
    private Surface surface;
    private String targetIp = "192.168.31.228"; // Target IP
    private int targetPort = 12345; // target port
    private MediaMuxer mediaMuxer;
    private int trackIndex;
    private boolean isMuxerStarted = false;

    public int GetCaptureState() {
        return mCaptureState;
    }

    public int OpenCamera(PXRCameraCallBack call) {

        Log.d(TAG, "OpenCamera");

        if (mCaptureState > PXRCamera.PXRCaptureState.CAPTURE_STATE_IDLE.ordinal()) {
            return 0;
        }

        //  mIsPreview = false;
      //  Map<String, String> cameraParams = new HashMap<>();
       // cameraParams.put(PXRCamera.CAMERA_PARAMS_KEY_MCTF, PXRCamera.VALUE_TRUE);
       // cameraParams.put(PXRCamera.CAMERA_PARAMS_KEY_EIS, PXRCamera.VALUE_TRUE);
       // cameraParams.put(PXRCamera.CAMERA_PARAMS_KEY_MFNR, PXRCamera.VALUE_TRUE);
        try {
            int openRes = mPXRCamera.openCameraAsync();
            if (openRes != 0) {
                Log.d(TAG, "openCameraAsync:" + openRes);
            }
        } catch (Exception ex) {
            Log.e(TAG, ex.toString());
        }

        return mPXRCamera.setCallback(call);
    }

    public static String GetMovieSavePath() {
        return "/sdcard/Download/";
    }
    private final Object mediaEncodeLock = new Object();  // 线程锁
    public int StartPreview(int width, int height, int mode) {
        previewWidth = width;
        previewHeight = height;

        renderMode = mode;
        Log.i(TAG, "StartPreview " + " width:" + width + " height:" + height );

        useState = UseState.Preview;

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
            MediaCodecInfo.CodecCapabilities capabilities = mediaEncode.getCodecInfo().getCapabilitiesForType("video/avc");
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

    private int StartSendImage(String ip, int port) {
        targetIp = ip;
        targetPort = port;
        Log.i(TAG, "StartSendImage " + " ip:" + ip + " port:" + port);
        encodeThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Log.i(TAG, "StartPreview  socket start " + targetIp + " " + targetPort);
                    socket = new Socket();
                    socket.connect(new InetSocketAddress(targetIp, targetPort), 5000);
                    outputStream = socket.getOutputStream();
                    EncodeStreaming(true);
                    Log.i(TAG, "startStreaming");
                } catch (IOException e) {
                    Log.e(TAG, "Socket connection failed", e);

                    mPXRCamera.stopPreview();
                    mediaEncode.stop();
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


    public void EncodeStreaming(boolean tcpSend) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        Log.i(TAG, "tartStreaming tcpSend:" + tcpSend + " useState:" + useState);
        while (useState == UseState.Preview) {
            try {
                long startTime = System.currentTimeMillis(); // Record the start time of the loop
                Log.i(TAG, "  startTime " + startTime);
                int outputBufferIndex;
                synchronized (mediaEncodeLock) {
                    if (mediaEncode == null) break; // 避免 NullPointerException
                    outputBufferIndex = mediaEncode.dequeueOutputBuffer(info, 10000);
                }
                if (outputBufferIndex >= 0) {
                    ByteBuffer encodedData;
                    // Obtain encoded data
                    synchronized (mediaEncodeLock) {
                        if (mediaEncode == null) break;
                         encodedData = mediaEncode.getOutputBuffer(outputBufferIndex);
                    }
                    if (tcpSend) {
                        int bodyLength = encodedData.remaining();
                        byte[] data = new byte[bodyLength];
                        encodedData.get(data);

                        int length = data.length;

                        ByteBuffer buffer = ByteBuffer.allocate(4 + length);
                        buffer.order(ByteOrder.BIG_ENDIAN); // Set byte order (big endian)
                        buffer.putInt(length); // Write length header

                        buffer.put(data);
                        byte[] packet = buffer.array();
                        // Check if the Socket is connected properly
                        if (socket != null && socket.isConnected()) {

                            Log.i(TAG, "  outputStream.write " + length);
                            outputStream.write(packet);
                            outputStream.flush();
                        } else {
                            Log.i(TAG, "Socket connection disconnected, try reconnecting...");
                            reconnectSocket();
                        }
                    } else {
                        if (encodedData != null && isMuxerStarted) {
                           // Log.i(TAG, "info.presentationTimeUs:" +  info.presentationTimeUs);
                            encodedData = addSEI(encodedData,  info.presentationTimeUs);//微秒
                            mediaMuxer.writeSampleData(trackIndex, encodedData, info);
                        }
                    }
                    synchronized (mediaEncodeLock) {
                        if (mediaEncode == null) break;
                        mediaEncode.releaseOutputBuffer(outputBufferIndex, false);
                    }
                } else if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // There is no output buffer, you may need to try again later
                    continue;
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // Output format changes (such as when the encoder is reinitialized)
                    if(!tcpSend)
                    {
                        synchronized (mediaEncodeLock) {
                            if (mediaEncode == null) break;
                            MediaFormat newFormat = mediaEncode.getOutputFormat();
                            ByteBuffer bf1 = newFormat.getByteBuffer("csd-1");
                            ByteBuffer bf2 = newFormat.getByteBuffer("csd-0");
                            Log.i(TAG, "The output format has been changed:" + newFormat);

                            trackIndex = mediaMuxer.addTrack(mediaEncode.getOutputFormat());
                            mediaMuxer.start();
                            isMuxerStarted = true;
                        }
                    }
                }

            } catch (IOException e) {
                e.printStackTrace();
                reconnectSocket(); // Attempt to reconnect when an exception occurs
            }
        }
    }

    //在每帧中插入时间戳，此时间戳与操作录制文件中“predictTime”字段对应.
    private ByteBuffer addSEI(ByteBuffer encodedData, long timestamp) {
        byte[] seiHeader = new byte[]{0x00, 0x00, 0x00, 0x01, 0x06}; // SEI NALU 头部
        byte seiPayloadType = 0x05;  // SEI 类型 5（User Data Unregistered）

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
        seiBuffer.put((byte) 0x80);    // RBSP Stop Bit，防止截断

        // 组合 SEI 数据和原始数据
        ByteBuffer outputBuffer = ByteBuffer.allocate(seiBuffer.remaining() + encodedData.remaining());
        outputBuffer.put(seiBuffer);
        outputBuffer.put(encodedData);
        outputBuffer.flip();

        return outputBuffer;
    }

    public int StopPreview() {
        useState = UseState.IDLE;
        Log.i("StopPreview:", "StopPreview:1");
        int res = mPXRCamera.stopPreview();
        Log.i("StopPreview:", "StopPreview:2");
        synchronized (mediaEncodeLock) {
            if (mediaEncode != null) {
                mediaEncode.signalEndOfInputStream();
                EncodeStreaming(targetPort > 0);
                mediaEncode.stop();
                mediaEncode.release();
                mediaEncode = null;
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

        return res;
    }

    public void SetConfig(int enableMvHevc, int videoFps) {
        mEnableMvHevc = enableMvHevc;
        mVideoFps = videoFps;
     //   mBitrate = bitrate;

        Log.i(TAG, "SetConfig: " + mEnableMvHevc + " " + mVideoFps +  " " + mBitrate);
        Log.i(TAG, "SetConfig mCaptureState: " + mCaptureState);

     //   Map<String, String> configureParams = new HashMap<>();
       // configureParams.put(PXRCapture.CONFIGURE_PARAMS_KEY_ENABLE_MVHEVC, String.valueOf(enableMvHevc));// 0
       // configureParams.put(PXRCapture.CONFIGURE_PARAMS_KEY_VIDEO_FPS, String.valueOf(videoFps));//60
       // configureParams.put(PXRCapture.CONFIGURE_PARAMS_KEY_VIDEO_WIDTH, String.valueOf(videoWidth));//2048
       // configureParams.put(PXRCapture.CONFIGURE_PARAMS_KEY_VIDEO_HEIGHT, String.valueOf(videoHeight));//1536 / 2
       // configureParams.put(PXRCapture.CONFIGURE_PARAMS_KEY_VIDEO_BITRATE, String.valueOf(bitrate));//10 * 1024 * 1024  默认40M
        mPXRCamera.enableEIS(true);
        mPXRCamera.configure(enableMvHevc==1,videoFps);
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
         *Output Width: The width of the video that the business needs to output
         *Output Height: The height of the video that the business needs to output
         *OutputFovHorizontal: The horizontal FOV of the video that the business needs to output Default 76.35f
         *OutputFovVertical: The vertical FOV of the video that the business needs to output Default 61.05f
         *Camera Intrinsics: Distortion Parameters
         *Len: Distortion parameter length
         *Return: Whether the execution was successful. 1 success 0 failure
         */
        mPXRCamera.getCameraIntrinsics(width,
                height,
                76.35f,
                61.05f,
                cameraIntrinsics,
                len);

        //   Log.i(tag, "cameraIntrinsics len: " + len[0] + " value: " + Arrays.toString(cameraIntrinsics));
        return Arrays.toString(cameraIntrinsics);
    }

    public static String formatRecordTimeString(long startTime, long stopTime, String timezone, String formatStr) {
        long time = (stopTime == 0 ? System.currentTimeMillis() : stopTime) - startTime;//long now = android.os.SystemClock.uptimeMillis();
        SimpleDateFormat format = new SimpleDateFormat(formatStr);
        format.setTimeZone(timezone == null ? TimeZone.getDefault() : TimeZone.getTimeZone(timezone));
        Date d1 = new Date(time);
        String t1 = format.format(d1);
        return t1;
    }
    public int CloseCamera() {

        useState = UseState.IDLE;
        Log.i(TAG, "CloseCamera");
        mPXRCamera.reset();
        mCaptureState = PXRCamera.PXRCaptureState.CAPTURE_STATE_IDLE.ordinal();
        cleanup();

        return mPXRCamera.closeCamera();
    }

    public enum UseState {
        IDLE, Preview, Record
    }

    private void reconnectSocket() {
        if (useState != UseState.Preview) {
            return;
        }
        // Try reconnecting the Socket
        try {

            synchronized (mediaEncodeLock) {
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }
                socket = new Socket(targetIp, targetPort);
                outputStream = socket.getOutputStream();
                Log.i(TAG, "Socket connected!");
            }


        } catch (IOException e) {
            e.printStackTrace();
            Log.i(TAG, "Reconnect failed, please check your network connection.");
        }
    }

    private void cleanup() {
        try {
            synchronized (mediaEncodeLock) {
                if (mediaEncode != null) {
                    mediaEncode.stop();
                    mediaEncode.release();
                    mediaEncode = null;
                }

                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
