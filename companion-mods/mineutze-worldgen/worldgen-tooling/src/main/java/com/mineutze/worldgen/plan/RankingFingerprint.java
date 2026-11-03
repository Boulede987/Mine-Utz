package com.mineutze.worldgen.plan;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 of the saved ranking's text and the graph version it is read against: picks made on a ranking a newer Solve
 * check replaced, or before a graph edit (which drops laws, so ranks shift), are refused.
 */
final class RankingFingerprint {

    private RankingFingerprint() {
    }

    static String of(String rankingText, String graphFingerprint) {
        MessageDigest digest = sha256();
        digest.update(rankingText.getBytes(StandardCharsets.UTF_8));
        digest.update(graphFingerprint.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Every JVM must provide SHA-256: its absence is a broken runtime, not a case to handle. */
    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("RankingFingerprint: this JVM has no SHA-256", missing);
        }
    }
}
