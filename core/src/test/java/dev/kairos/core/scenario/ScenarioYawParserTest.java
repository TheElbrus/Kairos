package dev.kairos.core.scenario;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Pins the optional per-tick yaw added in task 2.7, including float precision. */
class ScenarioYawParserTest {

    @Test
    void absentYawKeepsTheScenarioLevelBehavior() {
        Scenario scenario = ScenarioParser.parse(
                "{\"name\":\"fixed\",\"start\":{\"pos\":[0,0,0],\"yaw\":33.3},"
                        + "\"inputs\":[{\"ticks\":2,\"w\":1}]}");

        assertEquals(33.3f, scenario.yaw);
        assertNull(scenario.inputs.get(0).yaw,
                "an absent segment yaw must be distinguishable from a numeric zero");
    }

    @Test
    void parsesEveryFinitePerTickYawAtFloatPrecision() {
        Scenario scenario = ScenarioParser.parse(
                "{\"name\":\"per_tick\",\"start\":{\"pos\":[0,0,0]},"
                        + "\"inputs\":[{\"ticks\":1,\"yaw\":0.1},"
                        + "{\"ticks\":1,\"yaw\":-161.9},"
                        + "{\"ticks\":1,\"yaw\":33.3}]}");

        assertEquals(Float.floatToIntBits(0.1f),
                Float.floatToIntBits(scenario.inputs.get(0).yaw.floatValue()));
        assertEquals(Float.floatToIntBits(-161.9f),
                Float.floatToIntBits(scenario.inputs.get(1).yaw.floatValue()));
        assertEquals(Float.floatToIntBits(33.3f),
                Float.floatToIntBits(scenario.inputs.get(2).yaw.floatValue()));
        assertEquals(161.9f, Math.abs(scenario.inputs.get(1).yaw), 0.0f);
    }

    @Test
    void rejectsInvalidPerTickYaw() {
        assertThrows(IllegalArgumentException.class, () -> ScenarioParser.parse(
                "{\"name\":\"bad\",\"start\":{\"pos\":[0,0,0]},"
                        + "\"inputs\":[{\"ticks\":1,\"yaw\":\"north\"}]}"));
        assertThrows(IllegalArgumentException.class, () -> ScenarioParser.parse(
                "{\"name\":\"bad\",\"start\":{\"pos\":[0,0,0]},"
                        + "\"inputs\":[{\"ticks\":1,\"yaw\":1e999}]}"));
        assertThrows(IllegalArgumentException.class, () -> ScenarioParser.parse(
                "{\"name\":\"bad\",\"start\":{\"pos\":[0,0,0]},"
                        + "\"inputs\":[{\"ticks\":1,\"yaw\":1e39}]}"));
    }
}
