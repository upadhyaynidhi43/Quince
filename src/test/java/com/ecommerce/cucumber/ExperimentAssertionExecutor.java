package com.ecommerce.cucumber;

import com.ecommerce.experiments.ExperimentAssertionResult;
import com.ecommerce.experiments.GeneratedExperimentArtifact;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Runs experiment-specific locator assertions programmatically against the live page.
 *
 * Why programmatic rather than via the generated .feature file:
 *   The generated temp/ files are not on Cucumber's feature path (src/test/resources/features),
 *   so their steps never execute. Assertions run here in @After (order 10) — before the
 *   browser context closes — and results land in ExperimentContext for Allure logging.
 *
 * All assertions are soft: exceptions are caught, recorded, and never rethrown.
 * The keys checked are the "variant*" entries in the locator map.
 */
@Slf4j
public class ExperimentAssertionExecutor {

    // Only keys whose name starts with "variant" are experiment-specific UI checks.
    private static final String VARIANT_KEY_PREFIX = "variant";

    private ExperimentAssertionExecutor() {}

    /**
     * For each active artifact, find every variant* locator entry and assert it is
     * visible on the page. Null locators are recorded as SKIPPED (not yet configured).
     *
     * @param artifacts  list of ON-experiment artifacts carrying the resolved locator map
     * @param page       live Playwright page (must still be open)
     * @return           list of assertion results — one per variant locator entry per artifact
     */
    public static List<ExperimentAssertionResult> run(
            List<GeneratedExperimentArtifact> artifacts, Page page) {

        List<ExperimentAssertionResult> results = new ArrayList<>();

        if (artifacts == null || artifacts.isEmpty() || page == null) {
            return results;
        }

        for (GeneratedExperimentArtifact artifact : artifacts) {
            String flagKey   = artifact.getExperimentName();
            String variation = artifact.getAssignedVariation();

            for (Map.Entry<String, String> entry : artifact.getLocators().entrySet()) {
                String key     = entry.getKey();
                String locator = entry.getValue();

                if (!key.startsWith(VARIANT_KEY_PREFIX)) {
                    continue; // core locators are not experiment assertions
                }

                String step = "variant locator visible [" + key + "]";

                if (locator == null || locator.isBlank()) {
                    log.info("[ExperimentAssertionExecutor] {} → SKIPPED (locator not configured for {})",
                            step, key);
                    results.add(ExperimentAssertionResult.skipped(flagKey, variation, step,
                            "Locator not yet configured in PDPLocators." + key.toUpperCase()
                            + " — fill in selector before experiment is promoted"));
                    continue;
                }

                try {
                    page.waitForSelector(locator,
                            new Page.WaitForSelectorOptions()
                                    .setState(WaitForSelectorState.VISIBLE)
                                    .setTimeout(3000));
                    boolean visible = page.locator(locator).isVisible();
                    if (visible) {
                        log.info("[ExperimentAssertionExecutor] {} = {} → PASSED", step, locator);
                        results.add(ExperimentAssertionResult.ok(flagKey, variation, step + " = " + locator));
                    } else {
                        String msg = "Element found but not visible: " + locator;
                        log.warn("[ExperimentAssertionExecutor] {} → FAILED: {}", step, msg);
                        results.add(ExperimentAssertionResult.fail(flagKey, variation,
                                step + " = " + locator, msg));
                    }
                } catch (Exception e) {
                    String msg = e.getMessage() != null
                            ? e.getMessage().split("\n")[0] // first line only — keep it concise
                            : e.getClass().getSimpleName();
                    log.warn("[ExperimentAssertionExecutor] {} → FAILED: {}", step, msg);
                    results.add(ExperimentAssertionResult.fail(flagKey, variation,
                            step + " = " + locator, msg));
                }
            }
        }

        return results;
    }
}
