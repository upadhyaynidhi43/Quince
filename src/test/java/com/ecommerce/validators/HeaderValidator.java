package com.ecommerce.validators;

import com.ecommerce.pages.HeaderLocators;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;
import lombok.extern.slf4j.Slf4j;
import org.testng.Assert;

/**
 * Assertion methods for the site-wide header.
 */
@Slf4j
public class HeaderValidator {

    private final Page page;

    public HeaderValidator(Page page) {
        this.page = page;
    }

    public void cartIconIsVisible() {
        page.waitForSelector(HeaderLocators.CART_ICON,
                new Page.WaitForSelectorOptions().setState(WaitForSelectorState.VISIBLE));
        Assert.assertTrue(page.locator(HeaderLocators.CART_ICON).isVisible(),
                "Cart icon should be visible in the header");
        log.info("Cart icon confirmed visible in header");
    }

    public void cartCountEquals(String expectedCount) {
        page.waitForSelector(HeaderLocators.CART_COUNT,
                new Page.WaitForSelectorOptions().setState(WaitForSelectorState.VISIBLE));
        String actual = page.locator(HeaderLocators.CART_COUNT).textContent().trim();
        Assert.assertEquals(actual, expectedCount,
                "Cart count should be " + expectedCount + " but was " + actual);
        log.info("Cart count confirmed: {}", actual);
    }
}
