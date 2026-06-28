package com.ecommerce.steps;

import com.ecommerce.cucumber.ExperimentContext;
import com.ecommerce.cucumber.PlaywrightContext;
import com.ecommerce.experiments.TestPlanExecutor;
import com.ecommerce.experiments.TestPlanCache;
import com.ecommerce.pages.PDPPage;
import com.ecommerce.validators.HeaderValidator;
import com.ecommerce.validators.PDPValidator;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import lombok.extern.slf4j.Slf4j;

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

    // ── Navigation ────────────────────────────────────────────────────────────

    @Given("I navigate to the Leather Jewelry Travel Case PDP")
    public void navigateToPDP() {
        pdpPage().navigate();
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

        if (experimentCtx.getVariant() != null
                && experimentCtx.getVariant().isExperimentOn()
                && experimentCtx.getResolvedPlan() != null) {

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
}
