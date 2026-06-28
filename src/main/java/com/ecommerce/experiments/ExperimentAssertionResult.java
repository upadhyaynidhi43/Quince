package com.ecommerce.experiments;

/**
 * Outcome of a single experiment-specific assertion step.
 * Collected in ExperimentContext so the Allure hook can log them after the scenario.
 *
 * States:
 *   passed  = true             → element found and visible
 *   skipped = true             → locator was null (not yet configured in PDPLocators)
 *   passed  = false, !skipped  → locator present but assertion failed
 */
public record ExperimentAssertionResult(
        String flagKey,
        String variation,
        String stepDescription,
        boolean passed,
        boolean skipped,
        String failureMessage
) {

    public static ExperimentAssertionResult ok(String flagKey, String variation, String step) {
        return new ExperimentAssertionResult(flagKey, variation, step, true, false, null);
    }

    public static ExperimentAssertionResult fail(String flagKey, String variation, String step, String message) {
        return new ExperimentAssertionResult(flagKey, variation, step, false, false, message);
    }

    public static ExperimentAssertionResult skipped(String flagKey, String variation, String step, String reason) {
        return new ExperimentAssertionResult(flagKey, variation, step, false, true, reason);
    }
}
