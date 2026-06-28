package com.ecommerce.cucumber;

import com.ecommerce.experiments.ExperimentAssertionResult;
import com.ecommerce.experiments.ExperimentResolver;
import com.ecommerce.experiments.ExperimentUser;
import com.ecommerce.experiments.ExperimentVariant;
import com.ecommerce.experiments.FeatureGenerationContext;
import com.ecommerce.experiments.GeneratedExperimentArtifact;
import com.ecommerce.experiments.model.TestPlan;
import com.microsoft.playwright.Page;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Scenario-scoped state shared between Hooks and step definitions via PicoContainer.
 *
 * setPendingUserId(userId)                       — "Given I am the test user" step
 * resolveForUser(page, ctx, baseLocators)        — navigation step after page loads
 * recordExperimentAssertion(result)              — soft-assert steps after each experiment check
 */
@Getter
@Setter
public class ExperimentContext {

    private static ExperimentResolver resolver;

    public static void init(ExperimentResolver r) {
        resolver = r;
    }

    // ── Scenario-scoped state ────────────────────────────────────────────────

    private String                            pendingUserId;
    private ExperimentUser                    user;
    private ExperimentVariant                 variant;
    private TestPlan                          resolvedPlan;
    private List<GeneratedExperimentArtifact> generatedArtifacts   = Collections.emptyList();
    private List<ExperimentAssertionResult>   assertionResults     = new ArrayList<>();
    private boolean                           cacheHit;

    public void setPendingUserId(String userId) {
        this.pendingUserId = userId;
    }

    /**
     * Resolves all experiment flags for the pending user and populates this context.
     *
     * @param page         Playwright page loaded at the flow URL
     * @param ctx          flow-specific context (page URL, step defs, scenario steps)
     * @param baseLocators flow's existing selector constants
     */
    public void resolveForUser(Page page,
                               FeatureGenerationContext ctx,
                               Map<String, String> baseLocators) {
        if (pendingUserId == null) {
            throw new IllegalStateException("resolveForUser() called before setPendingUserId()");
        }
        ExperimentResolver.ResolvedExperiment result =
                resolver.resolve(pendingUserId, page, ctx, baseLocators);
        this.user               = result.user();
        this.variant            = result.lastVariant();
        this.resolvedPlan       = result.resolvedPlan();
        this.generatedArtifacts = result.artifacts() != null
                                  ? result.artifacts()
                                  : Collections.emptyList();
        this.cacheHit           = result.cacheHit();
        this.assertionResults   = new ArrayList<>();
        // Annotate while test case is active — parameters appear at top of Allure result card
        ExperimentFeatureLogger.annotateTestCase(this.user, this.generatedArtifacts);
    }

    /** Records the outcome of a single experiment-specific assertion (soft-assert). */
    public void recordExperimentAssertion(ExperimentAssertionResult result) {
        assertionResults.add(result);
    }

    public boolean isAnyExperimentOn() {
        return variant != null && variant.isExperimentOn();
    }

    /** Returns the first active variant's flag key for use in assertion attribution. */
    public String activeExperimentFlagKey() {
        return variant != null ? variant.getExperimentName() : "unknown-experiment";
    }

    /** Returns the first active variant's assigned variation for assertion attribution. */
    public String activeExperimentVariation() {
        return variant != null ? variant.getAssignedVariation() : "unknown-variation";
    }
}
