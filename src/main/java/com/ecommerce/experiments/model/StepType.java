package com.ecommerce.experiments.model;

public enum StepType {
    NAVIGATE,
    CLICK,
    FILL,
    ASSERT_VISIBLE,
    ASSERT_NOT_VISIBLE,
    ASSERT_TEXT,
    PRESS_KEY;

    /** Serialised value used in JSON plans and the AI prompt. */
    public String key() {
        return name().toLowerCase();
    }
}
