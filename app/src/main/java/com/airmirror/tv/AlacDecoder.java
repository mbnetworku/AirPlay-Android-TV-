package com.airmirror.tv;

/**
 * Pure-Java ALAC (Apple Lossless) decoder for AirPlay's 16-bit mono/stereo audio frames.
 * Exists because this device's only ALAC {@link android.media.MediaCodec} component
 * (OMX.google.alac.decoder) fails {@code native_configure} unconditionally on this
 * firmware, even with a byte-for-byte correct magic cookie — see AudioDecoder's
 * comment history. Decodes the stream ourselves instead of depending on it.
 * <p>
 * Implements adaptive Golomb-Rice residual decoding, sign-sign LMS FIR prediction and
 * mid/side un-mixing, per the published ALAC bitstream algorithm.
 */
final class AlacDecoder {

    private static final int QBSHIFT = 9;
    private static final int QB = 1 << QBSHIFT;
    private static final int PB0 = 40;
    private static final int MB0 = 10;
    private static final int KB0 = 14;
    private static final int MAX_PREFIX = 9;

    private final int channels;
    private final int frameLength;

    AlacDecoder(int channels, int frameLength) {
        this.channels = channels;
        this.frameLength = Math.max(1, frameLength);
    }

    /** Decodes one ALAC frame into interleaved 16-bit PCM; empty array if the frame can't be used. */
    short[] decode(byte[] data) {
        BitReader br = new BitReader(data);
        int[] left = new int[frameLength];
        int[] right = new int[frameLength];

        int tag = br.readBits(3);
        int numSamples;
        switch (tag) {
            case 0: // SCE
            case 3: // LFE
                numSamples = decodeChannelElement(br, left);
                if (numSamples <= 0) {
                    return new short[0];
                }
                short[] mono = new short[numSamples * channels];
                for (int i = 0; i < numSamples; i++) {
                    mono[i * channels] = (short) left[i];
                    if (channels > 1) {
                        mono[i * channels + 1] = (short) left[i];
                    }
                }
                return mono;
            case 1: // CPE
                numSamples = decodeChannelPair(br, left, right);
                if (numSamples <= 0) {
                    return new short[0];
                }
                short[] stereo = new short[numSamples * 2];
                for (int i = 0; i < numSamples; i++) {
                    stereo[i * 2] = (short) left[i];
                    stereo[i * 2 + 1] = (short) right[i];
                }
                return stereo;
            default:
                return new short[0]; // CCE/PCE/DSE/FIL/END as the first tag: nothing to play
        }
    }

    private int decodeChannelPair(BitReader br, int[] outLeft, int[] outRight) {
        br.skipBits(4); // element instance tag
        br.skipBits(12); // unused
        boolean partial = br.readBits(1) != 0;
        int bytesShifted = br.readBits(2);
        boolean escape = br.readBits(1) != 0;
        int numSamples = frameLength;
        if (partial) {
            numSamples = (br.readBits(16) << 16) | br.readBits(16);
        }
        if (bytesShifted == 3 || numSamples <= 0 || numSamples > frameLength) {
            return 0;
        }

        if (escape) {
            for (int i = 0; i < numSamples; i++) {
                outLeft[i] = signExtend(br.readBits(16), 16);
                outRight[i] = signExtend(br.readBits(16), 16);
            }
            return numSamples;
        }
        if (bytesShifted != 0) {
            return 0; // shifted low bits: not used by AirPlay's 16-bit audio, bail safely
        }

        int mixBits = br.readBits(8);
        int mixRes = (byte) br.readBits(8);
        ChannelParams pu = readChannelParams(br);
        ChannelParams pv = readChannelParams(br);

        int chanBits = 17; // 16-bit stream + 1 bit headroom for the mid channel
        int[] resU = new int[numSamples];
        int[] resV = new int[numSamples];
        decodeResiduals(br, resU, numSamples, chanBits, PB0 * pu.pbFactor / 4);
        decodeResiduals(br, resV, numSamples, chanBits, PB0 * pv.pbFactor / 4);

        int[] decU = new int[numSamples];
        int[] decV = new int[numSamples];
        predictInto(resU, decU, numSamples, pu, chanBits);
        predictInto(resV, decV, numSamples, pv, chanBits);

        if (mixRes == 0) {
            System.arraycopy(decU, 0, outLeft, 0, numSamples);
            System.arraycopy(decV, 0, outRight, 0, numSamples);
        } else {
            for (int i = 0; i < numSamples; i++) {
                int right = decU[i] - ((mixRes * decV[i]) >> mixBits);
                outRight[i] = right;
                outLeft[i] = decV[i] + right;
            }
        }
        return numSamples;
    }

