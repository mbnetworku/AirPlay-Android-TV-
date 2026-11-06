package com.airmirror.tv;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Log;
import android.view.Surface;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Receives an Android phone's screen from {@link ShareService} (the same app, running on the
 * phone). Completely separate from the AirPlay receiver: its own port, its own decoder, its own
 * thread. While idle it is one blocked {@code accept()} and an mDNS registration, nothing else.
 * <p>
 * Protocol (TCP, one phone at a time):
 * <pre>
 * phone -> TV  "AIRMIRROR 1 &lt;url-encoded device name&gt;\n"
 * TV -> phone  "OK\n" | "BUSY\n" | "DENIED\n"
 * phone -> TV  frames: int32 length, byte type, payload
 *              type 1 = H.264 SPS/PPS (Annex-B), 2 = H.264 access unit (Annex-B), 3 = keep-alive
 * </pre>
 */
final class AndroidShareReceiver {

    private static final String TAG = "AndroidShare";
    static final int PORT = 47100;
    static final String SERVICE_TYPE = "_airmirror._tcp.";
    static final String HELLO = "AIRMIRROR 1 ";

    static final byte TYPE_CONFIG = 1;
    static final byte TYPE_FRAME = 2;
    static final byte TYPE_PING = 3;

    private static final int MAX_PAYLOAD = 4 * 1024 * 1024;
    private static final int READ_TIMEOUT_MS = 15000; // the phone sends a keep-alive every 2 s

    interface Listener {
        /** A phone was let through and is about to send its picture. */
        void onAndroidStarted(String deviceName);

        void onAndroidStopped();

        void onAndroidVideoSize(int width, int height);
    }

    /** Same Allow/Deny flow as AirPlay. Called on this receiver's thread. */
    interface Gate {
        void requestApproval(String deviceName, java.util.function.Consumer<Boolean> decision);
    }

    private final Context context;
    private final Listener listener;
    private final VideoDecoder video = new VideoDecoder();
    private final AtomicBoolean active = new AtomicBoolean(false);
    private volatile Gate gate;
    private volatile BooleanSupplier otherSourceBusy = () -> false;
    private volatile ServerSocket serverSocket;
    private volatile Socket client;
    private Thread thread;
    private NsdManager.RegistrationListener nsdListener;

    AndroidShareReceiver(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        video.setSizeListener(listener::onAndroidVideoSize);
    }

    void setGate(Gate gate) {
        this.gate = gate;
    }

    /** True while AirPlay is showing something; phones are told the TV is busy. */
    void setOtherSourceBusy(BooleanSupplier busy) {
        this.otherSourceBusy = busy;
    }

    void setSurface(Surface surface) {
        video.setSurface(surface);
    }

    boolean isActive() {
        return active.get();
    }

    synchronized void start(String tvName) {
        if (thread != null) {
            return;
        }
        thread = new Thread(this::acceptLoop, "android-share");
        thread.start();
        advertise(tvName);
    }

    synchronized void stop() {
        unadvertise();
        Thread t = thread;
        thread = null;
        closeQuietly(serverSocket);
        closeQuietly(client);
        if (t != null) {
            t.interrupt();
        }
        video.stop();
    }

    // --- Listening ---

