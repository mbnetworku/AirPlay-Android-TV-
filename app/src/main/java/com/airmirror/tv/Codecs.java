package com.airmirror.tv;

import android.media.MediaCodec;
import android.os.Build;
import android.os.Handler;

import java.util.concurrent.FutureTask;

/** MediaCodec helpers that work back to Android 5.0. */
final class Codecs {

    private Codecs() {
    }

    /**
     * Creates the decoder on the handler's thread and attaches the callback there.
     * Before Android 6 {@code setCallback} has no handler overload: callbacks go to the
     * looper of the thread that created the codec.
     */
    static MediaCodec createDecoder(String mime, MediaCodec.Callback callback, Handler handler) throws Exception {
        FutureTask<MediaCodec> task = new FutureTask<>(() -> MediaCodec.createDecoderByType(mime));
        handler.post(task);
        MediaCodec codec = task.get();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            codec.setCallback(callback, handler);
        } else {
            codec.setCallback(callback);
        }
        return codec;
    }
}
