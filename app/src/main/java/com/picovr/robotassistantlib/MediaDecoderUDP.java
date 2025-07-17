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

import com.picoxr.fbolibrary.FBOPlugin;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.net.Socket;

import java.security.MessageDigest; // Import for MD5
import java.security.NoSuchAlgorithmException; // Import for exception handling
import java.math.BigInteger; // For converting byte array to hex string

public class MediaDecoderUDP {

    private final String TAG = "MediaDecoderUDP";
    private MediaCodec mediaCodec;
    private String mimeType = "video/avc"; // H. 264 format
    private int previewTextureId;
    private FBOPlugin mFBOPlugin = null;
    private Thread udpThread;
    private boolean receivie = false;
    private int width;
    private int height;

    private long lastTimestamp = 0;
    // Add ServerSocket and Socket references to the class
    private DatagramSocket udpSocket;

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
        // format.setInteger(MediaFormat.KEY_FRAME_RATE, 60);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);

        // Enable low latency mode
        format.setInteger(MediaFormat.KEY_PRIORITY, 0); // Real-time priority
        format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1); // Enable low latency mode

        mediaCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        mediaCodec.configure(format, mFBOPlugin.getSurface(), null, 0);

        // // Request hardware acceleration
        // mediaCodec.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT);
        mediaCodec.start();
        mediaCodec.flush(); // This clears both input and output queues
        Log.i(TAG, "mediaCodec start() with low latency mode enabled");
    }

    private byte[] buffer;

    public void startServer(int port, boolean record) throws IOException {
        receivie = true;
        Log.i(TAG, "startServer:" + port);

        udpThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    udpSocket = new DatagramSocket(port);
                    // Set a timeout to allow checking the 'receivie' flag
                    udpSocket.setSoTimeout(1000);
                    // Buffer for receiving UDP packets. 65535 is the max theoretical size.
                    byte[] receiveBuffer = new byte[65535];

                    Log.i(TAG, "UDP Server started on port:" + port);

                    while (receivie) {
                        try {
                            DatagramPacket packet = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                            udpSocket.receive(packet); // Blocks until a packet is received or timeout

                            int packetLength = packet.getLength();
                            if (packetLength < 4) {
                                Log.e(TAG, "UDP packet too short to contain header");
                                return;
                            }

                            // Read the 4-byte header
                            byte[] header = new byte[4];
                            System.arraycopy(packet.getData(), packet.getOffset(), header, 0, 4);

                            // Decode header: packet body length (big-endian)
                            ByteBuffer buffer = ByteBuffer.wrap(header);
                            buffer.order(ByteOrder.BIG_ENDIAN);
                            int bodyLength = buffer.getInt();

                            if (bodyLength <= 0 || bodyLength > 65535) { // Max size sanity check
                                Log.e(TAG, "Invalid body length: " + bodyLength);
                                return;
                            }

                            if (packetLength < 4 + bodyLength) {
                                Log.e(TAG, "Incomplete UDP packet body: expected " + bodyLength + ", actual " + (packetLength - 4));
                                return;
                            }

                            // Copy body
                            byte[] body = new byte[bodyLength];
                            System.arraycopy(packet.getData(), packet.getOffset() + 4, body, 0, bodyLength);

                            Log.i(TAG, "Received UDP packet body length: " + bodyLength);

                            if (bodyLength > 0) {
                                try {
                                    if (record) {
                                        Record(body, bodyLength);
                                    }
                                    decode(body, bodyLength);
                                } catch (Exception e) {
                                    Log.e(TAG, "Error during Record or decode: " + e.getMessage());
                                }
                            }
                        } catch (SocketTimeoutException e) {
                            // Timeout is expected, continue loop to check 'receivie' flag
                        } catch (IOException e) {
                            if (receivie) {
                                Log.e(TAG, "IOException during data receive: " + e.getMessage());
                            }
                        }
                    }
                } catch (IOException e) {
                    Log.e(TAG, "UDP Socket error: " + e.getMessage());
                } finally {
                    if (udpSocket != null && !udpSocket.isClosed()) {
                        udpSocket.close();
                        Log.i(TAG, "UDP Socket closed.");
                    }
                }
                Log.i(TAG, "UDP Thread stopped.");
            }
        });
        udpThread.start();
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
            throw e; // Re-throw the exception to be caught in the calling method
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
                outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, 10);

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
        receivie = false; // Signal the UDP thread to stop
        if (mediaCodec != null) {
            mediaCodec.flush();
            mediaCodec.stop();
            mediaCodec.release();
            mediaCodec = null;
        }

        if (fileOutputStream != null) {
            fileOutputStream.close();
            fileOutputStream = null;
        }

        // Ensure the UDP server and sockets are closed
        stopServer();
    }

    public void stopServer() {
        Log.i(TAG, "Stopping UDP Server...");
        receivie = false; // Set the flag to false to stop the thread loops

        if (udpSocket != null) {
            udpSocket.close();
            udpSocket = null;
        }

        // Interrupt the UDP thread if it's running
        if (udpThread != null && udpThread.isAlive()) {
            udpThread.interrupt();
            try {
                udpThread.join(100); // Wait for the thread to finish (max 0.1 second)
            } catch (InterruptedException e) {
                Log.e(TAG, "UDP thread interrupted while joining: " + e.getMessage());
            } finally {
                udpThread = null;
            }
        }
        Log.i(TAG, "UDP Server stopped.");
    }

    boolean savePng = false;

    public void SavePng() {
        savePng = true;
    }
}