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
    // Fallback only: used if the phone's SETUP request didn't say which rate/channels it picked.
    private static final int DEFAULT_SAMPLE_RATE = 44100;
    private static final int DEFAULT_CHANNELS = 2;

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
            int[] rateAndChannels = rateAndChannelsFor(info.getAudioFormat());
            int sampleRate = rateAndChannels[0];
            int channels = rateAndChannels[1];
            MediaFormat format = formatFor(info.getCompressionType(), sampleRate, channels,
                    info.getSamplesPerFrame());
            if (format == null) {
                Log.w(TAG, "Unsupported audio: " + info);
                return;
            }
            Log.i(TAG, "Audio stream " + info.getCompressionType() + " " + sampleRate + "Hz x" + channels
                    + "ch (negotiated " + info.getAudioFormat() + ")");
            try {
                track = createTrack(sampleRate, channels);
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

    /** Sample rate and channel count the phone actually asked for, not what we assume. */
    private static int[] rateAndChannelsFor(AudioStreamInfo.AudioFormat format) {
        if (format == null) {
            return new int[]{DEFAULT_SAMPLE_RATE, DEFAULT_CHANNELS};
        }
        switch (format) {
            case AAC_ELD_16000_1:
                return new int[]{16000, 1};
            case AAC_ELD_24000_1:
                return new int[]{24000, 1};
            case AAC_ELD_44100_1:
                return new int[]{44100, 1};
            case AAC_ELD_48000_1:
                return new int[]{48000, 1};
            case AAC_ELD_48000_2:
            case AAC_LC_48000_2:
            case PCM_48000_16_2:
            case PCM_48000_24_2:
            case ALAC_48000_16_2:
            case ALAC_48000_24_2:
                return new int[]{48000, 2};
            case PCM_48000_16_1:
            case PCM_48000_24_1:
                return new int[]{48000, 1};
            default:
                // Covers AAC_ELD_44100_2, AAC_LC_44100_2 and every other 44.1 kHz stereo variant.
                return new int[]{44100, 2};
        }
    }

    private static MediaFormat formatFor(AudioStreamInfo.CompressionType type, int sampleRate, int channels,
                                          int samplesPerFrame) {
        if (type == null) {
            return null;
        }
        switch (type) {
            case AAC_ELD: {
                MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels);
                f.setByteBuffer("csd-0", ByteBuffer.wrap(eldAudioSpecificConfig(sampleRate, channels)));
                return f;
            }
            case AAC: {
                MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels);
                f.setByteBuffer("csd-0", ByteBuffer.wrap(lcAudioSpecificConfig(sampleRate, channels)));
                return f;
            }
            case ALAC: {
                MediaFormat f = MediaFormat.createAudioFormat("audio/alac", sampleRate, channels);
                int frameLength = samplesPerFrame > 0 ? samplesPerFrame : 352;
                f.setByteBuffer("csd-0", ByteBuffer.wrap(alacMagicCookie(sampleRate, channels, frameLength)));
                return f;
            }
            default:
                return null;
        }
    }

    private static final int[] SAMPLE_RATE_TABLE = {
            96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050,
            16000, 12000, 11025, 8000, 7350,
    };

    private static int samplingFrequencyIndex(int sampleRate) {
        for (int i = 0; i < SAMPLE_RATE_TABLE.length; i++) {
            if (SAMPLE_RATE_TABLE[i] == sampleRate) {
                return i;
            }
        }
        return 4; // 44100, the common AirPlay default
    }

    /**
     * MPEG-4 AudioSpecificConfig for ER AAC ELD (object type 39), the codec AirPlay mirroring
     * normally sends: escape object type (31) + extension (7), sampling rate, channel count,
     * then ELDSpecificConfig with frameLengthFlag=1 (480 samples/frame), no SBR, no extensions.
     * Built bit by bit so any sample rate / channel count AirPlay negotiates is handled, not
     * just 44.1 kHz stereo.
     */
    private static byte[] eldAudioSpecificConfig(int sampleRate, int channels) {
        BitWriter bits = new BitWriter();
        bits.write(31, 5);                                   // audioObjectType: escape
        bits.write(39 - 32, 6);                               // audioObjectTypeExt -> AOT 39 (ER AAC ELD)
        bits.write(samplingFrequencyIndex(sampleRate), 4);
        bits.write(channels, 4);                              // channelConfiguration
        bits.write(1, 1);                                     // frameLengthFlag: 480 samples/frame
        bits.write(0, 1);                                     // aacSectionDataResilienceFlag
        bits.write(0, 1);                                     // aacScalefactorDataResilienceFlag
        bits.write(0, 1);                                     // aacSpectralDataResilienceFlag
        bits.write(0, 1);                                     // ldSbrPresentFlag
        bits.write(0, 1);                                     // end of ELD extension list
        return bits.toByteArray();
    }

    /** MPEG-4 AudioSpecificConfig for plain AAC-LC (object type 2), any sample rate / channel count. */
    private static byte[] lcAudioSpecificConfig(int sampleRate, int channels) {
        BitWriter bits = new BitWriter();
        bits.write(2, 5);                                     // audioObjectType: AAC LC
        bits.write(samplingFrequencyIndex(sampleRate), 4);
        bits.write(channels, 4);                              // channelConfiguration
        bits.write(0, 1);                                     // frameLengthFlag: 1024 samples/frame
        bits.write(0, 1);                                     // dependsOnCoreCoder
        bits.write(0, 1);                                     // extensionFlag
        return bits.toByteArray();
    }

    /** ALACSpecificConfig (24 bytes), with the sample rate / channel count AirPlay negotiated. */
    private static byte[] alacMagicCookie(int sampleRate, int channels, int frameLength) {
        ByteBuffer buf = ByteBuffer.allocate(24);
        buf.putInt(frameLength);
        buf.put((byte) 0);      // compatibleVersion
        buf.put((byte) 16);     // bitDepth
        buf.put((byte) 40);     // pb
        buf.put((byte) 10);     // mb
        buf.put((byte) 14);     // kb
        buf.put((byte) channels);
        buf.putShort((short) 255); // maxRun
        buf.putInt(0);          // maxFrameBytes: unconstrained
        buf.putInt(0);          // avgBitRate: unconstrained
        buf.putInt(sampleRate);
        return buf.array();
    }

    /** Minimal big-endian, MSB-first bit packer for building MPEG-4 AudioSpecificConfig bytes. */
    private static final class BitWriter {
        private final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        private int current;
        private int bitsInCurrent;

        void write(int value, int numBits) {
            for (int i = numBits - 1; i >= 0; i--) {
                current = (current << 1) | ((value >>> i) & 1);
                bitsInCurrent++;
                if (bitsInCurrent == 8) {
                    out.write(current);
                    current = 0;
                    bitsInCurrent = 0;
                }
            }
        }

        byte[] toByteArray() {
            if (bitsInCurrent > 0) {
                out.write(current << (8 - bitsInCurrent));
                current = 0;
                bitsInCurrent = 0;
            }
            return out.toByteArray();
        }
    }

    private static AudioTrack createTrack(int sampleRate, int channels) {
        int channelMask = channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
        int encoding = AudioFormat.ENCODING_PCM_16BIT;
        int minBuffer = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding);
        int bufferSize = Math.max(minBuffer, 8192) * 2;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, channelMask, encoding,
                    bufferSize, AudioTrack.MODE_STREAM);
        }
        return new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(sampleRate)
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
