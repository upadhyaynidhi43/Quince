package com.ecommerce.experiments;

import lombok.Builder;
import lombok.Getter;

/**
 * Flow-specific context handed to AIFeatureGenerator so it stays generic.
 *
 * Contains only the four things AIFeatureGenerator cannot infer on its own:
 *   - flowName          : human label for logs and the promotion header
 *   - pageUrl           : URL under test (embedded in Claude's system prompt)
 *   - locatorsClassName : class to update when promoting experiment locators
 *   - baseFeatureFile   : path to the base .feature file for this flow
 *
 * AIFeatureGenerator reads the base .feature file to derive step definitions
 * (for Claude's prompt) and the happy-path scenario steps (for the template).
 * Nothing is maintained manually here.
 */
@Getter
@Builder
public class FeatureGenerationContext {

    /** Human-readable flow label — appears in logs and the promotion header (e.g. "PDP"). */
    private final String flowName;

    /** URL loaded in the browser for this flow — embedded in Claude's system prompt. */
    private final String pageUrl;

    /** Class name that holds locators for this flow — used in the promotion header comment. */
    private final String locatorsClassName;

    /** Feature file name that is the base for this flow — used in the promotion header comment. */
    private final String baseFeatureFile;

}