    private void acceptLoop() {
        try (ServerSocket ss = new ServerSocket()) {
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(PORT));
            serverSocket = ss;
            Log.i(TAG, "Listening for Android phones on port " + PORT);
            while (!Thread.currentThread().isInterrupted()) {
                Socket s = ss.accept();
                handle(s); // one phone at a time; others wait in the backlog and get BUSY after
            }
        } catch (IOException e) {
            if (thread != null) {
                Log.w(TAG, "Android share listener stopped", e);
            }
        }
    }

    private void handle(Socket s) {
        String name = "An Android phone";
        boolean started = false;
        try {
            s.setTcpNoDelay(true);
            s.setSoTimeout(10000);
            InputStream rawIn = new BufferedInputStream(s.getInputStream(), 64 * 1024);
            OutputStream out = s.getOutputStream();

            String hello = readLine(rawIn, 512);
            if (hello == null || !hello.startsWith(HELLO)) {
                return; // not our app
            }
            String encoded = hello.substring(HELLO.length()).trim();
            if (!encoded.isEmpty()) {
                name = URLDecoder.decode(encoded, "UTF-8");
            }
            if (otherSourceBusy.getAsBoolean()) {
                Log.i(TAG, name + " refused: AirPlay is in use");
                writeLine(out, "BUSY");
                return;
            }
            if (!approve(name)) {
                Log.i(TAG, name + " not allowed");
                writeLine(out, "DENIED");
                return;
            }
            if (otherSourceBusy.getAsBoolean()) { // an iPhone got in while we were asking
                writeLine(out, "BUSY");
                return;
            }
            writeLine(out, "OK");
            client = s;
            active.set(true);
            started = true;
            Log.i(TAG, name + " is sharing its screen");
            listener.onAndroidStarted(name);

            s.setSoTimeout(READ_TIMEOUT_MS);
            DataInputStream in = new DataInputStream(rawIn);
            while (!Thread.currentThread().isInterrupted()) {
                int length = in.readInt();
                byte type = in.readByte();
                if (length < 0 || length > MAX_PAYLOAD) {
                    Log.w(TAG, "Bad frame length " + length + ", dropping the phone");
                    return;
                }
                byte[] payload = new byte[length];
                in.readFully(payload);
                if (type == TYPE_CONFIG || type == TYPE_FRAME) {
                    video.feed(payload);
                }
            }
        } catch (EOFException | SocketTimeoutException e) {
            Log.i(TAG, name + " stopped sharing");
        } catch (IOException e) {
            Log.i(TAG, name + " disconnected: " + e.getMessage());
        } finally {
            closeQuietly(s);
            client = null;
            if (started) {
                video.stop();
                active.set(false);
                listener.onAndroidStopped();
            }
        }
    }

    private boolean approve(String name) {
        Gate g = gate;
        if (g == null) {
            return true;
        }
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean ok = new AtomicBoolean(false);
        g.requestApproval(name, allowed -> {
            ok.set(allowed);
            done.countDown();
        });
        try {
            // The phone waits up to 70 s for someone to press Allow on the TV.
            return done.await(60, TimeUnit.SECONDS) && ok.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // --- Discovery: phones find the TV by name on the Wi-Fi ---

    private void advertise(String tvName) {
        try {
            NsdManager nsd = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
            if (nsd == null) {
                return;
            }
            NsdServiceInfo info = new NsdServiceInfo();
            info.setServiceName(tvName);
            info.setServiceType(SERVICE_TYPE);
            info.setPort(PORT);
            nsdListener = new NsdManager.RegistrationListener() {
                @Override
                public void onServiceRegistered(NsdServiceInfo i) {
                    Log.i(TAG, "Visible to Android phones as " + i.getServiceName());
                }

                @Override
                public void onRegistrationFailed(NsdServiceInfo i, int error) {
                    Log.w(TAG, "Could not announce to Android phones (" + error + "); they can still type the TV's address");
                }

                @Override
                public void onServiceUnregistered(NsdServiceInfo i) {
                }

                @Override
                public void onUnregistrationFailed(NsdServiceInfo i, int error) {
                }
            };
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, nsdListener);
        } catch (RuntimeException e) {
            Log.w(TAG, "Network service discovery unavailable", e);
            nsdListener = null;
        }
    }

    private void unadvertise() {
        if (nsdListener == null) {
            return;
        }
        try {
            NsdManager nsd = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
            if (nsd != null) {
                nsd.unregisterService(nsdListener);
            }
        } catch (RuntimeException ignored) {
            // already gone
        }
        nsdListener = null;
    }

    // --- Small helpers, shared with ShareService ---

    static String readLine(InputStream in, int max) throws IOException {
        StringBuilder sb = new StringBuilder();
        while (sb.length() < max) {
            int c = in.read();
            if (c < 0) {
                return sb.length() == 0 ? null : sb.toString();
            }
            if (c == '\n') {
                return sb.toString();
            }
            if (c != '\r') {
                sb.append((char) c);
            }
        }
        return sb.toString();
    }

    static void writeLine(OutputStream out, String line) throws IOException {
        out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
            }
        }
    }
}
