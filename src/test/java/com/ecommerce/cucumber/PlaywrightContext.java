package com.ecommerce.cucumber;

import com.microsoft.playwright.Page;
import lombok.Getter;
import lombok.Setter;

/**
 * Shared state holder for a single Cucumber scenario.
 * PicoContainer injects this into every step-def class and Hooks.
 *
 * IMPORTANT: PicoContainer constructs step-def classes BEFORE @Before runs,
 * so pw.getPage() is null at construction time. Step-def classes must call
 * page() at the start of each step method, not in the constructor.
 */
@Getter
@Setter
public class PlaywrightContext {

    private Page page;

    /** Fails fast with a clear message if @Before has not run yet. */
    public Page page() {
        if (page == null) {
            throw new IllegalStateException(
                    "Playwright page is null — @Before hook has not run yet. " +
                    "Do not capture the page in a step-def constructor.");
        }
        return page;
    }
}
