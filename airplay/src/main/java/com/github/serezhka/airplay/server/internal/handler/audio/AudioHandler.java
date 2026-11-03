package com.github.serezhka.airplay.server.internal.handler.audio;

import com.github.serezhka.airplay.protocol.FairPlaySession;
import com.github.serezhka.airplay.server.Playback;
import com.github.serezhka.airplay.server.internal.packet.AudioPacket;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class AudioHandler extends ChannelInboundHandlerAdapter {

    private final FairPlaySession airPlay;
    private final Playback dataConsumer;

    private final AudioPacket[] buffer = new AudioPacket[512];

    /**
     * Packets buffered waiting on one missing sequence number before we give up on it. A real
     * Wi-Fi network can drop a UDP packet outright with no retransmission ever following (this
     * receiver doesn't request one) - without this, a single lost packet stalled every packet
     * behind it forever, since {@link #dequeue} only accepted the exact next sequence number.
     * That silent, permanent stall is what "audio connects, then goes silent" turned out to be.
     */
    private static final int MAX_BACKLOG = 32;

    private int prevSeqNum;
    private boolean started;
    private int packetsInBuffer;
    /** Diagnostic: how many UDP packets this handler has seen, regardless of order/validity. */
    private int packetsSeen;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        AudioPacket packet = (AudioPacket) msg;

        int curSeqNum = packet.getSequenceNumber();
        packetsSeen++;
        if (!started || packetsSeen <= 10 || packetsSeen % 50 == 0) {
            // Diagnostic: confirms whether packets keep arriving at all over time, as opposed to
            // just the first one, which would point at a network-layer cutoff rather than our code.
            log.warn("Audio packet #{} arrived: seq={} size={}", packetsSeen, curSeqNum, packet.getEncodedAudioSize());
        }
        if (started && curSeqNum <= prevSeqNum) {
            return; // old/duplicate packet
        }
        if (!started) {
            // RTP sequence numbers start at an arbitrary value per RFC 3550, not necessarily 1.
            started = true;
            prevSeqNum = curSeqNum - 1;
        }

        buffer[curSeqNum % buffer.length] = packet;
        packetsInBuffer++;

        while (dequeue(prevSeqNum + 1)) {
            // keep draining contiguous packets
        }

        if (packetsInBuffer >= MAX_BACKLOG) {
            skipGap();
            while (dequeue(prevSeqNum + 1)) {
                // drain whatever the skip just made contiguous
            }
        }
    }

    private boolean dequeue(int curSeqNo) throws Exception {
        AudioPacket audioPacket = buffer[curSeqNo % buffer.length];
        if (audioPacket != null && audioPacket.isAvailable()) {
            int size = audioPacket.getEncodedAudioSize();
            try {
                airPlay.decryptAudio(audioPacket.getEncodedAudio(), size);
                // Pass offset/length so consumers can avoid an extra full-buffer copy.
                dataConsumer.onAudio(audioPacket.getEncodedAudio(), 0, size);
            } catch (Exception e) {
                // A Netty UDP handler's exceptions don't propagate anywhere visible by default -
                // without this, a decrypt/decode failure here would look identical to no packets
                // arriving at all.
                log.error("Audio packet seq={} size={} failed to decrypt/decode", curSeqNo, size, e);
            }
            audioPacket.available(false);
            prevSeqNum = curSeqNo;
            packetsInBuffer--;
            return true;
        }
        return false;
    }

    /** Gives up on the missing packet at prevSeqNum+1 and fast-forwards to the oldest one we have. */
    private void skipGap() {
        for (int seq = prevSeqNum + 1; seq <= prevSeqNum + buffer.length; seq++) {
            AudioPacket p = buffer[seq % buffer.length];
            if (p != null && p.isAvailable()) {
                prevSeqNum = seq - 1;
                return;
            }
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        // Diagnostic: a Netty pipeline exception (e.g. in the RTP/decrypt decoder upstream of
        // this handler) otherwise never shows up under this app's own log tags.
        log.error("Audio channel pipeline exception", cause);
    }
}
