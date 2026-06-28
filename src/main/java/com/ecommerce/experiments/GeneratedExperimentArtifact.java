package com.ecommerce.experiments;

import lombok.Getter;

import java.nio.file.Path;
import java.util.Map;

/**
 * Holds the Claude-generated artifacts for one experiment variant:
 *   - the .feature file path (Cucumber reads it directly)
 *   - parsed locator map (step definitions resolve selectors at runtime)
 *   - full ExperimentVariant for rich Allure reporting
 */
@Getter
public class GeneratedExperimentArtifact {

    private final Path featurePath;
    private final String featureContent;
    private final Map<String, String> locators;
    private final ExperimentVariant variant;

    public GeneratedExperimentArtifact(Path featurePath, String featureContent, Map<String, String> locators,
                                       ExperimentVariant variant) {
        this.featurePath       = featurePath;
        this.featureContent    = featureContent;
        this.locators          = locators;
        this.variant           = variant;
    }

    /** Convenience accessor — kept for callers that only need the flag key. */
    public String getExperimentName()   { return variant.getExperimentName(); }
    public String getAssignedVariation(){ return variant.getAssignedVariation(); }

    /** Returns the CSS selector for a semantic name, or null if not present. */
    public String locator(String name) {
        return locators.get(name);
    }
}
