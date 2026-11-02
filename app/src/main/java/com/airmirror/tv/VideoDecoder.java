package com.airmirror.tv;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Hardware H.264 decoder for the mirror stream. Input is Annex-B access units; the
 * SPS/PPS unit (codec config) arrives first and again whenever the phone rotates.
 */
class VideoDecoder {

    private static final String TAG = "VideoDecoder";
    private static final int NAL_SPS = 7;

    interface SizeListener {
        void onSize(int width, int height);
    }

    private final Object lock = new Object();
    private final LinkedBlockingQueue<Integer> freeInputs = new LinkedBlockingQueue<>();
    private HandlerThread thread;
    private MediaCodec codec;
    private Surface surface;
    private byte[] config;
    private boolean waitingForKeyFrame;
    private boolean firstFrameQueued;
    private SizeListener sizeListener;

    void setSizeListener(SizeListener sizeListener) {
        this.sizeListener = sizeListener;
    }

    void setSurface(Surface surface) {
        synchronized (lock) {
            this.surface = surface;
            releaseCodec();
            if (surface != null && config != null) {
                // Resume after the surface came back; frames show again from the next key frame.
                createCodec();
            }
        }
    }

    void feed(byte[] accessUnit) {
        if (accessUnit.length < 5) {
            return;
        }
        int nalType = accessUnit[4] & 0x1F;
        synchronized (lock) {
            if (nalType == NAL_SPS) {
                if (codec != null && Arrays.equals(config, accessUnit)) {
                    return;
                }
                config = accessUnit.clone();
                releaseCodec();
                if (surface != null) {
                    createCodec();
                }
                return;
            }
            if (codec == null) {
                return;
            }
            if (waitingForKeyFrame) {
                if (!containsIdr(accessUnit)) {
                    return;
                }
                waitingForKeyFrame = false;
            }
            queue(accessUnit, 0);
        }
    }

    void stop() {
        synchronized (lock) {
            config = null;
            releaseCodec();
        }
    }

    /**
     * A fresh decoder lands on the same {@link Surface} a just-released one (mirroring vs. video
     * casting swap) was using. On slower devices the old one's native teardown can still be
     * finishing when this one tries to attach, which fails with "already connected" even though
     * we already told it to let go — so give it one short, bounded retry before giving up.
     */
    private void createCodec() {
        for (int attempt = 1; attempt <= 2; attempt++) {
            if (attemptCreateCodec()) {
                return;
            }
            if (attempt < 2) {
                Log.w(TAG, "Retrying decoder start, previous surface owner may still be releasing it");
                try {
                    Thread.sleep(150);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** @return true if the decoder started; false means the caller may retry. */
    private boolean attemptCreateCodec() {
        try {
            thread = new HandlerThread("video-decoder");
            thread.start();
            freeInputs.clear();
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024);
            setCodecData(format, config);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                format.setInteger(MediaFormat.KEY_PRIORITY, 0); // realtime
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
            }
            MediaCodec c = Codecs.createDecoder(MediaFormat.MIMETYPE_VIDEO_AVC, new MediaCodec.Callback() {
                @Override
                public void onInputBufferAvailable(MediaCodec mc, int index) {
                    freeInputs.offer(index);
                }

                @Override
                public void onOutputBufferAvailable(MediaCodec mc, int index, MediaCodec.BufferInfo info) {
                    try {
                        mc.releaseOutputBuffer(index, true);
                    } catch (IllegalStateException ignored) {
                        // codec is being released
                    }
                }

                @Override
                public void onError(MediaCodec mc, MediaCodec.CodecException e) {
                    Log.e(TAG, "Decoder error", e);
                }

                @Override
                public void onOutputFormatChanged(MediaCodec mc, MediaFormat f) {
                    int w = f.getInteger(MediaFormat.KEY_WIDTH);
                    int h = f.getInteger(MediaFormat.KEY_HEIGHT);
                    if (f.containsKey("crop-left") && f.containsKey("crop-right")) {
                        w = f.getInteger("crop-right") + 1 - f.getInteger("crop-left");
                    }
                    if (f.containsKey("crop-top") && f.containsKey("crop-bottom")) {
                        h = f.getInteger("crop-bottom") + 1 - f.getInteger("crop-top");
                    }
                    Log.i(TAG, "Video size " + w + "x" + h);
                    if (sizeListener != null) {
                        sizeListener.onSize(w, h);
                    }
                }
            }, new Handler(thread.getLooper()));
            c.configure(format, surface, null, 0);
            c.start();
            codec = c;
            waitingForKeyFrame = true;
            firstFrameQueued = false;
            Log.i(TAG, "H.264 decoder started");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Cannot start H.264 decoder", e);
            releaseCodec();
            return false;
        }
    }

    /** Splits the Annex-B SPS/PPS unit into csd-0 (SPS) and csd-1 (PPS). */
    private static void setCodecData(MediaFormat format, byte[] annexB) {
        int sps = -1;
        int pps = -1;
        for (int i = 0; i + 4 < annexB.length; i++) {
            if (annexB[i] == 0 && annexB[i + 1] == 0 && annexB[i + 2] == 0 && annexB[i + 3] == 1) {
                int type = annexB[i + 4] & 0x1F;
                if (type == NAL_SPS && sps < 0) {
                    sps = i;
                } else if (type == 8 && pps < 0) {
                    pps = i;
                }
            }
        }
        if (sps < 0 || pps < 0) {
            return;
        }
        int spsEnd = sps < pps ? pps : annexB.length;
        int ppsEnd = pps < sps ? sps : annexB.length;
        format.setByteBuffer("csd-0", ByteBuffer.wrap(Arrays.copyOfRange(annexB, sps, spsEnd)));
        format.setByteBuffer("csd-1", ByteBuffer.wrap(Arrays.copyOfRange(annexB, pps, ppsEnd)));
    }

    private void queue(byte[] data, int flags) {
        try {
            // Some boxes need a few hundred ms before the first input buffer is free.
            Integer index = freeInputs.poll(firstFrameQueued ? 50 : 2000, TimeUnit.MILLISECONDS);
            if (index == null) {
                Log.w(TAG, "Decoder busy, dropping frame");
                return;
            }
            ByteBuffer buffer = codec.getInputBuffer(index);
            if (buffer == null || buffer.capacity() < data.length) {
                Log.w(TAG, "Frame too large for decoder buffer: " + data.length);
                codec.queueInputBuffer(index, 0, 0, 0, 0);
                return;
            }
            buffer.clear();
            buffer.put(data);
            codec.queueInputBuffer(index, 0, data.length, System.nanoTime() / 1000, flags);
            if (!firstFrameQueued) {
                firstFrameQueued = true;
                Log.i(TAG, "First frame queued, " + data.length + " bytes");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IllegalStateException e) {
            Log.w(TAG, "Decoder rejected frame", e);
        }
    }

    private static boolean containsIdr(byte[] au) {
        for (int i = 0; i + 4 < au.length; i++) {
            if (au[i] == 0 && au[i + 1] == 0 && au[i + 2] == 0 && au[i + 3] == 1) {
                int type = au[i + 4] & 0x1F;
                if (type == 5) {
                    return true;
                }
                if (type == 1) {
                    return false;
                }
            }
        }
        return false;
    }

    private void releaseCodec() {
        if (codec != null) {
            try {
                codec.stop();
            } catch (Exception ignored) {
            }
            codec.release();
            codec = null;
        }
        if (thread != null) {
            thread.quitSafely();
            thread = null;
        }
        freeInputs.clear();
    }
}
