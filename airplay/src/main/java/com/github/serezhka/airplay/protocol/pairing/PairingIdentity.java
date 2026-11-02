package com.github.serezhka.airplay.protocol.pairing;

import net.i2p.crypto.eddsa.EdDSAPrivateKey;
import net.i2p.crypto.eddsa.EdDSAPublicKey;
import net.i2p.crypto.eddsa.KeyPairGenerator;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable;
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec;
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec;

import java.security.KeyPair;
import java.security.SecureRandom;


/**
 * Receiver Ed25519 identity for one AirPlay process (legacy FairPlay pair-setup / pair-verify).
 * The public key is what mDNS advertises as {@code pk} and what {@code /pair-setup} returns.
 * <p>
 * Not the HKP controller identity saved by the cast client ({@code pairing_id} + seed) —
 * that lives in the client credential store.
 */
public final class PairingIdentity {

    private final KeyPair keyPair;

    private PairingIdentity(KeyPair keyPair) {
        this.keyPair = keyPair;
    }

    public static PairingIdentity generate() {
        return new PairingIdentity(new KeyPairGenerator().generateKeyPair());
    }

    /**
     * Rebuilds the exact same identity (same keypair, same public key) from a 32-byte seed
     * saved earlier with {@link #newSeed()} — so the receiver presents a stable identity across
     * restarts instead of a fresh random one every time, which breaks a phone's existing pairing.
     */
    public static PairingIdentity fromSeed(byte[] seed) {
        var params = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519);
        var privateSpec = new EdDSAPrivateKeySpec(seed, params);
        var privateKey = new EdDSAPrivateKey(privateSpec);
        var publicSpec = new EdDSAPublicKeySpec(privateSpec.getA(), params);
        var publicKey = new EdDSAPublicKey(publicSpec);
        return new PairingIdentity(new KeyPair(publicKey, privateKey));
    }

    /** A fresh random 32-byte seed for {@link #fromSeed(byte[])}, to be saved by the caller. */
    public static byte[] newSeed() {
        byte[] seed = new byte[32];
        new SecureRandom().nextBytes(seed);
        return seed;
    }

    public byte[] publicKey() {
        return ((EdDSAPublicKey) keyPair.getPublic()).getAbyte();
    }

    public String publicKeyHex() {
        return toHex(publicKey());
    }

    KeyPair keyPair() {
        return keyPair;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
