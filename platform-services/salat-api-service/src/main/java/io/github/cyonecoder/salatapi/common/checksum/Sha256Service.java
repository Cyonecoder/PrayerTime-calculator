package io.github.cyonecoder.salatapi.common.checksum;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.stereotype.Component;

/**
 * SHA-256 hex checksums for raw official pages and archive integrity.
 *
 * <p>The checksum is what makes an official page's identity verifiable: the raw
 * snapshot store uses it to decide whether an incoming page is new, unchanged, or
 * a revision, and it is recorded as provenance on every derived record.
 *
 * <p>Ported from {@code HabousSnapshotStore.sha256Hex}; see
 * {@code docs/EXTRACTION_MAP.md}.
 */
@Component
public class Sha256Service {

    /**
     * @param text the content to digest (hashed as UTF-8)
     * @return the lowercase 64-character hex SHA-256
     */
    public String hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Static form, for value types that cannot take an injected collaborator. */
    public static String sha256Hex(String text) {
        return new Sha256Service().hex(text);
    }
}
