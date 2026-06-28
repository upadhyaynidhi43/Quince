package com.ecommerce.experiments;

import com.ecommerce.experiments.model.StepType;
import com.ecommerce.experiments.model.TestPlan;
import com.ecommerce.experiments.model.TestStep;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.WaitForSelectorState;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Executes a TestPlan step-by-step against a live Playwright Page.
 *
 * Supported step types:
 *   navigate          — goto a URL
 *   click             — click a CSS / Playwright selector
 *   fill              — clear + type into an input
 *   press_key         — keyboard.press(value), e.g. "Escape"
 *   assert_visible    — element must be present and visible
 *   assert_not_visible — element must be absent or hidden
 *   assert_text       — element innerText must equal value
 *
 * On PlaywrightException: delegates to SelfHealingEngine.
 * If healing succeeds the step is retried once with the fixed locator.
 */
@Slf4j
public class TestPlanExecutor {

    private final SelfHealingEngine healer;

    public TestPlanExecutor(TestPlanCache cache) {
        this.healer = new SelfHealingEngine(cache);
    }

    public void execute(Page page, TestPlan plan) {
        List<TestStep> steps = plan.getSteps();
        log.info("Executing plan [{}/{}] — {} steps for user [{}]",
                plan.getExperimentName(), plan.getAssignedVariation(),
                steps.size(), plan.getUserId());

        for (TestStep step : steps) {
            log.info("  → [{}] {} : {}", step.getId(), step.getType(),
                    step.getDescription() != null ? step.getDescription() : step.getLocator());
            executeStep(page, step, plan);
        }

        log.info("Plan execution complete — all {} steps passed.", steps.size());
    }

    // ─────────────────────────────────────────────────────────────────────────

    private void executeStep(Page page, TestStep step, TestPlan plan) {
        // press_key has no locator — no self-healing applicable
        if (StepType.PRESS_KEY.key().equals(step.getType())) {
            runStep(page, step);
            return;
        }

        try {
            runStep(page, step);
        } catch (PlaywrightException | AssertionError e) {
            log.warn("Step [{}] failed: {} — attempting self-heal", step.getId(), e.getMessage());

            if (step.getLocator() != null && healer.heal(page, step, plan)) {
                log.info("Retrying step [{}] with healed locator: {}", step.getId(), step.getLocator());
                runStep(page, step);
            } else {
                throw e;
            }
        }
    }

    private void runStep(Page page, TestStep step) {
        switch (step.getType()) {

            case "navigate" -> {   // StepType.NAVIGATE.key()
                String url = step.getValue() != null ? step.getValue() : step.getLocator();
                log.debug("    navigate → {}", url);
                page.navigate(url);
                page.waitForLoadState(com.microsoft.playwright.options.LoadState.DOMCONTENTLOADED);
                // Give JS frameworks time to hydrate
                page.waitForTimeout(2000);
            }

            case "click" -> {
                Locator loc = resolveLocator(page, step.getLocator());
                loc.waitFor(new Locator.WaitForOptions()
                        .setState(WaitForSelectorState.VISIBLE)
                        .setTimeout(15_000));
                loc.click();
                // Small pause after click to let UI react (modal open, colour switch, etc.)
                page.waitForTimeout(800);
            }

            case "fill" -> {
                Locator loc = resolveLocator(page, step.getLocator());
                loc.waitFor(new Locator.WaitForOptions()
                        .setState(WaitForSelectorState.VISIBLE)
                        .setTimeout(15_000));
                loc.clear();
                loc.fill(step.getValue() != null ? step.getValue() : "");
            }

            case "press_key" -> {
                String key = step.getValue();
                if (key == null || key.isBlank()) {
                    throw new IllegalArgumentException("press_key step requires a value (key name)");
                }
                log.debug("    press_key → {}", key);
                page.keyboard().press(key);
                page.waitForTimeout(500);
            }

            case "assert_visible" -> {
                Locator loc = resolveLocator(page, step.getLocator());
                loc.waitFor(new Locator.WaitForOptions()
                        .setState(WaitForSelectorState.VISIBLE)
                        .setTimeout(15_000));
                if (!loc.isVisible()) {
                    throw new AssertionError("assert_visible FAILED — element not visible: "
                            + step.getLocator() + " | step: " + step.getDescription());
                }
                log.info("    ✔ visible: {}", step.getLocator());
            }

            case "assert_not_visible" -> {
                Locator loc = resolveLocator(page, step.getLocator());
                boolean notVisible = loc.count() == 0 || !loc.first().isVisible();
                if (!notVisible) {
                    throw new AssertionError("assert_not_visible FAILED — element IS visible: "
                            + step.getLocator() + " | step: " + step.getDescription());
                }
                log.info("    ✔ not visible: {}", step.getLocator());
            }

            case "assert_text" -> {
                Locator loc = resolveLocator(page, step.getLocator());
                loc.waitFor(new Locator.WaitForOptions()
                        .setState(WaitForSelectorState.VISIBLE)
                        .setTimeout(15_000));
                String actual   = loc.innerText().trim();
                String expected = step.getValue();
                if (!actual.equals(expected)) {
                    throw new AssertionError("assert_text FAILED — expected [" + expected
                            + "] but got [" + actual + "] | step: " + step.getDescription());
                }
                log.info("    ✔ text matches: '{}'", expected);
            }

            default -> throw new IllegalArgumentException(
                    "Unknown step type: '" + step.getType() + "' in step [" + step.getId() + "]");
        }
    }

    /**
     * Playwright's page.locator() supports both CSS and its own extended syntax
     * (e.g. `:text('...')`, `role=...`).  We pass the locator string through
     * directly — no translation needed.
     */
    private Locator resolveLocator(Page page, String locator) {
        if (locator == null || locator.isBlank()) {
            throw new IllegalArgumentException("Step locator must not be null/blank");
        }
        return page.locator(locator);
    }
}
