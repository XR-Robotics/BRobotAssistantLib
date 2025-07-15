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
    private String mimeType = "video/avc"; // H. 264 format
    private int previewTextureId;
    private FBOPlugin mFBOPlugin = null;
    private Thread tcpThread;
    private boolean receivie = false;
    private int width;
    private int height;

    private long lastTimestamp = 0;

    public void initialize(int unityTextureId, int width, int height) throws Exception {
        Log.i(TAG, "initialize: textureId=" + unityTextureId + ", size=" + width + "x" + height);

        // Clean up any existing resources first
        if (mediaCodec != null) {
            release();
        }

        this.width = width;
        this.height = height;
        this.previewTextureId = unityTextureId;

        // Initialize FBOPlugin
        if (mFBOPlugin == null) {
            mFBOPlugin = new FBOPlugin();
        }
        mFBOPlugin.init();
        mFBOPlugin.BuildTexture(unityTextureId, width, height);

        // Create and configure MediaCodec
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);

        // Enable low latency mode
        format.setInteger(MediaFormat.KEY_PRIORITY, 0); // Real-time priority
        format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1); // Enable low latency mode

        mediaCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        mediaCodec.configure(format, mFBOPlugin.getSurface(), null, 0);
        mediaCodec.start();

        // Reset state variables
        lastTimestamp = 0;
        savePng = false;

        Log.i(TAG, "MediaDecoder initialized successfully with low latency mode enabled");
    }

    private byte[] buffer;

    public void startTCPServer(int port, boolean record) throws IOException {
        // Stop existing TCP server if running
        stopTCPServer();

        receivie = true;
        Log.i(TAG, "startTCPServer:" + port);
        if (buffer == null) {
            buffer = new byte[1024 * 1024]; // Adjust the buffer size according to the actual situation
        }
        tcpThread = new Thread(new Runnable() {
            @Override
            public void run() {
                ServerSocket serverSocket = null;
                Socket socket = null;
                InputStream inputStream = null;
                DataInputStream dataInputStream = null;

                try {
                    Log.i(TAG, "-ServerSocket:" + port);
                    serverSocket = new ServerSocket(port);
                    socket = serverSocket.accept();
                    Log.i(TAG, "Socket connected:" + port);
                    inputStream = socket.getInputStream();
                    dataInputStream = new DataInputStream(inputStream);

                    while (receivie && !Thread.currentThread().isInterrupted()) {
                        try {
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

                            if (!receivie || Thread.currentThread().isInterrupted()) {
                                break;
                            }

                            Log.i(TAG, "inputStream.read: " + bodyLength);
                            if (bodyLength > 0) {
                                if (record) {
                                    Record(body, bodyLength);
                                }
                                decode(body, bodyLength);
                            }
                        } catch (IOException e) {
                            if (receivie && !Thread.currentThread().isInterrupted()) {
                                Log.e(TAG, "Error reading from socket", e);
                            }
                            break;
                        }
                    }
                } catch (Exception e) {
                    if (receivie && !Thread.currentThread().isInterrupted()) {
                        Log.e(TAG, "TCP server error", e);
                    }
                } finally {
                    // Clean up resources
                    try {
                        if (dataInputStream != null)
                            dataInputStream.close();
                        if (inputStream != null)
                            inputStream.close();
                        if (socket != null)
                            socket.close();
                        if (serverSocket != null)
                            serverSocket.close();
                    } catch (IOException e) {
                        Log.e(TAG, "Error closing TCP resources", e);
                    }
                    Log.i(TAG, "TCP thread finished");
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

        // return 132 + frameIndex * 1000000 / 30;
        long currentTime = System.nanoTime() / 1000; // Convert to microseconds
        lastTimestamp = Math.max(lastTimestamp + 1, currentTime);
        return lastTimestamp;

    }

    private void decode(byte[] data, int length) throws Exception {

        // Log.i(TAG, "decode length:" + length + " isComplete:" + isComplete + "
        // isKeyFrame:" + isKeyFrame + " nalu:" + nalu);
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

        // Stop TCP server first
        stopTCPServer();

        // Stop and release MediaCodec
        if (mediaCodec != null) {
            try {
                mediaCodec.stop();
                mediaCodec.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing MediaCodec", e);
            }
            mediaCodec = null;
        }

        // Clean up FBOPlugin
        if (mFBOPlugin != null) {
            try {
                mFBOPlugin.release(); // Assume FBOPlugin has a release method
            } catch (Exception e) {
                Log.w(TAG, "Error releasing FBOPlugin (method may not exist)", e);
            }
            mFBOPlugin = null;
        }

        // Reset state variables
        lastTimestamp = 0;
        savePng = false;
        previewTextureId = 0;
        width = 0;
        height = 0;
        buffer = null;

        Log.i(TAG, "MediaDecoder fully released and reset");
    }

    boolean savePng = false;

    public void SavePng() {
        savePng = true;
    }

    /**
     * Stop the TCP server and clean up resources
     */
    public void stopTCPServer() {
        Log.i(TAG, "stopTCPServer");
        receivie = false;

        if (tcpThread != null && tcpThread.isAlive()) {
            try {
                tcpThread.interrupt();
                tcpThread.join(1000); // Wait up to 1 second for thread to finish
            } catch (InterruptedException e) {
                Log.w(TAG, "Interrupted while waiting for TCP thread to stop", e);
                Thread.currentThread().interrupt(); // Restore interrupt status
            }
            tcpThread = null;
        }

        // Close file output stream if open
        if (fileOutputStream != null) {
            try {
                fileOutputStream.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing fileOutputStream", e);
            }
            fileOutputStream = null;
        }
    }
}
