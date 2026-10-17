package dev.kairos.core.trace;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the exact bytes of a trace line (ADR 0010), including the tick -1 start-state line.
 *
 * <p>{@code te} is RELATIVE to the anchor: the {@code tick:-1} line has {@code te = 0},
 * then {@code 1}, {@code 2}, ... The recorder subtracts the absolute {@code ticksExisted}
 * captured at the anchor, so two runs of the same scenario produce identical bytes even
 * though the absolute tick count differs. The gap guard still uses the absolute value.
 *
 * <p>Why only these numeric values: {@code Double.toString} and {@code Float.toString} do
 * not guarantee identical output across JDK versions for every value -- Java 19+ switched
 * to the shortest round-trip algorithm (JDK-4511638), so a value that formats one way on
 * the Java 8 that runs the game could format differently on the JDK that runs the tests.
 * The values here ({@code 0.1 + 0.2}, {@code -0.0}, {@code 0.5}, {@code 65.0}, the float
 * {@code 33.3f}) are stable on both. That is safe because later trace comparison parses
 * numbers instead of comparing strings.
 */
class TraceWriterTest {

    @Test
    void writesTheDocumentedLineForAFixedInput() {
        TraceLine line = new TraceLine(
                "1.8.9", "sprintjump_flat_01", 0,
                TraceInput.fromAxes(1, 0, 1, 33.3f, 0.0f, 1.0f, -1.0f),
                0.5, 65.0, 0.1 + 0.2,
                -0.0, 0.0, 0.5,
                true, true, false, false, false, 1);

        String expected = "{\"v\":1,\"mc\":\"1.8.9\",\"scenario\":\"sprintjump_flat_01\","
                + "\"tick\":0,"
                + "\"in\":{\"w\":1,\"a\":0,\"s\":0,\"d\":1,\"jump\":1,\"sneak\":0,\"sprint\":1,"
                + "\"yaw\":33.3,\"pitch\":0.0,\"mf\":1.0,\"ms\":-1.0},"
                + "\"pos\":[0.5,65.0,0.30000000000000004],"
                + "\"vel\":[-0.0,0.0,0.5],"
                + "\"onGround\":true,\"sprinting\":true,\"using\":false,"
                + "\"colH\":false,\"colV\":false,\"te\":1}";

        assertEquals(expected, TraceWriter.toJsonLine(line));
    }

    @Test
    void writesTheTickMinusOneStartStateLineWithZeroInput() {
        TraceLine line = new TraceLine(
                "1.8.9", "test1", -1, TraceInput.zero(),
                0.5, 4.0, 0.5,
                0.0, 0.0, 0.0,
                false, false, false, false, false, 0);

        String expected = "{\"v\":1,\"mc\":\"1.8.9\",\"scenario\":\"test1\",\"tick\":-1,"
                + "\"in\":{\"w\":0,\"a\":0,\"s\":0,\"d\":0,\"jump\":0,\"sneak\":0,\"sprint\":0,"
                + "\"yaw\":0.0,\"pitch\":0.0,\"mf\":0.0,\"ms\":0.0},"
                + "\"pos\":[0.5,4.0,0.5],\"vel\":[0.0,0.0,0.0],"
                + "\"onGround\":false,\"sprinting\":false,\"using\":false,"
                + "\"colH\":false,\"colV\":false,\"te\":0}";

        assertEquals(expected, TraceWriter.toJsonLine(line));
    }

    @Test
    void leftIsPositiveStrafeAndRightIsNegative() {
        // MovementInputFromOptions.updatePlayerMoveState(): left -> moveStrafe += 1 (line 31),
        // right -> moveStrafe -= 1 (line 34).
        TraceInput left = TraceInput.fromAxes(0, 0, 0, 0.0f, 0.0f, 0.0f, 1.0f);
        assertEquals(1, left.a);
        assertEquals(0, left.d);

        TraceInput right = TraceInput.fromAxes(0, 0, 0, 0.0f, 0.0f, 0.0f, -1.0f);
        assertEquals(0, right.a);
        assertEquals(1, right.d);

        // Both opposite keys held cancel out to 0.0f, so both signs are 0.
        TraceInput cancelled = TraceInput.fromAxes(0, 0, 0, 0.0f, 0.0f, 0.0f, 0.0f);
        assertEquals(0, cancelled.a);
        assertEquals(0, cancelled.d);
        assertEquals(0, cancelled.w);
        assertEquals(0, cancelled.s);
    }

    @Test
    void refusesToEmitNanAndInfinity() {
        // NaN is not valid JSON, so the writer must throw rather than write "NaN".
        IllegalArgumentException nanPos = assertThrows(IllegalArgumentException.class,
                () -> TraceWriter.toJsonLine(lineWith(Double.NaN, 0.0f, 0.0f)));
        assertTrue(nanPos.getMessage().contains("pos[0]"));

        assertThrows(IllegalArgumentException.class,
                () -> TraceWriter.toJsonLine(lineWith(Double.POSITIVE_INFINITY, 0.0f, 0.0f)));
        assertThrows(IllegalArgumentException.class,
                () -> TraceWriter.toJsonLine(lineWith(Double.NEGATIVE_INFINITY, 0.0f, 0.0f)));
        assertThrows(IllegalArgumentException.class,
                () -> TraceWriter.toJsonLine(lineWith(0.0, Float.NaN, 0.0f)));
        assertThrows(IllegalArgumentException.class,
                () -> TraceWriter.toJsonLine(lineWith(0.0, 0.0f, Float.POSITIVE_INFINITY)));
    }

    @Test
    void rejectsNonBooleanFlags() {
        assertThrows(IllegalArgumentException.class,
                () -> new TraceInput(2, 0, 0, 0, 0, 0, 0, 0.0f, 0.0f, 0.0f, 0.0f));
        assertThrows(IllegalArgumentException.class,
                () -> new TraceInput(0, 0, 0, 0, -1, 0, 0, 0.0f, 0.0f, 0.0f, 0.0f));
    }

    @Test
    void escapesScenarioNames() {
        TraceLine line = new TraceLine(
                "1.8.9", "a\"b\\c", 0, TraceInput.zero(),
                0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
                false, false, false, false, false, 0);

        assertTrue(TraceWriter.toJsonLine(line).contains("\"scenario\":\"a\\\"b\\\\c\""));
    }

    /** Builds a line with the given pos[0] and yaw/mf values, everything else fixed. */
    private static TraceLine lineWith(double posX, float yaw, float mf) {
        return new TraceLine(
                "1.8.9", "test1", 0,
                new TraceInput(0, 0, 0, 0, 0, 0, 0, yaw, 0.0f, mf, 0.0f),
                posX, 0.0, 0.0, 0.0, 0.0, 0.0,
                false, false, false, false, false, 0);
    }
}
