package com.ecommerce.experiments.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A single executable step in a JSON test plan.
 *
 * Prefer the static factory methods over the raw builder for hand-authored plans:
 *   TestStep.navigate(url)
 *   TestStep.click(locator)
 *   TestStep.fill(locator, value)
 *   TestStep.assertVisible(locator)
 *   TestStep.assertNotVisible(locator)
 *   TestStep.assertText(locator, expectedText)
 *   TestStep.pressKey(key)
 *
 * Keep the builder for AI-generated / deserialized steps where field-by-field
 * construction is necessary.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TestStep {

    private static final AtomicInteger COUNTER = new AtomicInteger(0);

    /** Unique step id — auto-assigned by factories; set explicitly for AI-generated steps. */
    private String id;

    /** Step type — use StepType.key() values: navigate | click | fill | assert_visible … */
    private String type;

    /**
     * CSS selector or data-testid, e.g. "[data-testid='size-option-M']".
     * Null for navigate steps.
     */
    private String locator;

    /**
     * URL for navigate steps; expected text value for assert_text;
     * text to type for fill steps. Null otherwise.
     */
    private String value;

    /** Human-readable purpose — used by self-healing engine as context */
    private String description;

    /**
     * Set to true when this step was AI-generated for an experiment variant
     * (not part of the original base plan).
     */
    @Builder.Default
    private boolean variantStep = false;

    // ── Static factories ──────────────────────────────────────────────────────

    public static TestStep navigate(String url) {
        return step(StepType.NAVIGATE).value(url)
                .description("Navigate to " + url).build();
    }

    public static TestStep click(String locator) {
        return step(StepType.CLICK).locator(locator).build();
    }

    public static TestStep click(String locator, String description) {
        return step(StepType.CLICK).locator(locator).description(description).build();
    }

    public static TestStep fill(String locator, String value) {
        return step(StepType.FILL).locator(locator).value(value).build();
    }

    public static TestStep fill(String locator, String value, String description) {
        return step(StepType.FILL).locator(locator).value(value).description(description).build();
    }

    public static TestStep assertVisible(String locator) {
        return step(StepType.ASSERT_VISIBLE).locator(locator).build();
    }

    public static TestStep assertVisible(String locator, String description) {
        return step(StepType.ASSERT_VISIBLE).locator(locator).description(description).build();
    }

    public static TestStep assertNotVisible(String locator) {
        return step(StepType.ASSERT_NOT_VISIBLE).locator(locator).build();
    }

    public static TestStep assertText(String locator, String expectedText) {
        return step(StepType.ASSERT_TEXT).locator(locator).value(expectedText).build();
    }

    public static TestStep pressKey(String key) {
        return step(StepType.PRESS_KEY).value(key)
                .description("Press key: " + key).build();
    }

    /** Resets the auto-ID counter — call in @BeforeMethod if plans are built per test. */
    public static void resetCounter() {
        COUNTER.set(0);
    }

    private static TestStepBuilder step(StepType type) {
        return TestStep.builder()
                .id("step-" + COUNTER.incrementAndGet())
                .type(type.key());
    }
}