    private int decodeChannelElement(BitReader br, int[] out) {
        br.skipBits(4);
        br.skipBits(12);
        boolean partial = br.readBits(1) != 0;
        int bytesShifted = br.readBits(2);
        boolean escape = br.readBits(1) != 0;
        int numSamples = frameLength;
        if (partial) {
            numSamples = (br.readBits(16) << 16) | br.readBits(16);
        }
        if (bytesShifted == 3 || numSamples <= 0 || numSamples > frameLength) {
            return 0;
        }
        if (escape) {
            for (int i = 0; i < numSamples; i++) {
                out[i] = signExtend(br.readBits(16), 16);
            }
            return numSamples;
        }
        if (bytesShifted != 0) {
            return 0;
        }
        br.skipBits(8); // mixBits: meaningless for mono, still present in the bitstream
        br.skipBits(8); // mixRes
        ChannelParams p = readChannelParams(br);
        int chanBits = 16;
        int[] res = new int[numSamples];
        decodeResiduals(br, res, numSamples, chanBits, PB0 * p.pbFactor / 4);
        predictInto(res, out, numSamples, p, chanBits);
        return numSamples;
    }

    private static void predictInto(int[] res, int[] out, int numSamples, ChannelParams p, int chanBits) {
        if (p.mode == 0) {
            predict(res, out, numSamples, p.coefs, p.numActive, chanBits, p.denShift);
        } else {
            int[] tmp = new int[numSamples];
            predict(res, tmp, numSamples, null, 31, chanBits, 0);
            predict(tmp, out, numSamples, p.coefs, p.numActive, chanBits, p.denShift);
        }
    }

    private static final class ChannelParams {
        int mode;
        int denShift;
        int pbFactor;
        int numActive;
        final int[] coefs = new int[32];
    }

    private static ChannelParams readChannelParams(BitReader br) {
        ChannelParams p = new ChannelParams();
        int b1 = br.readBits(8);
        p.mode = (b1 >> 4) & 0xF;
        p.denShift = b1 & 0xF;
        int b2 = br.readBits(8);
        p.pbFactor = (b2 >> 5) & 0x7;
        p.numActive = b2 & 0x1F;
        for (int i = 0; i < p.numActive; i++) {
            p.coefs[i] = (short) br.readBits(16);
        }
        return p;
    }

    /** Adaptive Golomb-Rice residual decode, including the "zero run" fast path for silence. */
    private static void decodeResiduals(BitReader br, int[] out, int numSamples, int chanBits, int mult) {
        int history = MB0;
        int zeroModifier = 0;
        int i = 0;
        while (i < numSamples) {
            int k = Math.min(log2((history >> QBSHIFT) + 3), KB0);
            int x = readEscapedValue(br, k, chanBits);
            int v = x + zeroModifier;
            zeroModifier = 0;
            int magnitude = (v + 1) >> 1;
            out[i++] = (v & 1) != 0 ? -magnitude : magnitude;

            history = (x > 0xFFFF) ? 0xFFFF : history + x * mult - ((history * mult) >> QBSHIFT);

            if (history < (QB >> 2) && i < numSamples) {
                int kk = Math.min(7 - log2(history) + ((history + 16) >> 6), KB0);
                int blockSize = readEscapedValue(br, Math.max(kk, 1), 16);
                if (blockSize > 0) {
                    int n = Math.min(blockSize, numSamples - i);
                    for (int j = 0; j < n; j++) {
                        out[i++] = 0;
                    }
                }
                if (blockSize <= 0xFFFF) {
                    zeroModifier = 1;
                }
                history = 0;
            }
        }
    }

