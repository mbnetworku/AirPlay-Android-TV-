package com.airmirror.tv;

import android.util.Log;
import android.view.Surface;

import com.github.serezhka.airplay.protocol.media.AudioStreamInfo;
import com.github.serezhka.airplay.protocol.media.VideoStreamInfo;
import com.github.serezhka.airplay.server.Playback;

/**
 * Bridges the AirPlay server callbacks to Android's hardware decoders.
 * Called from Netty threads; UI changes go through {@link Listener}.
 */
public class MirrorPlayback implements Playback {

    private static final String TAG = "MirrorPlayback";

    public interface Listener {
        void onMirroringStarted();

        void onMirroringStopped();

        void onVideoSize(int width, int height);
    }

    private final Listener listener;
    private final VideoDecoder video = new VideoDecoder();
    private final AudioDecoder audio = new AudioDecoder();
    private Observer observer = Observer.NONE;
    private volatile boolean videoActive;
    private volatile double volume = 1.0;

    public MirrorPlayback(Listener listener) {
        this.listener = listener;
        video.setSizeListener((w, h) -> {
            listener.onVideoSize(w, h);
            observer.onPresented();
        });
    }

    public void setSurface(Surface surface) {
        video.setSurface(surface);
    }

    public void release() {
        video.stop();
        audio.stop();
    }

    @Override
    public void setObserver(Observer observer) {
        this.observer = observer == null ? Observer.NONE : observer;
    }

    @Override
    public void onVideoFormat(VideoStreamInfo videoStreamInfo) {
        Log.i(TAG, "Video stream: " + videoStreamInfo);
        videoActive = true;
        listener.onMirroringStarted();
    }

    @Override
    public void onVideo(byte[] bytes) {
        if (!videoActive) {
            videoActive = true;
            listener.onMirroringStarted();
        }
        video.feed(bytes);
    }

    @Override
    public void onVideoSrcDisconnect() {
        Log.i(TAG, "Video source disconnected");
        videoActive = false;
        video.stop();
        listener.onMirroringStopped();
    }

    @Override
    public void onAudioFormat(AudioStreamInfo audioStreamInfo) {
        Log.i(TAG, "Audio stream: " + audioStreamInfo);
        audio.start(audioStreamInfo, (float) volume);
    }

    @Override
    public void onAudio(byte[] bytes) {
        audio.feed(bytes, 0, bytes.length);
    }

    @Override
    public void onAudio(byte[] bytes, int offset, int length) {
        audio.feed(bytes, offset, length);
    }

    @Override
    public void onAudioSrcDisconnect() {
        Log.i(TAG, "Audio source disconnected");
        audio.stop();
    }

    @Override
    public void onVolume(double volumeLinear) {
        volume = volumeLinear;
        audio.setVolume((float) volumeLinear);
    }

    @Override
    public double volume() {
        return volume;
    }
}
