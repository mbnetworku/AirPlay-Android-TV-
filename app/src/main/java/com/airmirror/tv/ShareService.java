package com.airmirror.tv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;
import android.view.WindowManager;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.ByteBuffer;

/**
 * Runs on an Android PHONE: captures its screen (MediaProjection, Android 5.0+), encodes it with
 * the phone's hardware H.264 encoder and streams it to {@link AndroidShareReceiver} on the TV.
 * Picture only for now. A foreground service, so sharing continues while other apps are open.
 */
public class ShareService extends Service {

    private static final String TAG = "ShareService";
    static final String EXTRA_RESULT_CODE = "result_code";
    static final String EXTRA_RESULT_DATA = "result_data";
    static final String EXTRA_HOST = "host";
    static final String EXTRA_PORT = "port";
    static final String EXTRA_TV_NAME = "tv_name";
    static final String ACTION_STOP = "com.airmirror.tv.STOP_SHARING";

    private static final String CHANNEL_ID = "screen_share";
    private static final int NOTIFICATION_ID = 3;
    private static final int[] MAX_SIDES = {1280, 960, 720}; // tried in order if an encoder refuses
    private static final int BIT_RATE = 4_000_000;
    private static final int FRAME_RATE = 30;

    enum State { IDLE, CONNECTING, WAITING_FOR_TV, SHARING, ENDED }

    interface StatusListener {
        void onShareStatus(State state, String message);
    }

