package com.ecommerce.pages;

/**
 * All CSS / XPath locators for the Product Detail Page.
 * No logic here — only selector strings.
 */
public final class PDPLocators {

    private PDPLocators() {}

    // ── Colour swatches ───────────────────────────────────────────────────────
    public static final String COLOUR_OPTION_CONTAINER = "[data-option-type='Color']";
    public static String colourLabel(String colour) {
        return "[data-option-type='Color'] [data-value='" + colour + "'] label";
    }

    // ── Zip code / delivery ───────────────────────────────────────────────────
    public static final String ZIP_CODE_BUTTON  = "button[class*='zipCodeButton']";
    public static final String ZIP_INPUT        = "input[aria-label='Zip Code']";
    public static final String ZIP_UPDATE       = "button[class*='updateBtn']";
    public static final String MODAL_CLOSE      = "[data-testid='modal-close-button']";
    // Hashed class names break on every redeploy — use text/role-based selector instead
    public static final String EDD_MESSAGE      = "[class*='eddMessageWrapper'], [class*='eddMessage']";

    // ── Cart ──────────────────────────────────────────────────────────────────
    public static final String ADD_TO_BAG_BUTTON = "button:has(span:text(\"ADD TO BAG\"))";
    // Cart icon lives in HeaderLocators — it belongs to the site header, not the PDP

    // ── Experiment variant locators ───────────────────────────────────────────
    // Fill these in once the variant UI is known, then promote them to permanent
    // named constants (or remove) after the experiment is validated.
    public static final String EXPERIMENT_VARIANT_CONTAINER = null; // TODO: set CSS selector for variant wrapper
    public static final String EXPERIMENT_VARIANT_CTA       = null; // TODO: set CSS selector for variant CTA
    public static final String EXPERIMENT_VARIANT_BADGE     = null; // TODO: set CSS selector for variant badge
}
