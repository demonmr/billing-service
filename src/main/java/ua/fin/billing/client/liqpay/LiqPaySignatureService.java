package ua.fin.billing.client.liqpay;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Phase 4.3 — LiqPay HMAC-SHA1 signature.
 *
 * <p>LiqPay's signature algorithm (unchanged since 2014):</p>
 * <pre>
 *   signature = base64( SHA1( private_key + base64(json) + private_key ) )
 * </pre>
 *
 * <p>The {@code private_key} is the same string on both sides of the
 * concatenation (no separator). The intermediate {@code data} is the
 * base64-encoded JSON, not the raw JSON — this is a frequent source
 * of off-by-one bugs in custom LiqPay integrations.</p>
 *
 * <p>The signature is used in two places:</p>
 * <ol>
 *   <li><b>Outbound (checkout)</b> — we sign the request payload
 *       before POSTing to {@code https://www.liqpay.ua/api/3/checkout}.
 *       The response carries a {@code data} (base64 of the result
 *       JSON) and {@code signature} field; we re-verify on the
 *       response so the merchant can't be MITM'd.</li>
 *   <li><b>Inbound (webhook)</b> — LiqPay POSTs {@code data} and
 *       {@code signature} in a form-encoded body. We recompute the
 *       signature on the {@code data} and reject on mismatch (400).</li>
 * </ol>
 *
 * <p>The implementation is JDK-only — no third-party crypto deps
 * (the LiqPay SDK on Maven Central pulls a half-megabyte of support
 * libs for ~80 lines of well-documented crypto).</p>
 */
@Component
public class LiqPaySignatureService {

    /**
     * Compute the LiqPay signature for a base64-encoded JSON
     * payload.
     *
     * @param privateKey   the merchant's private key (server-side
     *                     only; never log)
     * @param base64Json   the base64-encoded JSON payload
     * @return the base64-encoded SHA-1 signature, ready to put in
     *         the {@code signature} field
     */
    public String sign(String privateKey, String base64Json) {
        if (privateKey == null || privateKey.isBlank()) {
            throw new IllegalArgumentException("privateKey must not be blank");
        }
        if (base64Json == null) {
            throw new IllegalArgumentException("base64Json must not be null");
        }
        final String concat = privateKey + base64Json + privateKey;
        final byte[] sha1 = sha1(concat.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(sha1);
    }

    /**
     * Verify a webhook signature. Constant-time comparison via
     * {@link MessageDigest#isEqual} so a timing-attack can't
     * extract a valid signature byte-by-byte.
     *
     * @param privateKey     the merchant's private key
     * @param base64Data     the {@code data} field from the webhook
     *                       (base64-encoded JSON)
     * @param base64Signature the {@code signature} field from the
     *                       webhook (base64-encoded SHA-1)
     * @return {@code true} iff the signature is valid
     */
    public boolean verify(String privateKey, String base64Data, String base64Signature) {
        if (privateKey == null || base64Data == null || base64Signature == null) {
            return false;
        }
        final String expected = sign(privateKey, base64Data);
        // Base64 is case-sensitive; lower-casing is the safest cross-
        // runtime comparison (some providers uppercase the output).
        final byte[] a = expected.toLowerCase().getBytes(StandardCharsets.UTF_8);
        final byte[] b = base64Signature.toLowerCase().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }

    private static byte[] sha1(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(input);
        } catch (NoSuchAlgorithmException e) {
            // SHA-1 is a JDK-required algorithm; if it's missing
            // the JVM is broken at a much deeper level.
            throw new IllegalStateException("SHA-1 not available in JVM", e);
        }
    }

    /**
     * Test-only helper — exposed for unit tests that want a
     * hex-encoded digest (for cross-checking against a Python
     * / Node implementation that prints hex by default).
     */
    static String sha1Hex(String input) {
        return HexFormat.of().formatHex(sha1(input.getBytes(StandardCharsets.UTF_8)));
    }
}
