package com.airmirror.tv;

import android.media.MediaPlayer;
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
    private volatile Surface surface;
    private volatile MediaPlayer vodPlayer;
    private volatile boolean vodActive;

    public MirrorPlayback(Listener listener) {
        this.listener = listener;
        video.setSizeListener((w, h) -> {
            listener.onVideoSize(w, h);
            observer.onPresented();
        });
    }

    public void setSurface(Surface surface) {
        this.surface = surface;
        video.setSurface(surface);
        MediaPlayer mp = vodPlayer;
        if (mp != null && surface != null) {
            try {
                mp.setSurface(surface);
            } catch (Exception e) {
                Log.w(TAG, "Could not hand the surface to the video player", e);
            }
        }
    }

    public void release() {
        video.stop();
        audio.stop();
        releaseVodPlayer();
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
        if (videoActive || vodActive || awaitingApproval) {
            // The audio half of a mirroring or video-cast session already gated via its video.
            if (!approved) {
                return;
            }
            Log.i(TAG, "Audio stream: " + audioStreamInfo);
            audio.start(audioStreamInfo, (float) volume);
            return;
        }
        // A standalone "share audio to this TV as a speaker" connection (Control Center), with
        // no video of its own to gate it — ask (or auto-pass if already approved) on its own.
        ApprovalGate gate = approvalGate;
        if (gate == null) {
            Log.i(TAG, "Audio stream: " + audioStreamInfo);
            audio.start(audioStreamInfo, (float) volume);
            return;
        }
        String name = clientName;
        gate.requestApproval(name, ok -> {
            if (ok) {
                Log.i(TAG, "Audio stream (speaker cast): " + audioStreamInfo);
                audio.start(audioStreamInfo, (float) volume);
            } else {
                Log.i(TAG, "Speaker cast not approved: " + name);
            }
        });
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

    // --- Single-video casting: the AirPlay button inside Safari/YouTube/etc, not screen mirroring ---

    @Override
    public void onPlaylist(String playlistUri) {
        Log.i(TAG, "Cast video: " + playlistUri + " from " + clientName);
        ApprovalGate gate = approvalGate;
        if (gate == null) {
            startVodPlayback(playlistUri);
            return;
        }
        String name = clientName;
        gate.requestApproval(name, ok -> {
            if (ok) {
                startVodPlayback(playlistUri);
            } else {
                Log.i(TAG, "Cast not approved: " + name);
            }
        });
    }

    private void startVodPlayback(String playlistUri) {
        releaseVodPlayer();
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setDataSource(playlistUri);
            Surface s = surface;
            if (s != null) {
                mp.setSurface(s);
            }
            mp.setOnPreparedListener(p -> {
                try {
                    p.start();
                } catch (Exception e) {
                    Log.w(TAG, "Video player would not start", e);
                }
                vodActive = true;
                listener.onMirroringStarted();
                observer.onPresented();
            });
            mp.setOnVideoSizeChangedListener((p, w, h) -> {
                if (w > 0 && h > 0) {
                    listener.onVideoSize(w, h);
                }
            });
            mp.setOnCompletionListener(p -> observer.onEnded());
            mp.setOnSeekCompleteListener(p -> observer.onSeekDisplayed());
            mp.setOnErrorListener((p, what, extra) -> {
                Log.e(TAG, "Video player error " + what + "/" + extra + " for " + playlistUri);
                return true; // we handled it; suppresses the default "unknown error" dialog-less crash path
            });
            mp.prepareAsync();
            vodPlayer = mp;
        } catch (Exception e) {
            Log.e(TAG, "Cannot start video casting for " + playlistUri, e);
        }
    }

    @Override
    public void onPlaylistRemoved() {
        Log.i(TAG, "Cast playback removed");
        releaseVodPlayer();
        listener.onMirroringStopped();
    }

    @Override
    public void onPause() {
        MediaPlayer mp = vodPlayer;
        if (mp != null) {
            try {
                mp.pause();
            } catch (Exception e) {
                Log.w(TAG, "Could not pause the video player", e);
            }
        }
    }

    @Override
    public void onResume() {
        MediaPlayer mp = vodPlayer;
        if (mp != null) {
            try {
                mp.start();
            } catch (Exception e) {
                Log.w(TAG, "Could not resume the video player", e);
            }
        }
    }

    @Override
    public void onSeek(double positionSeconds) {
        MediaPlayer mp = vodPlayer;
        if (mp != null) {
            try {
                mp.seekTo((int) (positionSeconds * 1000));
            } catch (Exception e) {
                Log.w(TAG, "Could not seek the video player", e);
            }
        }
    }

    @Override
    public Info info() {
        MediaPlayer mp = vodPlayer;
        if (mp == null || !vodActive) {
            return Info.idle();
        }
        try {
            double duration = Math.max(0, mp.getDuration()) / 1000.0;
            double position = Math.max(0, mp.getCurrentPosition()) / 1000.0;
            double rate = mp.isPlaying() ? 1 : 0;
            return new Info(duration, position, rate);
        } catch (Exception e) {
            return Info.idle();
        }
    }

    private void releaseVodPlayer() {
        MediaPlayer mp = vodPlayer;
        vodPlayer = null;
        vodActive = false;
        if (mp != null) {
            try {
                mp.reset();
            } catch (Exception ignored) {
                // player was in a state that doesn't allow reset; release() below still works
            }
            try {
                mp.release();
            } catch (Exception e) {
                Log.w(TAG, "Error releasing the video player", e);
            }
        }
    }
}