    /** Capped-unary prefix + truncated-binary remainder, with a raw escape past the cap. */
    private static int readEscapedValue(BitReader br, int k, int escapeBits) {
        int unary = 0;
        while (unary < MAX_PREFIX && br.readBits(1) == 1) {
            unary++;
        }
        if (unary >= MAX_PREFIX) {
            return br.readBits(escapeBits);
        }
        if (k <= 1) {
            return unary;
        }
        int m = (1 << k) - 1;
        int extra = br.peekBits(k);
        if (extra > 1) {
            br.skipBits(k);
            return unary * m + extra - 1;
        }
        br.skipBits(k - 1);
        return unary * m;
    }

    /** Sign-sign LMS adaptive FIR predictor decode (or, for numActive==31, a fixed running sum). */
    private static void predict(int[] res, int[] out, int numSamples, int[] coefs, int numActive,
                                 int chanBits, int denShift) {
        if (numSamples <= 0) {
            return;
        }
        out[0] = signExtend(res[0], chanBits);
        if (numActive == 0) {
            for (int i = 1; i < numSamples; i++) {
                out[i] = signExtend(res[i], chanBits);
            }
            return;
        }
        if (numActive == 31) {
            for (int i = 1; i < numSamples; i++) {
                out[i] = signExtend(out[i - 1] + res[i], chanBits);
            }
            return;
        }
        int denHalf = denShift > 0 ? (1 << (denShift - 1)) : 0;
        int warm = Math.min(numActive, numSamples - 1);
        for (int i = 1; i <= warm; i++) {
            out[i] = signExtend(out[i - 1] + res[i], chanBits);
        }
        for (int i = numActive + 1; i < numSamples; i++) {
            int d = out[i - numActive - 1];
            long sum = 0;
            for (int j = 0; j < numActive; j++) {
                sum += (long) (out[i - numActive + j] - d) * coefs[j];
            }
            int predicted = (int) ((sum + denHalf) >> denShift);
            int e = res[i];
            out[i] = signExtend(predicted + d + e, chanBits);

            if (e != 0) {
                int s = e > 0 ? 1 : -1;
                int r = e;
                int j = 0;
                while (j < numActive && s * r > 0) {
                    int t = d - out[i - numActive + j];
                    int g = Integer.signum(t) * s;
                    coefs[j] -= g;
                    r -= ((t * g) >> denShift) * (j + 1);
                    j++;
                }
            }
        }
    }

    private static int log2(int x) {
        return x <= 0 ? 0 : 31 - Integer.numberOfLeadingZeros(x);
    }

    private static int signExtend(int value, int bits) {
        int shift = 32 - bits;
        return (value << shift) >> shift;
    }

    /** MSB-first bit reader over a byte array; reads past the end return 0 rather than throwing. */
    private static final class BitReader {
        private final byte[] data;
        private int bitPos;

        BitReader(byte[] data) {
            this.data = data;
        }

        int readBits(int n) {
            int result = 0;
            for (int i = 0; i < n; i++) {
                result = (result << 1) | readBit();
            }
            return result;
        }

        int peekBits(int n) {
            int save = bitPos;
            int v = readBits(n);
            bitPos = save;
            return v;
        }

        void skipBits(int n) {
            bitPos += n;
        }

        private int readBit() {
            int byteIndex = bitPos >>> 3;
            int bitIndex = 7 - (bitPos & 7);
            bitPos++;
            if (byteIndex < 0 || byteIndex >= data.length) {
                return 0;
            }
            return (data[byteIndex] >>> bitIndex) & 1;
        }
    }
}
