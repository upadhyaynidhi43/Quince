package com.ecommerce.steps;

import com.ecommerce.constants.PDPConstants;
import com.ecommerce.cucumber.ExperimentContext;
import com.ecommerce.cucumber.PlaywrightContext;
import com.ecommerce.experiments.ExperimentAssertionResult;
import com.ecommerce.experiments.FeatureGenerationContext;
import com.ecommerce.experiments.TestPlanCache;
import com.ecommerce.experiments.TestPlanExecutor;
import com.ecommerce.pages.HeaderLocators;
import com.ecommerce.pages.PDPLocators;
import com.ecommerce.pages.PDPPage;
import com.ecommerce.validators.HeaderValidator;
import com.ecommerce.validators.PDPValidator;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
public class PDPSteps {

    private final PlaywrightContext pw;
    private final ExperimentContext experimentCtx;
    private final TestPlanExecutor  executor;

    // Page objects created lazily — page is null at construction time
    private PDPPage         pdpPage()       { return new PDPPage(pw.page()); }
    private PDPValidator    pdpValidator()  { return new PDPValidator(pw.page()); }
    private HeaderValidator headerValidator() { return new HeaderValidator(pw.page()); }

    public PDPSteps(PlaywrightContext pw, ExperimentContext experimentCtx) {
        this.pw            = pw;
        this.experimentCtx = experimentCtx;
        this.executor      = new TestPlanExecutor(new TestPlanCache());
    }

    // ── User identity + experiment resolution ─────────────────────────────────

    @Given("I am the test user {string}")
    public void setTestUser(String userId) {
        // Store userId; experiment resolution is deferred until after PDP navigation
        // so the live DOM is available for Claude-based locator discovery when experiment is ON.
        experimentCtx.setPendingUserId(userId);
        log.info("Test user set to [{}] — experiment will resolve after PDP navigation", userId);
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    @Given("I navigate to the Leather Jewelry Travel Case PDP")
    public void navigateToPDP() {
        pdpPage().navigate();
        experimentCtx.resolveForUser(pw.page(), pdpFeatureContext(), pdpBaseLocators());
        log.info("Experiment resolved — ON={} variation=[{}]",
                experimentCtx.getVariant() != null && experimentCtx.getVariant().isExperimentOn(),
                experimentCtx.getVariant() != null ? experimentCtx.getVariant().getAssignedVariation() : "n/a");
    }

    private FeatureGenerationContext pdpFeatureContext() {
        return FeatureGenerationContext.builder()
                .flowName("PDP")
                .pageUrl(PDPConstants.PDP_URL)
                .locatorsClassName("PDPLocators")
                .baseFeatureFile("src/test/resources/features/pdp.feature")
                .build();
    }

    private Map<String, String> pdpBaseLocators() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("colourContainer", PDPLocators.COLOUR_OPTION_CONTAINER);
        map.put("zipCodeButton",   PDPLocators.ZIP_CODE_BUTTON);
        map.put("zipInput",        PDPLocators.ZIP_INPUT);
        map.put("zipUpdate",       PDPLocators.ZIP_UPDATE);
        map.put("modalClose",      PDPLocators.MODAL_CLOSE);
        map.put("eddMessage",      PDPLocators.EDD_MESSAGE);
        map.put("addToBag",        PDPLocators.ADD_TO_BAG_BUTTON);
        map.put("cartIcon",        HeaderLocators.CART_ICON);
        // Experiment variant stubs — fill in PDPLocators.EXPERIMENT_VARIANT_* when
        // selectors are known; null entries are replaced by live DOM discovery via Claude.
        map.put("variantContainer", PDPLocators.EXPERIMENT_VARIANT_CONTAINER);
        map.put("variantCta",       PDPLocators.EXPERIMENT_VARIANT_CTA);
        map.put("variantBadge",     PDPLocators.EXPERIMENT_VARIANT_BADGE);
        return map;
    }

    // ── Colour ────────────────────────────────────────────────────────────────

    @When("I select the {string} colour")
    public void selectColour(String colour) {
        pdpPage().selectColour(colour);
    }

    @Then("the {string} colour option should be active")
    public void assertColourActive(String colour) {
        pdpValidator().colourIsActive(colour);
    }

    // ── Zip code / delivery ───────────────────────────────────────────────────

    @And("I open the zip code delivery check")
    public void openZipModal() {
        pdpPage().openZipModal();
    }

