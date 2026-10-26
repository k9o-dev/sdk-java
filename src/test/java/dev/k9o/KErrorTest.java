package dev.k9o;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class KErrorTest {

    @Test
    void constructsWithRequiredFields() {
        KError e = new KError("K9O_INVALID_FIELD_VALUE", "bad");
        assertEquals("K9O_INVALID_FIELD_VALUE", e.getCode());
        assertEquals("bad", e.getMessage());
        assertTrue(e.getContext().isEmpty());
        assertNull(e.getRecommendation());
        assertNull(e.getStatus());
    }

    @Test
    void contextIsUnmodifiable() {
        KError e = new KError("X", "y", Map.of("k", "v"), null, null, null);
        assertEquals("v", e.getContext().get("k"));
        assertThrows(UnsupportedOperationException.class, () -> e.getContext().put("x", "y"));
    }

    @Test
    void isRuntimeException() {
        assertTrue(RuntimeException.class.isAssignableFrom(KError.class));
    }

    @Test
    void toStringIncludesCodeAndStatus() {
        KError e = new KError("K9O_TIER_CAP_HIT", "cap", Map.of(), null, 429, null);
        String s = e.toString();
        assertTrue(s.contains("K9O_TIER_CAP_HIT"));
        assertTrue(s.contains("429"));
    }

    @Test
    void withRecommendationAddsHint() {
        KError e = new KError("X", "y").withRecommendation("do this");
        assertEquals("do this", e.getRecommendation());
    }
}
