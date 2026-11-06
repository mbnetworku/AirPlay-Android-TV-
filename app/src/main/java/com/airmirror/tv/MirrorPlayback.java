package com.airmirror.tv;

import android.media.MediaPlayer;
import android.util.Log;
import android.view.Surface;

import com.github.serezhka.airplay.protocol.media.AudioStreamInfo;
import com.github.serezhka.airplay.protocol.media.VideoStreamInfo;
import com.github.serezhka.airplay.server.ControlExchange;
import com.github.serezhka.airplay.server.Playback;

import java.util.function.BooleanSupplier;
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
    private volatile BooleanSupplier otherSourceBusy = () -> false;

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

    /**
     * True while an Android phone is sharing its screen: AirPlay video then stays blank until
     * that phone stops (one screen, one sender at a time). Has no effect otherwise.
     */
    public void setOtherSourceBusy(BooleanSupplier busy) {
        this.otherSourceBusy = busy == null ? () -> false : busy;
    }

    private boolean refusedBecauseBusy(String what) {
        if (!otherSourceBusy.getAsBoolean()) {
            return false;
        }
        Log.i(TAG, what + " from " + clientName + " refused: an Android phone is sharing");
        return true;
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
        if (refusedBecauseBusy("Mirroring")) {
            approved = false; // drop its frames until it disconnects (reset there)
            return;
        }
        ApprovalGate gate = approvalGate;
        if (gate == null) {
            beginMirroring();
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
                beginMirroring();
            } else {
                Log.i(TAG, "Not approved, staying blank: " + name);
            }
        });
    }

    /**
     * Mirroring doesn't always go through {@link #onVideoFormat}; some senders go straight to
     * {@link #onVideo}, which starts a session itself the same way. Both paths call this, so the
     * surface handoff away from video-casting happens no matter which one a given sender uses.
     */
    private void beginMirroring() {
        // Mirroring and video-casting can't both hold the screen surface at once; let go of
        // whichever one the previous session was using before this one tries to grab it.
        releaseVodPlayer();
        videoActive = true;
        listener.onMirroringStarted();
    }

    @Override
    public void onVideo(byte[] bytes) {
        if (!approved) {
            return; // waiting on (or declined) approval; drop frames instead of decoding them
        }
        if (!videoActive) {
            if (refusedBecauseBusy("Mirroring")) {
                approved = false;
                return;
            }
            beginMirroring();
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
        if (refusedBecauseBusy("Video cast")) {
            return;
        }
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
        // Same reasoning in the other direction: let go of the mirroring decoder's hold on the
        // surface before the video player tries to take it.
        video.stop();
        // On this hardware the just-released decoder's hold on the Surface can take a moment to
        // actually let go, the same race VideoDecoder.createCodec retries for. A MediaPlayer that
        // hits that race here throws from setSurface()/prepareAsync() rather than failing softly,
        // so give it the same bounded retry.
        for (int attempt = 1; attempt <= 2; attempt++) {
            if (attemptStartVod(playlistUri)) {
                return;
            }
            if (attempt < 2) {
                Log.w(TAG, "Retrying video cast start, previous surface owner may still be releasing it");
                try {
                    Thread.sleep(150);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** @return true if the player started preparing; false means the caller may retry. */
    private boolean attemptStartVod(String playlistUri) {
        MediaPlayer mp = new MediaPlayer();
        try {
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
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Cannot start video casting for " + playlistUri, e);
            // Unlike the success path, nothing else holds a reference to mp here - leaving it
            // unreleased would leak its hold on the Surface and jam every mirroring/casting
            // attempt after it until the app restarts.
            try {
                mp.reset();
            } catch (Exception ignored) {
            }
            try {
                mp.release();
            } catch (Exception ignored) {
            }
            return false;
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
