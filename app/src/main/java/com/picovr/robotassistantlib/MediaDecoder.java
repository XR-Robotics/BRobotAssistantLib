package com.picovr.robotassistantlib;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import com.picoxr.fbolibrary.FBOPlugin;

public class MediaDecoder {
    private final String TAG = "MediaDecoder";
    private MediaCodec mediaCodec;
    private String mimeType = "video/avc"; //H. 264 format
    private int previewTextureId;
    private FBOPlugin mFBOPlugin = null;
    private Thread tcpThread;
    private boolean receivie = false;
    private int width;
    private int height;

    private long lastTimestamp = 0;

    public void initialize(int unityTextureId, int width, int height) throws Exception {

        if (mediaCodec != null) {
            release();
        }
        this.width = width;
        this.height = height;
        if (mFBOPlugin == null) {
            mFBOPlugin = new FBOPlugin();
        }
        mFBOPlugin.init();
        mFBOPlugin.BuildTexture(unityTextureId, width, height);
        previewTextureId = unityTextureId;
        Log.i(TAG, "initialize:" + unityTextureId);
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
        //format.setInteger(MediaFormat.KEY_FRAME_RATE, 60);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);

        // Enable low latency mode
        format.setInteger(MediaFormat.KEY_PRIORITY, 0);  // Real-time priority
        format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);  // Enable low latency mode

        mediaCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        mediaCodec.configure(format, mFBOPlugin.getSurface(), null, 0);

        // // Request hardware acceleration
        //mediaCodec.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT);

        mediaCodec.start();
        Log.i(TAG, "mediaCodec start() with low latency mode enabled");
    }

    private byte[] buffer;

    private void startTCPServer(int port, boolean record) throws IOException {
        receivie = true;
        Log.i(TAG, "startTCPServer:" + port);
        if (buffer == null) {
            buffer = new byte[1024 * 1024];  // Adjust the buffer size according to the actual situation
        }
        tcpThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {

                    Log.i(TAG, "-ServerSocket:" + port);
                    ServerSocket serverSocket = new ServerSocket(port);
                    Socket socket = serverSocket.accept();
                    Log.i(TAG, "Socket connected:" + port);
                    InputStream inputStream = socket.getInputStream();
                    DataInputStream dataInputStream = new DataInputStream(inputStream);
                    while (receivie) {

                        // Read the 4-byte header and obtain the length of the packet body
                        byte[] header = new byte[4];
                        dataInputStream.readFully(header);

                        // Analyze package length (big end mode)
                        ByteBuffer buffer = ByteBuffer.wrap(header);
                        buffer.order(ByteOrder.BIG_ENDIAN);
                        int bodyLength = buffer.getInt();
                        Log.i(TAG, "Received packet length: " + bodyLength);

                        byte[] body = new byte[bodyLength];
                        dataInputStream.readFully(body); // Ensure complete reading

                        if (!receivie) {
                            break;
                        }

                        Log.i(TAG, "inputStream.read: " + bodyLength);
                        if (bodyLength > 0) {

                            if (record) {
                                Record(body, bodyLength);
                            }
                            decode(body, bodyLength);
                        }
                    }

                    inputStream.close();
                    socket.close();
                    serverSocket.close();

                    Log.i(TAG, "socket close");
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
        tcpThread.start();
    }

    FileOutputStream fileOutputStream = null;

    private void Record(byte[] data, int length) throws Exception {
        try {
            if (fileOutputStream == null) {
                String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
                String fileName = "/sdcard/Download/received_data_" + timeStamp + ".bin";
                Log.i(TAG, "fileName:" + fileName);
                File file = new File(fileName);
                fileOutputStream = new FileOutputStream(file);
            }
            fileOutputStream.write(data, 0, length);

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public boolean isUpdateFrame() {
        if (mFBOPlugin != null) {

            return mFBOPlugin.isUpdateFrame();
        }
        return false;
    }

    public void updateTexture() {

        // 更新 SurfaceTexture
        if (mFBOPlugin != null && isUpdateFrame()) {
            mFBOPlugin.updateTexture();

        }
    }

    private long computePresentationTime(long frameIndex) {

        //return 132 + frameIndex * 1000000 / 30;
        long currentTime = System.nanoTime() / 1000; // Convert to microseconds
        lastTimestamp = Math.max(lastTimestamp + 1, currentTime);
        return lastTimestamp;

    }


    private void decode(byte[] data, int length) throws Exception {

        //   Log.i(TAG, "decode length:" + length + " isComplete:" + isComplete + " isKeyFrame:" + isKeyFrame + " nalu:" + nalu);
        int inputBufferIndex = mediaCodec.dequeueInputBuffer(0);
        if (inputBufferIndex >= 0) {
            ByteBuffer inputBuffer = mediaCodec.getInputBuffer(inputBufferIndex);
            inputBuffer.clear();
            inputBuffer.put(data, 0, length);
            mediaCodec.queueInputBuffer(inputBufferIndex, 0, length, computePresentationTime(inputBufferIndex), 0);
        }

        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        int outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, 10);
        Log.i(TAG, "outputBufferIndex:" + outputBufferIndex + " savePng:" + savePng);
        while (true) {
            if (!receivie) {
                break;
            }
            if (outputBufferIndex >= 0) {

                if (savePng) {
                    ByteBuffer outputBuffer = mediaCodec.getOutputBuffer(outputBufferIndex);
                    Log.i(TAG, "saveFrameAsJPEG:" + outputBufferIndex);
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        Log.i(TAG, "saveFrameAs size:" + bufferInfo.size);

                        byte[] yuvData = new byte[bufferInfo.size];
                        outputBuffer.position(bufferInfo.offset);
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size);
                        outputBuffer.get(yuvData);

                        saveBitmapFromYUV(yuvData, width, height);
                    }
                    savePng = false;
                }

                // Release output buffer
                mediaCodec.releaseOutputBuffer(outputBufferIndex, true);
                outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, 1000);


            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat newFormat = mediaCodec.getOutputFormat();
                // Update renderer configuration
                Log.i(TAG, "reset mediaFormat " + newFormat);
                if (newFormat.containsKey("csd-0") && newFormat.containsKey("csd-1")) {
                    Log.i(TAG, "newFormat containsKey " + newFormat);
                }
                break;
            } else if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {

                break;
            }
        }
    }

    private void saveBitmapFromYUV(byte[] yuvData, int width, int height) {
        Bitmap bitmap = yuvToBitmap(yuvData, width, height);
        if (bitmap != null) {
            try {
                File file = new File("/sdcard/Download/", "decoded_frame_" + System.currentTimeMillis() + ".png");
                FileOutputStream fos = new FileOutputStream(file);
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
                fos.close();
                Log.i(TAG, "Saved PNG frame to " + file.getAbsolutePath());
            } catch (IOException e) {
                Log.e(TAG, "Failed to save PNG", e);
            }
        }
    }

    /**
     * YUV420 -> Bitmap
     */
    private Bitmap yuvToBitmap(byte[] yuvData, int width, int height) {
        YuvImage yuvImage = new YuvImage(yuvData, ImageFormat.NV21, width, height, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new Rect(0, 0, width, height), 100, out);
        byte[] imageBytes = out.toByteArray();
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
    }

    public void release() throws IOException {

        Log.i(TAG, "mediaCodec release");
        receivie = false;
        if (mediaCodec != null) {
            mediaCodec.stop();
            mediaCodec.release();
            mediaCodec = null;
        }

        if (fileOutputStream != null) fileOutputStream.close();
        fileOutputStream = null;
    }

    boolean savePng = false;

    public void SavePng() {
        savePng = true;
    }
}

