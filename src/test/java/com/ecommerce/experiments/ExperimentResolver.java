package com.ecommerce.experiments;

import com.ecommerce.config.FrameworkConfig;
import com.ecommerce.experiments.model.TestPlan;
import com.ecommerce.tests.product.PDPTestPlans;
import com.microsoft.playwright.Page;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Suite-scoped service that resolves experiment state for a given user across ALL
 * configured flag keys.
 *
 * This class is flow-agnostic. It knows nothing about PDP, Checkout, Cart, etc.
 * The caller supplies:
 *   - a FeatureGenerationContext  (page URL, step definitions, scenario steps)
 *   - a baseLocators map          (flow's existing selector constants)
 *
 * ExperimentResolver adds variant-specific locators via ExperimentLocatorDiscovery
 * (a live DOM snapshot + Claude), then delegates file generation to AIFeatureGenerator.
 */
@Slf4j
public class ExperimentResolver {

    // All experiment flag keys + their variation descriptions.
    // Add a new entry here when a new experiment is introduced.
    private static final Map<String, Map<String, String>> ALL_FLAGS = new LinkedHashMap<>() {{
        put(MockLaunchDarklyClient.EXP_1, new LinkedHashMap<>() {{
            put(MockLaunchDarklyClient.EXP1_VARIANT_A, "PDP page will show location details");
            put(MockLaunchDarklyClient.EXP1_VARIANT_B, "PDP page will NOT show location details");
        }});
        put(MockLaunchDarklyClient.EXP_2, new LinkedHashMap<>() {{
            put(MockLaunchDarklyClient.EXP2_VARIANT_A, "Standard 'Add to Bag' CTA (control)");
            put(MockLaunchDarklyClient.EXP2_VARIANT_B, "Urgency CTA — 'Add to Bag · Only 3 left!'");
        }});
    }};

    private final FrameworkConfig            cfg;
    private final ILaunchDarklyClient        ldClient;
    private final AIFeatureGenerator         featureGenerator;
    private final ExperimentLocatorDiscovery locatorDiscovery;
    private final AITestPlanner              aiPlanner;
    private final TestPlanCache              cache;

    public ExperimentResolver(FrameworkConfig cfg,
                              ILaunchDarklyClient ldClient,
                              AIFeatureGenerator featureGenerator,
                              AITestPlanner aiPlanner,
                              TestPlanCache cache) {
        this.cfg              = cfg;
        this.ldClient         = ldClient;
        this.featureGenerator = featureGenerator;
        this.locatorDiscovery = new ExperimentLocatorDiscovery();
        this.aiPlanner        = aiPlanner;
        this.cache            = cache;
    }

    /**
     * Resolves experiment state for the given user across ALL configured flag keys.
     *
     * For each flag that is ON:
     *   - Merges baseLocators with variant-specific locators discovered from the live DOM.
     *   - Generates a .feature + -locators.json pair in temp/experiment-features/.
     *
     * @param userId       the test user identifier
     * @param page         Playwright page loaded at the flow URL (used for DOM snapshot)
     * @param ctx          flow-specific context: page URL, step definitions, scenario steps
     * @param baseLocators flow's existing selector constants (built by the calling step class)
     */
    public ResolvedExperiment resolve(String userId,
                                      Page page,
                                      FeatureGenerationContext ctx,
                                      Map<String, String> baseLocators) {
        ExperimentUser user = ExperimentUser.builder()
                .userId(userId)
                .email(userId + "@example-shop.com")
                .country(cfg.getTestUserCountry())
                .plan(cfg.getTestUserPlan())
                .build();

        List<GeneratedExperimentArtifact> artifacts   = new ArrayList<>();
        TestPlan                          resolvedPlan = null;
        boolean                           cacheHit     = false;
        ExperimentVariant                 lastVariant  = null;

        for (Map.Entry<String, Map<String, String>> entry : ALL_FLAGS.entrySet()) {
            String              flagKey = entry.getKey();
            Map<String, String> descs   = entry.getValue();

            ExperimentVariant variant = ldClient.getExperimentDetails(flagKey, descs, user);
            lastVariant = variant;

            log.info("Experiment [{}] for user [{}] — ON={} variation=[{}] reason=[{}]",
                    flagKey, userId, variant.isExperimentOn(),
                    variant.getAssignedVariation(), variant.getEvaluationReason());

            if (!variant.isExperimentOn()) {
                log.info("User [{}] flag [{}] → OFF — skipping.", userId, flagKey);
                continue;
            }

            if (cfg.isMock()) {
                // Merge base locators with variant-specific ones discovered from the live DOM
                Map<String, String> locators = mergeLocators(baseLocators, variant, page);
                GeneratedExperimentArtifact artifact = featureGenerator.generate(variant, user, locators, ctx);
                artifacts.add(artifact);
                log.info("User [{}] flag [{}] → feature: {} | locators: {} entries",
                        userId, flagKey, artifact.getFeaturePath().getFileName(),
                        artifact.getLocators().size());
            } else {
                String variation = variant.getAssignedVariation();
                if (cache.exists(flagKey, userId, variation)) {
                    resolvedPlan = cache.load(flagKey, userId, variation)
                            .orElseThrow(() -> new IllegalStateException(
                                    "Cache file missing after exists(): "
                                    + cache.cacheFilePath(flagKey, userId, variation)));
                    cacheHit = true;
                    log.info("User [{}] flag [{}] → Cache HIT [{}]",
                            userId, flagKey, cache.cacheFilePath(flagKey, userId, variation));
                } else {
                    log.info("User [{}] flag [{}] → Cache MISS — calling LLM...", userId, flagKey);
                    TestPlan base = PDPTestPlans.buildBasePlan(userId);
                    resolvedPlan  = aiPlanner.generateVariantPlan(base, variant);
                    cache.save(resolvedPlan);
                    log.info("User [{}] flag [{}] → LLM plan saved → {}",
                            userId, flagKey, cache.cacheFilePath(flagKey, userId, variation));
                }
            }
        }

        if (artifacts.isEmpty() && resolvedPlan == null) {
            log.info("User [{}] → no experiments ON — base flow will run.", userId);
        }

        return new ResolvedExperiment(user, lastVariant, resolvedPlan, artifacts, cacheHit);
    }

    /**
     * Merges the caller-supplied base locators with variant-specific locators
     * discovered from the live DOM via Claude. Base locators always win on conflict.
     */
    private Map<String, String> mergeLocators(Map<String, String> baseLocators,
                                              ExperimentVariant variant,
                                              Page page) {
        // Start with base locators, then let discovered selectors fill any null slots
        Map<String, String> merged = new LinkedHashMap<>(baseLocators);
        Map<String, String> discovered = locatorDiscovery.discover(page, variant);
        discovered.forEach((k, v) -> {
            if (v != null) merged.merge(k, v, (existing, fresh) -> existing != null ? existing : fresh);
        });
        log.info("[ExperimentResolver] Locator map: {} entries for variant [{}]",
                merged.size(), variant.getAssignedVariation());
        return merged;
    }

    // ── Value object returned by resolve() ───────────────────────────────────

    public record ResolvedExperiment(
            ExperimentUser user,
            ExperimentVariant lastVariant,
            TestPlan resolvedPlan,
            List<GeneratedExperimentArtifact> artifacts,
            boolean cacheHit) {}
}
