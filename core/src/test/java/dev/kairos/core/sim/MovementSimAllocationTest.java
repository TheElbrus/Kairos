package dev.kairos.core.sim;

import com.sun.management.ThreadMXBean;
import dev.kairos.core.bench.SimBench;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.management.ManagementFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

class MovementSimAllocationTest {

    private static final long WARMUP_STEPS = 2_000_000L;
    private static final long MEASURED_STEPS = 1_000_000L;

    @Test
    void stepAllocatesNothing() throws Exception {
        ThreadMXBean bean = allocatedBean();
        long threadId = Thread.currentThread().getId();
        bean.getThreadAllocatedBytes(threadId);

        SimBench.Setup setup = SimBench.prepare(new File(System.getProperty("user.dir")), null);
        MovementSim.State work = SimBench.newWorkingState(setup.template);

        SimBench.runTicks(work, setup.template, setup.inputs, setup.world, WARMUP_STEPS);
        consume(noStep(MEASURED_STEPS));

        long baselineBefore = bean.getThreadAllocatedBytes(threadId);
        consume(noStep(MEASURED_STEPS));
        long baselineAfter = bean.getThreadAllocatedBytes(threadId);

        long stepBefore = bean.getThreadAllocatedBytes(threadId);
        SimBench.runTicks(work, setup.template, setup.inputs, setup.world, MEASURED_STEPS);
        long stepAfter = bean.getThreadAllocatedBytes(threadId);

        long baselineBytes = baselineAfter - baselineBefore;
        long stepBytes = stepAfter - stepBefore;
        String message = "MovementSim.step allocated " + stepBytes + " bytes over "
                + MEASURED_STEPS + " steps ("
                + ((double) stepBytes / (double) MEASURED_STEPS)
                + " bytes/tick); no-step baseline " + baselineBytes + " bytes; delta "
                + (stepBytes - baselineBytes) + " bytes";
        System.out.println("MovementSimAllocationTest: " + message);
        assertEquals(0L, stepBytes - baselineBytes, message);
    }

    private static long noStep(long steps) {
        long sink = 0L;
        for (long i = 0L; i < steps; i++) {
            sink += i;
        }
        return sink;
    }

    private static void consume(long value) {
        if (value == Long.MIN_VALUE) {
            fail("unreachable sink");
        }
    }

    private static ThreadMXBean allocatedBean() {
        java.lang.management.ThreadMXBean any = ManagementFactory.getThreadMXBean();
        if (!(any instanceof ThreadMXBean)) {
            fail("com.sun.management.ThreadMXBean is not available; got "
                    + any.getClass().getName());
            return null;
        }
        ThreadMXBean bean = (ThreadMXBean) any;
        if (!bean.isThreadAllocatedMemorySupported()) {
            fail("thread allocated-memory measurement is not supported by this JVM");
            return null;
        }
        if (!bean.isThreadAllocatedMemoryEnabled()) {
            bean.setThreadAllocatedMemoryEnabled(true);
        }
        return bean;
    }
}
