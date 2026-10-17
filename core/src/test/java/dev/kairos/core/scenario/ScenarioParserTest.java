package dev.kairos.core.scenario;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the PLAN.md 5.2 scenario format and the parser's error reporting. */
class ScenarioParserTest {

    /** The exact example from PLAN.md 5.2. */
    private static final String PLAN_EXAMPLE =
            "{\"name\":\"sprintjump_ice_gap3\",\"start\":{\"pos\":[0.5,65,0.5],\"yaw\":0},\n"
            + " \"blocks\":[{\"from\":[0,64,0],\"to\":[0,64,0],\"id\":\"packed_ice\"},\n"
            + "           {\"from\":[0,64,4],\"to\":[0,64,4],\"id\":\"stone\"}],\n"
            + " \"settleTicks\":20,\n"
            + " \"inputs\":[{\"ticks\":10,\"w\":1,\"sprint\":1},{\"ticks\":1,\"w\":1,\"sprint\":1,\"jump\":1},\n"
            + "           {\"ticks\":14,\"w\":1,\"sprint\":1}]}";

    @Test
    void parsesThePlanExampleExactly() {
        Scenario s = ScenarioParser.parse(PLAN_EXAMPLE);

        assertEquals("sprintjump_ice_gap3", s.name);
        assertEquals(0.5, s.startX);
        assertEquals(65.0, s.startY);
        assertEquals(0.5, s.startZ);
        assertEquals(0.0f, s.yaw);
        assertEquals(0.0f, s.pitch);
        assertEquals(20, s.settleTicks);

        assertEquals(2, s.blocks.size());
        ScenarioBlock ice = s.blocks.get(0);
        assertEquals("packed_ice", ice.id);
        assertEquals(0, ice.fromX);
        assertEquals(64, ice.fromY);
        assertEquals(0, ice.fromZ);
        assertEquals(1L, ice.volume());
        assertEquals("stone", s.blocks.get(1).id);
        assertEquals(4, s.blocks.get(1).fromZ);
        assertEquals(4, s.blocks.get(1).toZ);

        assertEquals(3, s.inputs.size());
        ScenarioInput run = s.inputs.get(0);
        assertEquals(10, run.ticks);
        assertEquals(1, run.w);
        assertEquals(1, run.sprint);
        assertEquals(0, run.jump);
        assertEquals(1.0f, run.moveForward());
        assertEquals(0.0f, run.moveStrafe());

        ScenarioInput jump = s.inputs.get(1);
        assertEquals(1, jump.ticks);
        assertEquals(1, jump.jump);

        assertEquals(25, s.totalInputTicks());
    }

    @Test
    void inputAtWalksTheSegments() {
        Scenario s = ScenarioParser.parse(PLAN_EXAMPLE);

        assertEquals(10, s.inputAt(0).ticks);
        assertEquals(10, s.inputAt(9).ticks);
        assertEquals(1, s.inputAt(10).ticks);        // the jump segment
        assertEquals(14, s.inputAt(11).ticks);       // the run-out segment
        assertEquals(14, s.inputAt(24).ticks);
        assertNull(s.inputAt(25));
        assertNull(s.inputAt(-1));
    }

    @Test
    void leftIsPositiveStrafe() {
        Scenario s = ScenarioParser.parse(
                "{\"name\":\"strafe\",\"start\":{\"pos\":[0,0,0]},"
                + "\"inputs\":[{\"ticks\":1,\"a\":1,\"d\":0}]}");
        assertEquals(1.0f, s.inputs.get(0).moveStrafe());

        Scenario right = ScenarioParser.parse(
                "{\"name\":\"strafe2\",\"start\":{\"pos\":[0,0,0]},"
                + "\"inputs\":[{\"ticks\":1,\"d\":1}]}");
        assertEquals(-1.0f, right.inputs.get(0).moveStrafe());
    }

    @Test
    void settleTicksDefaultsWhenMissing() {
        Scenario s = ScenarioParser.parse(
                "{\"name\":\"a\",\"start\":{\"pos\":[0,0,0]},\"inputs\":[{\"ticks\":1,\"w\":1}]}");
        assertEquals(ScenarioParser.DEFAULT_SETTLE_TICKS, s.settleTicks);
        assertTrue(s.blocks.isEmpty());
    }

    @Test
    void rejectsMalformedScenarios() {
        // Not an object.
        assertThrows(IllegalArgumentException.class, () -> ScenarioParser.parse("[]"));
        // Missing name.
        assertThrows(IllegalArgumentException.class,
                () -> ScenarioParser.parse("{\"start\":{\"pos\":[0,0,0]},\"inputs\":[{\"ticks\":1}]}"));
        // pos must have 3 numbers.
        assertThrows(IllegalArgumentException.class,
                () -> ScenarioParser.parse("{\"name\":\"a\",\"start\":{\"pos\":[0,0]},\"inputs\":[{\"ticks\":1}]}"));
        // Empty inputs.
        assertThrows(IllegalArgumentException.class,
                () -> ScenarioParser.parse("{\"name\":\"a\",\"start\":{\"pos\":[0,0,0]},\"inputs\":[]}"));
        // ticks must be >= 1.
        assertThrows(IllegalArgumentException.class,
                () -> ScenarioParser.parse("{\"name\":\"a\",\"start\":{\"pos\":[0,0,0]},\"inputs\":[{\"ticks\":0}]}"));
        // flags must be 0/1.
        assertThrows(IllegalArgumentException.class,
                () -> ScenarioParser.parse("{\"name\":\"a\",\"start\":{\"pos\":[0,0,0]},\"inputs\":[{\"ticks\":1,\"w\":2}]}"));
        // block coordinates must be whole numbers.
        assertThrows(IllegalArgumentException.class,
                () -> ScenarioParser.parse("{\"name\":\"a\",\"start\":{\"pos\":[0,0,0]},"
                        + "\"blocks\":[{\"from\":[0.5,0,0],\"to\":[0,0,0],\"id\":\"stone\"}],"
                        + "\"inputs\":[{\"ticks\":1}]}"));
        // Malformed JSON.
        assertThrows(IllegalArgumentException.class, () -> ScenarioParser.parse("{\"name\":"));
    }

    @Test
    void reportsWhereTheJsonIsBroken() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ScenarioParser.parse("{\"name\":\"a\", \"start\":{\"pos\":[0,0,0]},\"inputs\":[{\"ticks\":1,}]}"));
        assertTrue(e.getMessage().contains("offset"), e.getMessage());
    }
}
