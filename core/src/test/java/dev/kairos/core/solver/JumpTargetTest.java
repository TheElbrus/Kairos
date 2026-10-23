package dev.kairos.core.solver;

import dev.kairos.core.sim.MovementSim;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 2.4 landing-criterion tests: zero margin preserves the 2.2 strict-overlap rule,
 * positive margins require a strictly deeper overlap on both horizontal axes, and the
 * vertical conditions stay exact and independent of the horizontal margin.
 */
class JumpTargetTest {

    private static final double SURFACE = 65.0;

    @Test
    void zeroMarginAcceptsTinyStrictOverlapAndRejectsEdgeTouching() {
        JumpTarget target = JumpTarget.of(0.0, 4.0, 0.0, 4.0, SURFACE);

        assertEquals(0.0, target.edgeMargin, 0.0,
                "the five-argument factory must keep the zero-margin default");
        assertTrue(target.isReachedBy(state(-1.0e-9, 1.0e-9, 1.0, 2.0)),
                "a strict overlap on both axes must be accepted at zero margin");
        assertFalse(target.isReachedBy(state(-0.6, 0.0, 1.0, 2.0)),
                "touching the west edge must not count as overlap");
        assertFalse(target.isReachedBy(state(1.0, 2.0, -0.6, 0.0)),
                "touching the north edge must not count as overlap");
    }

    @Test
    void positiveMarginRejectsSmallerOverlapAndAcceptsLargerOverlap() {
        JumpTarget target = JumpTarget.of(0.0, 4.0, 0.0, 4.0, SURFACE, 0.25);

        assertFalse(target.isReachedBy(state(-0.4, 0.2, 1.0, 2.0)),
                "an X overlap smaller than the margin must be rejected");
        assertTrue(target.isReachedBy(state(-0.3, 0.3, 1.0, 2.0)),
                "an X overlap larger than the margin must be accepted");
        assertFalse(target.isReachedBy(state(1.0, 2.0, -0.4, 0.2)),
                "a Z overlap smaller than the margin must be rejected");
        assertTrue(target.isReachedBy(state(1.0, 2.0, -0.3, 0.3)),
                "a Z overlap larger than the margin must be accepted");
    }

    @Test
    void overlapEqualToPositiveMarginIsRejected() {
        JumpTarget target = JumpTarget.of(0.0, 4.0, 0.0, 4.0, SURFACE, 0.25);

        assertFalse(target.isReachedBy(state(-0.35, 0.25, 1.0, 2.0)),
                "an X overlap exactly equal to the margin must be rejected");
        assertFalse(target.isReachedBy(state(1.0, 2.0, -0.35, 0.25)),
                "a Z overlap exactly equal to the margin must be rejected");
    }

    @Test
    void bothAxesMustSatisfyTheMarginAndFullyContainedLandingPasses() {
        JumpTarget target = JumpTarget.of(0.0, 4.0, 0.0, 4.0, SURFACE, 0.25);

        assertFalse(target.isReachedBy(state(1.0, 2.0, -0.5, 0.1)),
                "a deep X overlap must not rescue a shallow Z overlap");
        assertFalse(target.isReachedBy(state(-0.5, 0.1, 1.0, 2.0)),
                "a deep Z overlap must not rescue a shallow X overlap");
        assertTrue(target.isReachedBy(state(1.0, 1.6, 2.0, 2.6)),
                "a fully contained box must satisfy both margins");
    }

    @Test
    void groundAndExactSurfaceChecksIgnoreHorizontalMargin() {
        for (double margin : new double[] {0.0, 0.25}) {
            JumpTarget target = JumpTarget.of(0.0, 4.0, 0.0, 4.0, SURFACE, margin);
            assertFalse(target.isReachedBy(state(1.0, 1.6, 2.0, 2.6, SURFACE, false)),
                    "onGround=false must reject at margin " + margin);
            assertFalse(target.isReachedBy(
                            state(1.0, 1.6, 2.0, 2.6, SURFACE + 1.0e-9, true)),
                    "a wrong surfaceY must reject at margin " + margin);
        }
    }

    @Test
    void invalidMarginsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> JumpTarget.of(0.0, 4.0, 0.0, 4.0, SURFACE, -0.1));
        assertThrows(IllegalArgumentException.class,
                () -> JumpTarget.of(0.0, 4.0, 0.0, 4.0, SURFACE, Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> JumpTarget.of(0.0, 4.0, 0.0, 4.0, SURFACE, Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class,
                () -> JumpTarget.of(0.0, 4.0, 0.0, 4.0, SURFACE, Double.NEGATIVE_INFINITY));
    }

    private static MovementSim.State state(double minX, double maxX, double minZ, double maxZ) {
        return state(minX, maxX, minZ, maxZ, SURFACE, true);
    }

    private static MovementSim.State state(double minX, double maxX, double minZ, double maxZ,
            double y, boolean onGround) {
        MovementSim.State state = new MovementSim.State();
        state.minX = minX;
        state.maxX = maxX;
        state.minZ = minZ;
        state.maxZ = maxZ;
        state.x = (minX + maxX) * 0.5;
        state.z = (minZ + maxZ) * 0.5;
        state.y = y;
        state.maxY = y + 1.8;
        state.onGround = onGround;
        return state;
    }
}
