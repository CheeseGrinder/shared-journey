package fr.cheesegrinder.sharedjourney.common.util;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Small hashing helpers shared by the region integrity check (client + server). */
public final class Hashing {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Hashing() {}

    /**
     * SHA-256 of the given bytes, as a lowercase hex string. Used to verify
     * the integrity of client-cached region files against the server's
     * record: CRC32 would be forgeable, a cryptographic hash is not.
     */
    public static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] raw = digest.digest(data);
            char[] out = new char[raw.length * 2];
            for (int i = 0; i < raw.length; i++) {
                out[i * 2] = HEX[(raw[i] >> 4) & 0xF];
                out[i * 2 + 1] = HEX[raw[i] & 0xF];
            }

            return new String(out);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JVM spec: cannot happen.
            throw new IllegalStateException(e);
        }
    }
}
