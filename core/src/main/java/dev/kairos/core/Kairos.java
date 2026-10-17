package dev.kairos.core;

/**
 * Library-wide constants. Kept tiny on purpose: this class exists so the loader can
 * prove at runtime that the shaded {@code core} classes are present and current.
 */
public final class Kairos {

    /** Version of the plan format described in docs/PLAN.md. */
    public static final String PLAN_VERSION = "2.0";

    private Kairos() {
    }
}
