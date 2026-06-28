package com.ecommerce.experiments;

import lombok.Getter;

import java.nio.file.Path;
import java.util.Map;

/**
 * Holds the Claude-generated artifacts for one experiment variant:
 *   - the .feature file path (Cucumber reads it directly)
 *   - parsed locator map (step definitions resolve selectors at runtime)
 *   - experimentName / assignedVariation for Allure reporting
 */
@Getter
public class GeneratedExperimentArtifact {

    private final Path featurePath;
    private final String featureContent;
    private final Map<String, String> locators;
    private final String experimentName;
    private final String assignedVariation;

    public GeneratedExperimentArtifact(Path featurePath, String featureContent, Map<String, String> locators,
                                       String experimentName, String assignedVariation) {
        this.featurePath        = featurePath;
        this.featureContent     = featureContent;
        this.locators           = locators;
        this.experimentName     = experimentName;
        this.assignedVariation  = assignedVariation;
    }

    /** Returns the CSS selector for a semantic name, or null if not present. */
    public String locator(String name) {
        return locators.get(name);
    }
}
