package dev.kairos.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlaceholderTest {

    @Test
    void skeletonIdIsStable() {
        assertEquals("kairos-core-skeleton", Placeholder.id());
    }
}
