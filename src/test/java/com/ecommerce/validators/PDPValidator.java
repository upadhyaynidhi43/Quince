package com.ecommerce.validators;

import com.ecommerce.pages.PDPLocators;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;
import lombok.extern.slf4j.Slf4j;
import org.testng.Assert;

/**
 * Assertion methods for the Product Detail Page.
 * Cart / header assertions live in HeaderValidator.
 */
@Slf4j
public class PDPValidator {

    private final Page page;

    public PDPValidator(Page page) {
        this.page = page;
    }

    public void colourIsActive(String colour) {
        Locator label = page.locator(PDPLocators.colourLabel(colour));
        Assert.assertTrue(label.isVisible(),
                colour + " colour label should be visible after selection");
        log.info("Colour '{}' confirmed active", colour);
    }

    public void eddMessageIsVisible() {
        page.waitForSelector(PDPLocators.EDD_MESSAGE,
                new Page.WaitForSelectorOptions().setState(WaitForSelectorState.VISIBLE));
        Locator edd = page.locator(PDPLocators.EDD_MESSAGE);
        Assert.assertTrue(edd.isVisible(),
                "Estimated delivery message should be visible after zip lookup");
        log.info("EDD message visible: {}", edd.textContent());
    }

    public void eddMessageContains(String expectedText) {
        page.waitForSelector(PDPLocators.EDD_MESSAGE,
                new Page.WaitForSelectorOptions().setState(WaitForSelectorState.VISIBLE));
        String actual = page.locator(PDPLocators.EDD_MESSAGE).textContent();
        Assert.assertTrue(actual.contains(expectedText),
                "EDD message should contain '" + expectedText + "' but was: " + actual);
        log.info("EDD message text confirmed contains: {}", expectedText);
    }

    public void eddMessageIsNotVisible() {
        boolean absent = page.locator(PDPLocators.EDD_MESSAGE).count() == 0
                || !page.locator(PDPLocators.EDD_MESSAGE).first().isVisible();
        Assert.assertTrue(absent, "EDD message should NOT be visible for invalid zip");
        log.info("EDD message confirmed not visible");
    }
}