    @And("I enter zip code {string}")
    public void enterZipCode(String zip) {
        pdpPage().enterZipCode(zip);
    }

    @And("I submit the zip code")
    public void submitZipCode() {
        pdpPage().submitZipCode();
    }

    @Then("the estimated delivery message should be visible")
    public void assertEDDVisible() {
        pdpValidator().eddMessageIsVisible();
    }

    @Then("the estimated delivery message should contain {string}")
    public void assertEDDContains(String text) {
        pdpValidator().eddMessageContains(text);
    }

    @Then("no delivery estimate should be shown")
    public void assertEDDNotVisible() {
        pdpValidator().eddMessageIsNotVisible();
    }

    @And("I dismiss the zip code modal")
    public void dismissZipModal() {
        pdpPage().dismissZipModal();
    }

    // ── Add to bag ────────────────────────────────────────────────────────────

    /**
     * If the experiment is ON, execute the resolved variant plan (from cache or LLM).
     * If the experiment is OFF, perform the standard add-to-bag click.
     * The feature file never changes — the experiment decision is invisible.
     */
    @And("I click {string}")
    public void clickButton(String buttonText) {
        if (!"ADD TO BAG".equals(buttonText)) {
            throw new IllegalArgumentException("No mapped action for button: " + buttonText);
        }

        if (experimentCtx.isAnyExperimentOn() && experimentCtx.getResolvedPlan() != null) {
            log.info("Experiment ON — executing {} variant plan [{}]",
                    experimentCtx.isCacheHit() ? "cached" : "LLM-generated",
                    experimentCtx.getVariant().getAssignedVariation());
            executor.execute(pw.page(), experimentCtx.getResolvedPlan());
        } else {
            log.info("Experiment OFF — running standard add-to-bag step");
            pdpPage().addToBag();
        }
    }

    // ── Cart ──────────────────────────────────────────────────────────────────

    @Then("the cart icon should be visible in the header")
    public void assertCartVisible() {
        headerValidator().cartIconIsVisible();
    }

    // ── Variant-specific assertions (soft-assert — failure logs to Allure, never blocks core flow) ──

    @Then("the element with locator {string} should be visible")
    public void assertLocatorVisible(String locator) {
        String step = "locator visible: " + locator;
        try {
            pdpValidator().locatorIsVisible(locator);
            experimentCtx.recordExperimentAssertion(ExperimentAssertionResult.ok(
                    experimentCtx.activeExperimentFlagKey(),
                    experimentCtx.activeExperimentVariation(), step));
        } catch (AssertionError | Exception e) {
            log.warn("[Experiment soft-assert] {} — {}", step, e.getMessage());
            experimentCtx.recordExperimentAssertion(ExperimentAssertionResult.fail(
                    experimentCtx.activeExperimentFlagKey(),
                    experimentCtx.activeExperimentVariation(), step, e.getMessage()));
        }
    }

    @Then("the element with locator {string} should not be visible")
    public void assertLocatorNotVisible(String locator) {
        String step = "locator not visible: " + locator;
        try {
            pdpValidator().locatorIsNotVisible(locator);
            experimentCtx.recordExperimentAssertion(ExperimentAssertionResult.ok(
                    experimentCtx.activeExperimentFlagKey(),
                    experimentCtx.activeExperimentVariation(), step));
        } catch (AssertionError | Exception e) {
            log.warn("[Experiment soft-assert] {} — {}", step, e.getMessage());
            experimentCtx.recordExperimentAssertion(ExperimentAssertionResult.fail(
                    experimentCtx.activeExperimentFlagKey(),
                    experimentCtx.activeExperimentVariation(), step, e.getMessage()));
        }
    }

    @Then("the element with locator {string} should contain text {string}")
    public void assertLocatorContainsText(String locator, String text) {
        String step = "locator contains text '" + text + "': " + locator;
        try {
            pdpValidator().locatorContainsText(locator, text);
            experimentCtx.recordExperimentAssertion(ExperimentAssertionResult.ok(
                    experimentCtx.activeExperimentFlagKey(),
                    experimentCtx.activeExperimentVariation(), step));
        } catch (AssertionError | Exception e) {
            log.warn("[Experiment soft-assert] {} — {}", step, e.getMessage());
            experimentCtx.recordExperimentAssertion(ExperimentAssertionResult.fail(
                    experimentCtx.activeExperimentFlagKey(),
                    experimentCtx.activeExperimentVariation(), step, e.getMessage()));
        }
    }
}
