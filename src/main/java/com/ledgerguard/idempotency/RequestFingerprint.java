package com.ledgerguard.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;

/**
 * Turns a request into a stable 64-character hex digest, so that reusing an
 * idempotency key for a <em>different</em> request can be detected rather than
 * assumed away.
 *
 * <p>The body is canonicalised before hashing: every JSON object has its keys
 * sorted recursively. Key order therefore cannot change the fingerprint, and
 * that property is structural rather than a convention callers have to follow.
 *
 * <p><b>A deliberate limitation.</b> {@code "4.00"} and {@code "4.0"} produce
 * different fingerprints and so conflict, even though they are the same amount
 * of money. Treating differing representations as equivalent means guessing at
 * intent, and guessing wrong means accepting a genuinely different request
 * under an already-used key. Refusing is the safe direction to be wrong in.
 */
@Component
public class RequestFingerprint {

    private final ObjectMapper mapper;

    public RequestFingerprint(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * @param method HTTP method, e.g. {@code POST}
     * @param path   the concrete path including ids, e.g. {@code /payments/abc/refunds}
     * @param body   the parsed request object, or {@code null} for a bodyless request
     */
    public String of(String method, String path, Object body) {
        String canonicalBody = canonicalise(body);
        String material = method + "\n" + path + "\n" + canonicalBody;
        return sha256Hex(material);
    }

    /** The canonical JSON form used for hashing. Exposed so tests can assert on it directly. */
    public String canonicalise(Object body) {
        if (body == null) {
            return "null";
        }
        try {
            JsonNode tree = mapper.valueToTree(body);
            return mapper.writeValueAsString(sortRecursively(tree));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("request body could not be canonicalised for fingerprinting", e);
        }
    }

    private JsonNode sortRecursively(JsonNode node) {
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
                names.add(it.next());
            }
            Collections.sort(names);

            ObjectNode sorted = mapper.createObjectNode();
            for (String name : names) {
                sorted.set(name, sortRecursively(node.get(name)));
            }
            return sorted;
        }
        if (node.isArray()) {
            // Array order is meaningful and is left alone: [a, b] is not [b, a].
            ArrayNode copy = mapper.createArrayNode();
            node.forEach(element -> copy.add(sortRecursively(element)));
            return copy;
        }
        return node;
    }

    private static String sha256Hex(String material) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }
}
