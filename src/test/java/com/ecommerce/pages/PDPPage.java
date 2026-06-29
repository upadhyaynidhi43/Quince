package com.ecommerce.pages;

import com.ecommerce.constants.PDPConstants;
import com.ecommerce.experiments.SelfHealingEngine;
import com.ecommerce.experiments.TestPlanCache;
import com.ecommerce.experiments.model.TestPlan;
import com.ecommerce.experiments.model.TestStep;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitForSelectorState;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PDPPage {

    private final Page page;
    private final SelfHealingEngine healer;
    private final TestPlan healingPlan;

    public PDPPage(Page page) {
        this.page = page;
        this.healer = new SelfHealingEngine(new TestPlanCache());
        TestStep.resetCounter();
        this.healingPlan = TestPlan.builder()
                .userId("base")
                .experimentName("base")
                .experimentOn(false)
                .build();
    }

    public void navigate() {
        log.info("Navigating to PDP: {}", PDPConstants.PDP_URL);
        page.navigate(PDPConstants.PDP_URL);
        // DOMCONTENTLOADED is reliable — NETWORKIDLE hangs on sites with analytics/polling
        page.waitForLoadState(LoadState.DOMCONTENTLOADED);
        waitVisibleHealing(PDPLocators.colourLabel(PDPConstants.DEFAULT_COLOUR), "Wait for colour options to load");
        log.info("PDP loaded — title: {}", page.title());
    }

    public void selectColour(String colour) {
        log.info("Selecting colour: {}", colour);
        clickHealing(PDPLocators.colourLabel(colour), "Select " + colour + " colour");
        log.info("Colour selected: {}", colour);
    }

    public void openZipModal() {
        log.info("Opening zip code modal");
        page.locator(PDPLocators.ZIP_CODE_BUTTON).scrollIntoViewIfNeeded();
        clickHealing(PDPLocators.ZIP_CODE_BUTTON, "Open zip code delivery modal");
        // Wait for input to be ready before proceeding
        waitVisible(PDPLocators.ZIP_INPUT);
    }

    public void enterZipCode(String zip) {
        log.info("Entering zip code: {}", zip);
        fillHealing(PDPLocators.ZIP_INPUT, zip, "Enter zip code");
    }

    public void submitZipCode() {
        log.info("Submitting zip code");
        clickHealing(PDPLocators.ZIP_UPDATE, "Submit zip code");
        // Wait for EDD message — confirms the zip lookup completed
        waitVisible(PDPLocators.EDD_MESSAGE);
    }

    public void dismissZipModal() {
        log.info("Dismissing zip modal");
        clickHealing(PDPLocators.MODAL_CLOSE, "Close zip modal");
        // Wait for modal to be gone before proceeding
        page.waitForSelector(PDPLocators.MODAL_CLOSE,
                new Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN));
    }

    public void addToBag() {
        log.info("Clicking ADD TO BAG");
        // Capture cart count before click so we can wait for it to change
        String countBefore = cartCountText();
        clickHealing(PDPLocators.ADD_TO_BAG_BUTTON, "Add product to cart");
        // Wait for cart count to change — confirms the item was actually added
        page.waitForFunction(
                "([icon, before]) => { const el = document.querySelector(icon); return el && el.textContent.trim() !== before; }",
                new Object[]{HeaderLocators.CART_COUNT, countBefore});
        log.info("ADD TO BAG confirmed — cart updated");
    }

    public String getEDDMessageText() {
        waitVisibleHealing(PDPLocators.EDD_MESSAGE, "EDD message visible");
        return page.locator(PDPLocators.EDD_MESSAGE).textContent();
    }

    // ── Self-healing wrappers ─────────────────────────────────────────────────

    private void clickHealing(String locator, String description) {
        try {
            waitVisible(locator);
            page.click(locator);
        } catch (PlaywrightException e) {
            log.warn("Click failed on [{}] — invoking self-healer", locator);
            TestStep step = TestStep.click(locator, description);
            healingPlan.getSteps().add(step);
            if (healer.heal(page, step, healingPlan)) {
                // Wait for the healed locator before interacting
                waitVisible(step.getLocator());
                page.click(step.getLocator());
            } else {
                throw e;
            }
        }
    }

    private void fillHealing(String locator, String value, String description) {
        try {
            waitVisible(locator);
            page.fill(locator, value);
        } catch (PlaywrightException e) {
            log.warn("Fill failed on [{}] — invoking self-healer", locator);
            TestStep step = TestStep.fill(locator, value, description);
            healingPlan.getSteps().add(step);
            if (healer.heal(page, step, healingPlan)) {
                waitVisible(step.getLocator());
                page.fill(step.getLocator(), value);
            } else {
                throw e;
            }
        }
    }

    private void waitVisibleHealing(String locator, String description) {
        try {
            waitVisible(locator);
        } catch (PlaywrightException e) {
            log.warn("WaitVisible failed on [{}] — invoking self-healer", locator);
            TestStep step = TestStep.assertVisible(locator, description);
            healingPlan.getSteps().add(step);
            if (healer.heal(page, step, healingPlan)) {
                waitVisible(step.getLocator());
            } else {
                throw e;
            }
        }
    }

    private void waitVisible(String selector) {
        page.waitForSelector(selector,
                new Page.WaitForSelectorOptions().setState(WaitForSelectorState.VISIBLE));
    }

    private String cartCountText() {
        try {
            var el = page.locator(HeaderLocators.CART_COUNT);
            return el.count() > 0 ? el.textContent().trim() : "";
        } catch (PlaywrightException e) {
            return "";
        }
    }
}
