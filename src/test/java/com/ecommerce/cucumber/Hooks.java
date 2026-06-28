package com.ecommerce.cucumber;

import com.ecommerce.config.BrowserFactory;
import com.ecommerce.config.FrameworkConfig;
import com.ecommerce.experiments.AITestPlanner;
import com.ecommerce.experiments.ExperimentUser;
import com.ecommerce.experiments.ExperimentVariant;
import com.ecommerce.experiments.LaunchDarklyClient;
import com.ecommerce.experiments.TestPlanCache;
import com.ecommerce.experiments.TestPlanExecutor;
import com.ecommerce.experiments.model.TestPlan;
import com.ecommerce.tests.product.PDPTestPlans;
import com.ecommerce.utils.ScreenshotUtils;
import com.microsoft.playwright.*;
import io.cucumber.java.After;
import io.cucumber.java.AfterAll;
import io.cucumber.java.Before;
import io.cucumber.java.BeforeAll;
import io.cucumber.java.Scenario;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
public class Hooks {

    private static final Map<String, String> VARIATION_DESCRIPTIONS = new LinkedHashMap<>() {{
        put("hide-location", "PDP page will NOT show location details");
        put("show-location", "PDP page will show location details");
    }};

    // ── Suite-scoped (created once, shared across all scenarios) ─────────────
    private static Playwright         playwright;
    private static Browser            browser;
    private static LaunchDarklyClient ldClient;
    private static TestPlanCache      cache;
    private static AITestPlanner      aiPlanner;
    private static TestPlanExecutor   executor;

    // ── Scenario-scoped (injected by PicoContainer) ───────────────────────────
    private BrowserContext context;
    private final PlaywrightContext  pw;
    private final ExperimentContext  experimentCtx;

    public Hooks(PlaywrightContext pw, ExperimentContext experimentCtx) {
        this.pw            = pw;
        this.experimentCtx = experimentCtx;
    }

    @BeforeAll
    public static void launchSuite() {
        FrameworkConfig cfg = FrameworkConfig.get();
        playwright = Playwright.create();
        browser    = BrowserFactory.createBrowser(playwright);
        ldClient   = new LaunchDarklyClient();
        cache      = new TestPlanCache();
        aiPlanner  = new AITestPlanner();
        executor   = new TestPlanExecutor(cache);
        log.info("Suite started — browser={} env={}",
                cfg.getBrowser(), cfg.getEnvironment());
    }

    @AfterAll
    public static void closeSuite() {
        if (browser != null)    browser.close();
        if (playwright != null) playwright.close();
        if (ldClient != null) {
            try { ldClient.close(); } catch (IOException e) {
                log.warn("Error closing LaunchDarkly client", e);
            }
        }
        log.info("Suite closed.");
    }

    /**
     * Before each scenario:
     *  1. Open a fresh browser page.
     *  2. Evaluate the LD experiment flag for the configured test user.
     *  3. If experiment is ON  → check local cache → load plan if found,
     *                            call LLM and save if not.
     *     If experiment is OFF → mark context so PDPSteps runs the base flow.
     *
     * The feature file and step definitions are completely unaware of this.
     */
    @Before
    public void openScenario() {
        FrameworkConfig cfg = FrameworkConfig.get();

        context = BrowserFactory.createContext(browser);
        Page page = context.newPage();
        page.setDefaultTimeout(cfg.getDefaultTimeoutMs());
        pw.setPage(page);

        resolveExperiment(cfg);
    }

    @After
    public void closeScenario(Scenario scenario) {
        if (scenario.isFailed() && pw.getPage() != null) {
            try {
                Path shot = ScreenshotUtils.capture(pw.getPage(), scenario.getName());
                scenario.attach(Files.readAllBytes(shot), "image/png", "failure-screenshot");
            } catch (IOException e) {
                log.warn("Could not capture screenshot for: {}", scenario.getName(), e);
            }
        }
        if (context != null) context.close();
        log.info("Scenario [{}] — {}", scenario.getStatus(), scenario.getName());
    }

    // ── Experiment resolution — invisible to feature files ───────────────────

    private void resolveExperiment(FrameworkConfig cfg) {
        ExperimentUser user = ExperimentUser.builder()
                .userId(cfg.getTestUserId())
                .email(cfg.getTestUserId() + "@example-shop.com")
                .country(cfg.getTestUserCountry())
                .plan(cfg.getTestUserPlan())
                .build();

        ExperimentVariant variant = ldClient.getExperimentDetails(
                cfg.getExperimentFlagKey(), VARIATION_DESCRIPTIONS, user);

        experimentCtx.setUser(user);
        experimentCtx.setVariant(variant);

        log.info("Experiment [{}] — ON={} variation=[{}] reason=[{}]",
                cfg.getExperimentFlagKey(),
                variant.isExperimentOn(),
                variant.getAssignedVariation(),
                variant.getEvaluationReason());

        if (!variant.isExperimentOn()) {
            log.info("Experiment OFF — base flow will run.");
            experimentCtx.setResolvedPlan(null);
            experimentCtx.setCacheHit(false);
            return;
        }

        String userId    = user.getUserId();
        String flagKey   = cfg.getExperimentFlagKey();
        String variation = variant.getAssignedVariation();

        if (cache.exists(flagKey, userId, variation)) {
            TestPlan plan = cache.load(flagKey, userId, variation)
                    .orElseThrow(() -> new IllegalStateException(
                            "Cache file exists but could not be loaded: "
                            + cache.cacheFilePath(flagKey, userId, variation)));
            experimentCtx.setResolvedPlan(plan);
            experimentCtx.setCacheHit(true);
            log.info("Cache HIT — loaded variant plan locally, LLM skipped. [{}]",
                    cache.cacheFilePath(flagKey, userId, variation));
        } else {
            log.info("Cache MISS — calling LLM to generate variant plan...");
            TestPlan base     = PDPTestPlans.buildBasePlan(userId);
            TestPlan enriched = aiPlanner.generateVariantPlan(base, variant);
            cache.save(enriched);
            experimentCtx.setResolvedPlan(enriched);
            experimentCtx.setCacheHit(false);
            log.info("LLM variant plan saved → {}",
                    cache.cacheFilePath(flagKey, userId, variation));
        }
    }
}
