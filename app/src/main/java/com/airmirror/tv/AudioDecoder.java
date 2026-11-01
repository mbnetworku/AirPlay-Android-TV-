package com.airmirror.tv;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import com.github.serezhka.airplay.protocol.media.AudioStreamInfo;

import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Decodes AirPlay audio (AAC-ELD while mirroring, AAC-LC / ALAC for audio-only casting)
 * with MediaCodec and plays the PCM through an AudioTrack.
 */
class AudioDecoder {

    private static final String TAG = "AudioDecoder";
    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNELS = 2;

    private final Object lock = new Object();
    private final LinkedBlockingQueue<Integer> freeInputs = new LinkedBlockingQueue<>();
    private HandlerThread thread;
    private MediaCodec codec;
    private AudioTrack track;
    private float volume = 1f;

    void start(AudioStreamInfo info, float volume) {
        synchronized (lock) {
            release();
            this.volume = volume;
            MediaFormat format = formatFor(info.getCompressionType());
            if (format == null) {
                Log.w(TAG, "Unsupported audio compression: " + info.getCompressionType());
                return;
            }
            try {
                track = createTrack();
                track.setVolume(volume);
                track.play();
                thread = new HandlerThread("audio-decoder");
                thread.start();
                AudioTrack out = track;
                MediaCodec c = Codecs.createDecoder(format.getString(MediaFormat.KEY_MIME), new MediaCodec.Callback() {
                    @Override
                    public void onInputBufferAvailable(MediaCodec mc, int index) {
                        freeInputs.offer(index);
                    }

                    @Override
                    public void onOutputBufferAvailable(MediaCodec mc, int index, MediaCodec.BufferInfo bi) {
                        try {
                            ByteBuffer pcm = mc.getOutputBuffer(index);
                            if (pcm != null && bi.size > 0) {
                                pcm.position(bi.offset);
                                pcm.limit(bi.offset + bi.size);
                                out.write(pcm, bi.size, AudioTrack.WRITE_BLOCKING);
                            }
                            mc.releaseOutputBuffer(index, false);
                        } catch (IllegalStateException ignored) {
                            // codec or track is being released
                        }
                    }

                    @Override
                    public void onError(MediaCodec mc, MediaCodec.CodecException e) {
                        Log.e(TAG, "Audio decoder error", e);
                    }

                    @Override
                    public void onOutputFormatChanged(MediaCodec mc, MediaFormat f) {
                        Log.i(TAG, "Audio output format " + f);
                    }
                }, new Handler(thread.getLooper()));
                c.configure(format, null, null, 0);
                c.start();
                codec = c;
            } catch (Exception e) {
                Log.e(TAG, "Cannot start audio decoder for " + info.getCompressionType(), e);
                release();
            }
        }
    }

    void feed(byte[] data, int offset, int length) {
        synchronized (lock) {
            if (codec == null || length <= 0) {
                return;
            }
            try {
                Integer index = freeInputs.poll(20, TimeUnit.MILLISECONDS);
                if (index == null) {
                    return;
                }
                ByteBuffer buffer = codec.getInputBuffer(index);
                if (buffer == null || buffer.capacity() < length) {
                    codec.queueInputBuffer(index, 0, 0, 0, 0);
                    return;
                }
                buffer.clear();
                buffer.put(data, offset, length);
                codec.queueInputBuffer(index, 0, length, System.nanoTime() / 1000, 0);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IllegalStateException e) {
                Log.w(TAG, "Audio decoder rejected frame", e);
            }
        }
    }

    void setVolume(float volume) {
        synchronized (lock) {
            this.volume = volume;
            if (track != null) {
                track.setVolume(volume);
            }
        }
    }

    void stop() {
        synchronized (lock) {
            release();
        }
    }

    private static MediaFormat formatFor(AudioStreamInfo.CompressionType type) {
        if (type == null) {
            return null;
        }
        switch (type) {
            case AAC_ELD: {
                MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS);
                // AudioSpecificConfig: AAC-ELD, 44.1 kHz, stereo, 480 samples per frame.
                f.setByteBuffer("csd-0", ByteBuffer.wrap(new byte[]{(byte) 0xF8, (byte) 0xE8, 0x50, 0x00}));
                return f;
            }
            case AAC: {
                MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS);
                // AudioSpecificConfig: AAC-LC, 44.1 kHz, stereo.
                f.setByteBuffer("csd-0", ByteBuffer.wrap(new byte[]{0x12, 0x10}));
                return f;
            }
            case ALAC: {
                MediaFormat f = MediaFormat.createAudioFormat("audio/alac", SAMPLE_RATE, CHANNELS);
                // ALAC magic cookie: 352 frames/packet, 16-bit, stereo, 44.1 kHz.
                f.setByteBuffer("csd-0", ByteBuffer.wrap(new byte[]{
                        0x00, 0x00, 0x01, 0x60, 0x00, 0x10, 0x28, 0x0A, 0x0E, 0x02, 0x00, (byte) 0xFF,
                        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, (byte) 0xAC, 0x44}));
                return f;
            }
            default:
                return null;
        }
    }

    private static AudioTrack createTrack() {
        int channelMask = AudioFormat.CHANNEL_OUT_STEREO;
        int encoding = AudioFormat.ENCODING_PCM_16BIT;
        int minBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE, channelMask, encoding);
        int bufferSize = Math.max(minBuffer, 8192) * 2;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return new AudioTrack(AudioManager.STREAM_MUSIC, SAMPLE_RATE, channelMask, encoding,
                    bufferSize, AudioTrack.MODE_STREAM);
        }
        return new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(channelMask)
                        .setEncoding(encoding)
                        .build())
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
    }

    private void release() {
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
        if (track != null) {
            try {
                track.stop();
            } catch (IllegalStateException ignored) {
            }
            track.release();
            track = null;
        }
        freeInputs.clear();
    }
}
