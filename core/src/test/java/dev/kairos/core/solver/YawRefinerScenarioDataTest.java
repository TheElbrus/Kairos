package dev.kairos.core.solver;

import dev.kairos.core.scenario.Scenario;
import dev.kairos.core.scenario.ScenarioBlock;
import dev.kairos.core.scenario.ScenarioInput;
import dev.kairos.core.scenario.ScenarioParser;
import dev.kairos.core.sim.CollisionWorld;
import dev.kairos.core.sim.MovementSim;
import dev.kairos.core.trace.TraceInput;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 2.7 data guard: the committed scenario must be exactly the generator output and
 * its one-tick segments must replay from the generated start to the tight target.
 */
class YawRefinerScenarioDataTest {

    @Test
    void generatedScenarioMatchesCommittedFileAndReplays() throws IOException {
        String generated = YawRefinerScenarioGenerator.generate();
        File file = new File(YawRefinerScenarioGenerator.repoRoot(),
                YawRefinerScenarioGenerator.SCENARIO_PATH);
        byte[] committed = Files.readAllBytes(file.toPath());

        assertArrayEquals(generated.getBytes(StandardCharsets.UTF_8), committed,
                "data/scenarios/yaw_refiner_tight_wall_01.json must be generator output");
        for (byte b : committed) {
            assertTrue(b != (byte) 0x0D,
                    "generated scenario must be LF-only; found CR in " + file);
        }

        Scenario scenario = ScenarioParser.parse(
                new String(committed, StandardCharsets.UTF_8));
        assertEquals(YawRefinerScenarioGenerator.SCENARIO_NAME, scenario.name);
        assertEquals(10, scenario.settleTicks);
        assertEquals(scenario.inputs.size(), scenario.totalInputTicks(),
                "one segment per tick");
        assertRegionsAreDisjoint(scenario);

        CollisionWorld world = CollisionWorld.of(YawRefinerScenarioGenerator.blockData(),
                scenario);
        MovementSim.State state = YawRefinerScenarioGenerator.start();
        int jumps = 0;
        for (int tick = 0; tick < scenario.totalInputTicks(); tick++) {
            ScenarioInput segment = scenario.inputAt(tick);
            assertNotNull(segment, "scenario input at tick " + tick);
            assertNotNull(segment.yaw, "per-tick yaw at tick " + tick);
            assertEquals(1, segment.ticks, "segment " + tick + " must cover one tick");
            if (segment.jump != 0) {
                jumps++;
            }
            MovementSim.step(state, traceInput(segment, scenario.pitch), world);
        }
        assertEquals(1, jumps, "the refined plan must contain exactly one jump input");
        assertTrue(YawRefinerScenarioGenerator.tightTarget().isReachedBy(state),
                "scenario replay must land on the tight target; ended at "
                        + state.x + "," + state.y + "," + state.z);
    }

    /**
     * The client harness verifies every position of every region against that region's
     * own requested state. Overlapping regions would make the later state win in the
     * world but the earlier region would still be checked, so the run aborts during
     * BUILD even when the union is correct.
     */
    private static void assertRegionsAreDisjoint(Scenario scenario) {
        for (int i = 0; i < scenario.blocks.size(); i++) {
            ScenarioBlock a = scenario.blocks.get(i);
            for (int j = i + 1; j < scenario.blocks.size(); j++) {
                ScenarioBlock b = scenario.blocks.get(j);
                boolean overlap = a.fromX <= b.toX && b.fromX <= a.toX
                        && a.fromY <= b.toY && b.fromY <= a.toY
                        && a.fromZ <= b.toZ && b.fromZ <= a.toZ;
                assertFalse(overlap, "scenario regions " + i + " and " + j + " overlap;"
                        + " the client harness checks each region against its own state");
            }
        }
    }

    private static TraceInput traceInput(ScenarioInput segment, float pitch) {
        float mf = segment.moveForward();
        float ms = segment.moveStrafe();
        if (segment.sneak != 0) {
            mf = (float) ((double) mf * 0.3);
            ms = (float) ((double) ms * 0.3);
        }
        return new TraceInput(
                segment.w, segment.a, segment.s, segment.d,
                segment.jump, segment.sneak, segment.sprint,
                segment.yaw.floatValue(), pitch, mf, ms);
    }
}
