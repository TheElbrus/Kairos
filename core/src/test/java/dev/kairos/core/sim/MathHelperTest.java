package dev.kairos.core.sim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Identities and invariants of the mirrored sine/cosine table (task 1.6):
 * MathHelper.java:17, 29-38, 493-499. Decimal table values are deliberately NOT pinned
 * here: they depend on the JDK's {@link Math#sin} implementation, and the live golden
 * traces are the exact check (AGENTS.md: the real game is the judge).
 */
class MathHelperTest {

    private static final float[] ANGLES = {33.3f, 90.0f, 161.9f, -33.3f};

    @Test
    void zeroIsExact() {
        assertEquals(0, Float.compare(MathHelper.sin(0.0f), 0.0f),
                "sin(0) must be +0.0");
        assertEquals(0, Float.compare(MathHelper.cos(0.0f), 1.0f),
                "cos(0) must be exactly 1.0");
    }

    @Test
    void sinAndCosAreFiniteAndWithinTheUnitRange() {
        for (float angle : ANGLES) {
            assertFiniteInRange("sin(" + angle + ")", MathHelper.sin(angle));
            assertFiniteInRange("cos(" + angle + ")", MathHelper.cos(angle));
        }
    }

    @Test
    void repeatedCallsReturnTheSameValue() {
        for (float angle : ANGLES) {
            assertEquals(0, Float.compare(MathHelper.sin(angle), MathHelper.sin(angle)),
                    "sin must be deterministic at " + angle);
            assertEquals(0, Float.compare(MathHelper.cos(angle), MathHelper.cos(angle)),
                    "cos must be deterministic at " + angle);
        }
    }

    private static void assertFiniteInRange(String what, float value) {
        assertTrue(Float.isFinite(value), what + " must be finite, got " + value);
        assertTrue(value >= -1.0f && value <= 1.0f,
                what + " must be within [-1, 1], got " + value);
    }
}