    static volatile StatusListener statusListener;
    static volatile State state = State.IDLE;
    static volatile String stateMessage = "";

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean running;
    private volatile boolean rotationPending;
    private volatile Socket socket;
    private MediaProjection projection;
    private Thread worker;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running) {
            return START_NOT_STICKY; // already sharing
        }
        String host = intent.getStringExtra(EXTRA_HOST);
        int port = intent.getIntExtra(EXTRA_PORT, AndroidShareReceiver.PORT);
        String tvName = intent.getStringExtra(EXTRA_TV_NAME);
        if (tvName == null) {
            tvName = host;
        }

        // Android 14+ requires the media-projection foreground service to be up before the
        // projection is created.
        Notification n = buildNotification(getString(R.string.share_notification, tvName));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }

        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        Intent data = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        try {
            projection = mpm == null || data == null ? null
                    : mpm.getMediaProjection(intent.getIntExtra(EXTRA_RESULT_CODE, 0), data);
        } catch (RuntimeException e) {
            Log.e(TAG, "Screen capture refused", e);
            projection = null;
        }
        if (projection == null) {
            setState(State.ENDED, getString(R.string.share_error_capture));
            stopSelf();
            return START_NOT_STICKY;
        }
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                stopSelf(); // the user ended it from the system UI
            }
        }, main);

        running = true;
        String finalTvName = tvName;
        worker = new Thread(() -> run(host, port, finalTvName), "screen-share");
        worker.start();
        return START_NOT_STICKY;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        rotationPending = true; // the encoder is rebuilt for the new orientation
    }

    @Override
    public void onDestroy() {
        running = false;
        closeSocket();
        if (worker != null) {
            worker.interrupt();
        }
        if (projection != null) {
            try {
                projection.stop();
            } catch (RuntimeException ignored) {
            }
            projection = null;
        }
        if (state != State.ENDED) {
            setState(State.ENDED, getString(R.string.share_stopped));
        }
        super.onDestroy();
    }

    // --- Connection ---

    private void run(String host, int port, String tvName) {
        try {
            setState(State.CONNECTING, getString(R.string.share_connecting, tvName));
            Socket s = new Socket();
            socket = s;
            s.connect(new InetSocketAddress(host, port), 8000);
            s.setTcpNoDelay(true);
            s.setSoTimeout(75000); // the TV may be waiting for someone to press Allow
            String me = Build.MANUFACTURER + " " + Build.MODEL;
            AndroidShareReceiver.writeLine(s.getOutputStream(),
                    AndroidShareReceiver.HELLO + URLEncoder.encode(me.trim(), "UTF-8"));
            setState(State.WAITING_FOR_TV, getString(R.string.share_waiting, tvName));
            InputStream in = s.getInputStream();
            String reply = AndroidShareReceiver.readLine(in, 64);
            if ("BUSY".equals(reply)) {
                setState(State.ENDED, getString(R.string.share_busy, tvName));
                return;
            }
            if (!"OK".equals(reply)) {
                setState(State.ENDED, getString(R.string.share_denied, tvName));
                return;
            }
            setState(State.SHARING, getString(R.string.share_sharing, tvName));
            stream(new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 256 * 1024)));
            if (running) {
                setState(State.ENDED, getString(R.string.share_tv_stopped, tvName));
            }
        } catch (IOException e) {
            if (running) {
                Log.w(TAG, "Sharing ended", e);
                setState(State.ENDED, getString(R.string.share_error_network, tvName));
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "Sharing failed", e);
            setState(State.ENDED, getString(R.string.share_error_encoder));
        } finally {
            closeSocket();
            main.post(this::stopSelf);
        }
    }

    // --- Capture + encode ---

    private void stream(DataOutputStream out) throws IOException {
        Encoder enc = startEncoder();
        VirtualDisplay display = projection.createVirtualDisplay("AirMirror", enc.width, enc.height, enc.dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, enc.input, null, null);
        byte[] config = null;
        long lastSent = System.currentTimeMillis();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            while (running) {
                if (rotationPending) {
                    rotationPending = false;
                    Encoder next = startEncoder();
                    if (next.width != enc.width || next.height != enc.height) {
                        display.setSurface(null);
                        enc.release();
                        enc = next;
                        display.resize(enc.width, enc.height, enc.dpi);
                        display.setSurface(enc.input);
                        config = null;
                    } else {
                        next.release();
                    }
                }
                int index = enc.codec.dequeueOutputBuffer(info, 100_000);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // Some encoders hand SPS/PPS over in the format instead of a config buffer.
                    MediaFormat f = enc.codec.getOutputFormat();
                    ByteBuffer sps = f.getByteBuffer("csd-0");
                    ByteBuffer pps = f.getByteBuffer("csd-1");
                    if (sps != null && pps != null && config == null) {
                        config = annexB(concat(bytes(sps), bytes(pps)));
                        send(out, AndroidShareReceiver.TYPE_CONFIG, config);
                        lastSent = System.currentTimeMillis();
                    }
                } else if (index >= 0) {
                    ByteBuffer buf = enc.codec.getOutputBuffer(index);
                    byte[] data = null;
                    if (buf != null && info.size > 0) {
                        buf.position(info.offset);
                        buf.limit(info.offset + info.size);
                        data = new byte[info.size];
                        buf.get(data);
                    }
                    enc.codec.releaseOutputBuffer(index, false);
                    if (data != null) {
                        data = annexB(data);
                        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            config = data;
                            send(out, AndroidShareReceiver.TYPE_CONFIG, data);
                        } else {
                            if ((info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0 && config != null) {
                                // Re-send SPS/PPS with every key frame so the TV can (re)join any time.
                                send(out, AndroidShareReceiver.TYPE_CONFIG, config);
                            }
                            send(out, AndroidShareReceiver.TYPE_FRAME, data);
                        }
                        lastSent = System.currentTimeMillis();
                    }
                }
                if (System.currentTimeMillis() - lastSent > 2000) {
                    send(out, AndroidShareReceiver.TYPE_PING, new byte[0]); // static screen
                    lastSent = System.currentTimeMillis();
                }
            }
        } finally {
            try {
                display.release();
            } catch (RuntimeException ignored) {
            }
            enc.release();
        }
    }

    private static void send(DataOutputStream out, byte type, byte[] payload) throws IOException {
        out.writeInt(payload.length);
        out.writeByte(type);
        out.write(payload);
        out.flush();
    }

    private static final class Encoder {
        MediaCodec codec;
        Surface input;
        int width;
        int height;
        int dpi;

        void release() {
            try {
                codec.stop();
            } catch (RuntimeException ignored) {
            }
            codec.release();
            input.release();
        }
    }

    /** Hardware H.264 encoder for the current screen shape, scaled to at most 1280 px. */
    private Encoder startEncoder() throws IOException {
        DisplayMetrics m = new DisplayMetrics();
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        wm.getDefaultDisplay().getRealMetrics(m);
        IOException last = null;
        for (int maxSide : MAX_SIDES) {
            float scale = Math.min(1f, maxSide / (float) Math.max(m.widthPixels, m.heightPixels));
            int w = Math.max(16, Math.round(m.widthPixels * scale / 16f) * 16);
            int h = Math.max(16, Math.round(m.heightPixels * scale / 16f) * 16);
            MediaCodec codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            try {
                MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h);
                f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                f.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
                f.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
                f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
                // Keep sending frames on a still screen so the TV never stalls on a half-drawn one.
                f.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000);
                codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                Encoder e = new Encoder();
                e.codec = codec;
                e.input = codec.createInputSurface();
                e.width = w;
                e.height = h;
                e.dpi = m.densityDpi;
                codec.start();
                Log.i(TAG, "Encoding " + w + "x" + h);
                return e;
            } catch (RuntimeException e) {
                Log.w(TAG, "Encoder refused " + w + "x" + h + ", trying smaller", e);
                codec.release();
                last = new IOException("No suitable video encoder", e);
            }
        }
        throw last;
    }

    // --- H.264 helpers: the TV expects 4-byte Annex-B start codes, like AirPlay sends ---

    static byte[] annexB(byte[] in) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(in.length + 16);
        int i = 0;
        while (i < in.length) {
            if (i + 3 <= in.length && in[i] == 0 && in[i + 1] == 0 && in[i + 2] == 1) {
                out.write(0);
                out.write(0);
                out.write(0);
                out.write(1);
                i += 3;
            } else if (i + 4 <= in.length && in[i] == 0 && in[i + 1] == 0 && in[i + 2] == 0 && in[i + 3] == 1) {
                out.write(in, i, 4);
                i += 4;
            } else {
                out.write(in[i]);
                i++;
            }
        }
        return out.toByteArray();
    }

    private static byte[] bytes(ByteBuffer b) {
        ByteBuffer d = b.duplicate();
        d.position(0);
        byte[] out = new byte[d.remaining()];
        d.get(out);
        return out;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    // --- Status + notification ---

    private void setState(State s, String message) {
        state = s;
        stateMessage = message;
        main.post(() -> {
            StatusListener l = statusListener;
            if (l != null) {
                l.onShareStatus(s, message);
            }
        });
    }

    private void closeSocket() {
        Socket s = socket;
        socket = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private Notification buildNotification(String text) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(new NotificationChannel(
                        CHANNEL_ID, getString(R.string.share_title), NotificationManager.IMPORTANCE_LOW));
            }
        }
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, ShareActivity.class), flags);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, ShareService.class).setAction(ACTION_STOP), flags);
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setContentTitle(getString(R.string.share_title))
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(0, getString(R.string.share_stop), stop)
                .build();
    }
}
