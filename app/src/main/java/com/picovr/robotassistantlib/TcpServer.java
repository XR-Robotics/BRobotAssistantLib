package com.picovr.robotassistantlib;

import android.util.Log;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;


public class TcpServer {

    // Callback Interface Definition
    public interface ServerCallback {
        void onServerStarted(int port);

        void onClientConnected(Socket clientSocket); // Pass the socket for more info

        void onClientDisconnected();

        void onDataReceived(byte[] data, int length);

        void onError(String errorMessage, Exception e);

        void onServerStopped();
    }

    private final String TAG = "TcpServer";
    private Thread tcpThread;
    private boolean receive = false;

    private ServerCallback serverCallback; // Store the callback

    private ServerSocket serverSocket;
    private Socket socket;
    private InputStream inputStream;
    private DataInputStream dataInputStream;

    // Updated StartTCPServer method
    public void startTCPServer(int port, ServerCallback callback) throws IOException { // Added ServerCallback parameter
        this.serverCallback = callback; // Store the callback instance
        receive = true;
        Log.i(TAG, "startTCPServer:" + port);

        if (serverCallback != null) {
            serverCallback.onServerStarted(port); // Notify: Server is starting
        }

        tcpThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Log.i(TAG, "-ServerSocket:" + port);
                    serverSocket = new ServerSocket(port);

                    // Usually a server waits for connections in a loop if it supports multiple clients
                    // For this example, it accepts one client as in the original code.
                    socket = serverSocket.accept();
                    Log.i(TAG, "Socket connected:" + socket.getInetAddress().getHostAddress());
                    if (serverCallback != null) {
                        serverCallback.onClientConnected(socket); // Notify: Client connected
                    }

                    inputStream = socket.getInputStream();
                    dataInputStream = new DataInputStream(inputStream);

                    while (receive) { // Consider renaming 'receivie' to 'receive' or 'isRunning'
                        // Read the 4-byte header and obtain the length of the packet body
                        byte[] header = new byte[4];
                        try {
                            dataInputStream.readFully(header);
                        } catch (IOException e) {
//                            Log.e(TAG, "Failed to read header, client might have disconnected.", e);
                            if (serverCallback != null) {
                                // This might cause JNI ERROR (app bug): global reference table overflow.
//                                serverCallback.onError("Failed to read header", e);
                            }
                            continue; // skip loop if cannot read header
                        }


                        // Analyze package length (big end mode)
                        ByteBuffer buffer = ByteBuffer.wrap(header);
                        buffer.order(ByteOrder.BIG_ENDIAN);
                        int bodyLength = buffer.getInt();
                        Log.i(TAG, "Received packet length: " + bodyLength);

                        if (bodyLength <= 0 || bodyLength > 10 * 1024 * 1024) { // Basic sanity check for length
                            Log.w(TAG, "Invalid body length received: " + bodyLength);
                            if (serverCallback != null) {
                                serverCallback.onError("Invalid body length: " + bodyLength, null);
                            }
                            continue; // Potentially corrupt data, skip processing this connection
                        }

                        byte[] body = new byte[bodyLength];
                        try {
                            dataInputStream.readFully(body); // Ensure complete reading
                        } catch (IOException e) {
                            Log.e(TAG, "Failed to read body, client might have disconnected.", e);
                            if (serverCallback != null) {
                                serverCallback.onError("Failed to read body", e);
                            }
                            continue; // Skip loop
                        }


                        if (!receive) { // Check again before processing
                            break;
                        }

                        Log.i(TAG, "inputStream.read (body bytes): " + bodyLength);
                        if (bodyLength > 0) {
                            // Notify: Data received
                            if (serverCallback != null) {
                                serverCallback.onDataReceived(body, bodyLength);
                            }
                        }
                    }
                } catch (IOException e) {
                    Log.e(TAG, "IOException in TCP Server loop", e);
                    if (serverCallback != null) {
                        serverCallback.onError("Server IO Error", e);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Exception in TCP Server loop", e);
                    if (serverCallback != null) {
                        serverCallback.onError("Server Runtime Error", e);
                    }
                    // e.printStackTrace(); // Already logged above
                } finally {
                    try {
                        if (dataInputStream != null) dataInputStream.close();
                        if (inputStream != null) inputStream.close();
                        if (socket != null && !socket.isClosed()) {
                            socket.close();
                            Log.i(TAG, "Client socket closed");
                            if (serverCallback != null) {
                                serverCallback.onClientDisconnected(); // Notify: Client disconnected
                            }
                        }
                        if (serverSocket != null && !serverSocket.isClosed()) {
                            serverSocket.close();
                            Log.i(TAG, "Server socket closed");
                        }
                    } catch (IOException e) {
                        Log.e(TAG, "IOException during cleanup", e);
                        if (serverCallback != null) {
                            serverCallback.onError("Error during cleanup", e);
                        }
                    }
                    receive = false; // Ensure loop terminates
                    Log.i(TAG, "TCP Server thread finished.");
                    if (serverCallback != null) {
                        serverCallback.onServerStopped(); // Notify: Server thread stopped
                    }
                }
            }
        });
        tcpThread.setName("TcpServerThread-" + port); // Good practice to name threads
        tcpThread.start();
    }

    // Add a method to stop the server gracefully
    public void stopServer() throws InterruptedException {
        Log.i(TAG, "Attempting to stop TCP server...");
        receive = false; // Signal the loop to terminate

        try {
            if (inputStream != null) inputStream.close();
            if (socket != null && !socket.isClosed()) socket.close();
            if (serverSocket != null && !serverSocket.isClosed()) serverSocket.close();
            Log.i(TAG, "Stopped TCP server");
        } catch (IOException e) {
            Log.e(TAG, "Error while stopping server", e);
        }

        if (tcpThread != null) {
            tcpThread.interrupt(); // Interrupt the thread if it's blocking on I/O
            try {
                tcpThread.join(100); // Wait for the thread to die for up to 0.1 seconds
                if (tcpThread.isAlive()) {
                    Log.w(TAG, "TCP thread did not terminate in time. Forcing shutdown...");
                    // You might want to take additional action here, like closing sockets
                } else {
                    Log.i(TAG, "TCP thread stopped successfully.");
                }
            } catch (InterruptedException e) {
                Log.e(TAG, "Interrupted while waiting for TCP thread to stop.", e);
                throw e; // rethrow to signal the caller
            }
        }
    }
}