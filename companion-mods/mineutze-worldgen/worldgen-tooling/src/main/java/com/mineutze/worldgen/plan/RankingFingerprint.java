package com.mineutze.worldgen.plan;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 of the saved ranking's text: picks made on a ranking a newer Solve check replaced are refused. */
final class RankingFingerprint {

    private RankingFingerprint() {
    }

    static String of(String rankingText) {
        return HexFormat.of().formatHex(sha256().digest(rankingText.getBytes(StandardCharsets.UTF_8)));
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
