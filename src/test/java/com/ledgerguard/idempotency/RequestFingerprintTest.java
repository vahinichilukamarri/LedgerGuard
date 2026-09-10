package com.ledgerguard.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fingerprint is what makes "same key, different request" detectable rather
 * than assumed. These tests pin the two properties that matter: JSON key order
 * must not change it, and anything else about the request must.
 */
class RequestFingerprintTest {

    private final RequestFingerprint fingerprints = new RequestFingerprint(new ObjectMapper());

    private static Map<String, Object> ordered(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k1, v1);
        m.put(k2, v2);
        return m;
    }

    @Test
    @DisplayName("JSON key order does not change the fingerprint")
    void keyOrderIsIrrelevant() {
        Map<String, Object> oneWay = ordered("amount", "4.00", "description", "a refund");
        Map<String, Object> otherWay = ordered("description", "a refund", "amount", "4.00");

        assertThat(fingerprints.of("POST", "/payments", oneWay))
                .isEqualTo(fingerprints.of("POST", "/payments", otherWay));
    }

    @Test
    @DisplayName("key order deep inside a nested object is also irrelevant")
    void nestedKeyOrderIsIrrelevant() {
        Map<String, Object> a = Map.of("outer", ordered("x", 1, "y", 2));
        Map<String, Object> b = Map.of("outer", ordered("y", 2, "x", 1));

        assertThat(fingerprints.of("POST", "/payments", a))
                .isEqualTo(fingerprints.of("POST", "/payments", b));
    }

    @Test
    @DisplayName("the canonical form itself has sorted keys")
    void canonicalFormIsSorted() {
        String canonical = fingerprints.canonicalise(ordered("zebra", 1, "apple", 2));
        assertThat(canonical).isEqualTo("{\"apple\":2,\"zebra\":1}");
    }

    @Test
    @DisplayName("array order IS significant, because it carries meaning")
    void arrayOrderMatters() {
        assertThat(fingerprints.of("POST", "/x", Map.of("legs", List.of("a", "b"))))
                .isNotEqualTo(fingerprints.of("POST", "/x", Map.of("legs", List.of("b", "a"))));
    }

    @Test
    @DisplayName("a different value gives a different fingerprint")
    void differentValueDiffers() {
        assertThat(fingerprints.of("POST", "/payments", Map.of("amount", "4.00")))
                .isNotEqualTo(fingerprints.of("POST", "/payments", Map.of("amount", "5.00")));
    }

    @Test
    @DisplayName("the same body at a different path is a different request")
    void pathIsPartOfTheFingerprint() {
        Map<String, Object> body = Map.of("amount", "4.00");
        assertThat(fingerprints.of("POST", "/payments/aaa/refunds", body))
                .isNotEqualTo(fingerprints.of("POST", "/payments/bbb/refunds", body));
    }

    @Test
    @DisplayName("the method is part of the fingerprint")
    void methodIsPartOfTheFingerprint() {
        Map<String, Object> body = Map.of("amount", "4.00");
        assertThat(fingerprints.of("POST", "/payments", body))
                .isNotEqualTo(fingerprints.of("PUT", "/payments", body));
    }

    @Test
    @DisplayName("a bodyless request still fingerprints, and stably")
    void nullBodyIsStable() {
        String first = fingerprints.of("POST", "/transactions/abc/reversals", null);
        String second = fingerprints.of("POST", "/transactions/abc/reversals", null);

        assertThat(first).isEqualTo(second);
        assertThat(fingerprints.canonicalise(null)).isEqualTo("null");
    }

    @Test
    @DisplayName("the digest is 64 lowercase hex characters, matching the column constraint")
    void digestShapeMatchesTheSchema() {
        assertThat(fingerprints.of("POST", "/payments", Map.of("amount", "1.00")))
                .matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("differing decimal scale is treated as a different request, deliberately")
    void decimalScaleIsSignificant() {
        // "4.00" and "4.0" are the same money but not the same bytes. Treating
        // them as equivalent would mean guessing at intent, and guessing wrong
        // means honouring a genuinely different request under a used key.
        assertThat(fingerprints.of("POST", "/payments", Map.of("amount", "4.00")))
                .isNotEqualTo(fingerprints.of("POST", "/payments", Map.of("amount", "4.0")));
    }
}
