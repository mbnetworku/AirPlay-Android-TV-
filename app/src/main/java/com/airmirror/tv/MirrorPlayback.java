package com.airmirror.tv;

import android.util.Log;
import android.view.Surface;

import com.github.serezhka.airplay.protocol.media.AudioStreamInfo;
import com.github.serezhka.airplay.protocol.media.VideoStreamInfo;
import com.github.serezhka.airplay.server.ControlExchange;
import com.github.serezhka.airplay.server.Playback;

import java.util.function.Consumer;

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

    /** Lets the app hold off showing a picture from a device until someone approves it on the TV. */
    public interface ApprovalGate {
        /** Called on a background thread. Call {@code decision} (true/false) once, from any thread. */
        void requestApproval(String deviceName, Consumer<Boolean> decision);
    }

    private final Listener listener;
    private final VideoDecoder video = new VideoDecoder();
    private final AudioDecoder audio = new AudioDecoder();
    private Observer observer = Observer.NONE;
    private volatile boolean videoActive;
    private volatile double volume = 1.0;
    private volatile ApprovalGate approvalGate;
    private volatile String clientName = "An iPhone";
    private volatile boolean approved = true;
    private volatile boolean awaitingApproval;

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

    /** Pass {@code null} to allow every device without asking. */
    public void setApprovalGate(ApprovalGate gate) {
        this.approvalGate = gate;
    }

    @Override
    public void setObserver(Observer observer) {
        this.observer = observer == null ? Observer.NONE : observer;
    }

    /** The library hands us every RTSP exchange; pull the device's own name out of it, if sent. */
    @Override
    public void onControlExchange(ControlExchange exchange) {
        String name = exchange.header(exchange.requestHeaders(), "X-Apple-Client-Name");
        if (name != null && !name.trim().isEmpty()) {
            clientName = name.trim();
        }
    }

    @Override
    public void onVideoFormat(VideoStreamInfo videoStreamInfo) {
        Log.i(TAG, "Video stream: " + videoStreamInfo + " from " + clientName);
        ApprovalGate gate = approvalGate;
        if (gate == null) {
            videoActive = true;
            listener.onMirroringStarted();
            return;
        }
        approved = false;
        awaitingApproval = true;
        String name = clientName;
        gate.requestApproval(name, ok -> {
            awaitingApproval = false;
            approved = ok;
            if (ok) {
                Log.i(TAG, "Approved: " + name);
                videoActive = true;
                listener.onMirroringStarted();
            } else {
                Log.i(TAG, "Not approved, staying blank: " + name);
            }
        });
    }

    @Override
    public void onVideo(byte[] bytes) {
        if (!approved) {
            return; // waiting on (or declined) approval; drop frames instead of decoding them
        }
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
        approved = true;
        awaitingApproval = false;
        video.stop();
        listener.onMirroringStopped();
    }

    @Override
    public void onAudioFormat(AudioStreamInfo audioStreamInfo) {
        if (!approved) {
            return;
        }
        Log.i(TAG, "Audio stream: " + audioStreamInfo);
        audio.start(audioStreamInfo, (float) volume);
    }

    @Override
    public void onAudio(byte[] bytes) {
        if (!approved) {
            return;
        }
        audio.feed(bytes, 0, bytes.length);
    }

    @Override
    public void onAudio(byte[] bytes, int offset, int length) {
        if (!approved) {
            return;
        }
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
