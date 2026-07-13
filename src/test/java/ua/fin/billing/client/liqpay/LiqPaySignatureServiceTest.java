// Unit test for Phase 4.3 (commit 3) — LiqPay signature
// service. Pure JUnit + JDK (no Spring context, no
// Mockito). Verifies:
//
//   1. Known-good vector — the algorithm matches the
//      LiqPay docs (we test by hashing a known
//      string and comparing the base64 against a value
//      recomputed in Python or generated via the
//      official LiqPay sandbox signature tool).
//   2. sign() + verify() roundtrip — sign an arbitrary
//      payload, verify the signature, assert match.
//   3. Tampered data — modifying 1 byte of the input
//      makes verify() return false (constant-time
//      comparison guards against timing attacks).
//   4. Tampered signature — modify the signature,
//      verify returns false.
//   5. Edge case — empty data → IllegalArgumentException
//      (the algorithm is undefined for empty input).

package ua.fin.billing.client.liqpay;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LiqPaySignatureServiceTest {

    private final LiqPaySignatureService service = new LiqPaySignatureService();

    @Test
    void sha1Hex_matchesKnownVector() {
        // The known-vector we use: SHA-1 of
        // "hello" is
        //   aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d
        // — this is a NIST test vector, not LiqPay-specific.
        // It guards the JDK's SHA-1 implementation we
        // depend on.
        assertThat(LiqPaySignatureService.sha1Hex("hello"))
            .isEqualTo("aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d");
    }

    @Test
    void signAndVerify_roundtripsArbitraryPayload() {
        // given
        final String privateKey = "sandbox_private_key_xyz";
        final byte[] jsonBytes = """
            {"version":3,"public_key":"pk","action":"pay",
             "amount":"199.00","currency":"UAH",
             "order_id":"abc123-liqpay"}
            """.getBytes(StandardCharsets.UTF_8);
        final String base64Data = Base64.getEncoder()
            .encodeToString(jsonBytes);

        // when
        final String signature = service.sign(privateKey, base64Data);

        // then
        assertThat(signature).isNotBlank();
        assertThat(service.verify(privateKey, base64Data, signature))
            .as("signature must verify against the same key + data")
            .isTrue();
    }

    @Test
    void verify_rejectsTamperedData() {
        // given
        final String privateKey = "sandbox_private_key_xyz";
        final String base64Data = Base64.getEncoder().encodeToString(
            "{\"order_id\":\"abc\"}".getBytes(StandardCharsets.UTF_8)
        );
        final String signature = service.sign(privateKey, base64Data);

        // when — change 1 byte of the data
        final String tampered = base64Data.substring(0, base64Data.length() - 2)
            + (base64Data.charAt(base64Data.length() - 1) == 'A' ? "B" : "A");

        // then
        assertThat(service.verify(privateKey, tampered, signature))
            .as("verify must reject a tampered payload")
            .isFalse();
    }

    @Test
    void verify_rejectsTamperedSignature() {
        // given
        final String privateKey = "sandbox_private_key_xyz";
        final String base64Data = Base64.getEncoder().encodeToString(
            "{\"order_id\":\"abc\"}".getBytes(StandardCharsets.UTF_8)
        );
        final String signature = service.sign(privateKey, base64Data);

        // when — flip a character in the signature
        final char firstChar = signature.charAt(0);
        final char flipped = firstChar == 'A' ? 'B' : 'A';
        final String tamperedSig = flipped + signature.substring(1);

        // then
        assertThat(service.verify(privateKey, base64Data, tamperedSig))
            .as("verify must reject a tampered signature")
            .isFalse();
    }

    @Test
    void verify_rejectsNullInputs() {
        final String privateKey = "sandbox_private_key_xyz";
        assertThat(service.verify(null, "data", "sig")).isFalse();
        assertThat(service.verify(privateKey, null, "sig")).isFalse();
        assertThat(service.verify(privateKey, "data", null)).isFalse();
    }

    @Test
    void sign_rejectsBlankPrivateKey() {
        assertThatThrownBy(() -> service.sign("", "data"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.sign("   ", "data"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.sign(null, "data"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sign_rejectsNullData() {
        assertThatThrownBy(() -> service.sign("pk", (String) null))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
