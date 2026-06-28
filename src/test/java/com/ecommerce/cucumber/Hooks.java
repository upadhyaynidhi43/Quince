package com.ecommerce.cucumber;

import com.ecommerce.config.BrowserFactory;
import com.ecommerce.config.FrameworkConfig;
import com.ecommerce.experiments.AIFeatureGenerator;
import com.ecommerce.experiments.AITestPlanner;
import com.ecommerce.experiments.ExperimentResolver;
import com.ecommerce.experiments.ILaunchDarklyClient;
import com.ecommerce.experiments.LaunchDarklyClient;
import com.ecommerce.experiments.MockLaunchDarklyClient;
import com.ecommerce.experiments.TestPlanCache;
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

@Slf4j
public class Hooks {

    // ── Suite-scoped (created once, shared across all scenarios) ─────────────
    private static Playwright          playwright;
    private static Browser             browser;
    private static ILaunchDarklyClient ldClient;

    // ── Scenario-scoped (injected by PicoContainer) ───────────────────────────
    private BrowserContext context;
    private final PlaywrightContext pw;
    private final ExperimentContext experimentCtx;

    public Hooks(PlaywrightContext pw, ExperimentContext experimentCtx) {
        this.pw            = pw;
        this.experimentCtx = experimentCtx;
    }

    @BeforeAll
    public static void launchSuite() {
        FrameworkConfig cfg = FrameworkConfig.get();
        playwright = Playwright.create();
        browser    = BrowserFactory.createBrowser(playwright);

        if (cfg.isMock()) {
            ldClient = new MockLaunchDarklyClient();
            log.info("MOCK MODE — using MockLaunchDarklyClient (no LD SDK key required)");
        } else {
            ldClient = new LaunchDarklyClient();
        }

        ExperimentResolver resolver = new ExperimentResolver(
                cfg,
                ldClient,
                new AIFeatureGenerator(),
                new AITestPlanner(),
                new TestPlanCache()
        );
        ExperimentContext.init(resolver);

        log.info("Suite started — browser={} env={} mock={} users={}",
                cfg.getBrowser(), cfg.getEnvironment(), cfg.isMock(), cfg.getTestUserIds());
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

    @Before
    public void openScenario() {
        FrameworkConfig cfg = FrameworkConfig.get();
        context = BrowserFactory.createContext(browser);
        Page page = context.newPage();
        page.setDefaultTimeout(cfg.getDefaultTimeoutMs());
        pw.setPage(page);
    }

    /**
     * @After — experiment assertions and Allure step injection happen in assertCartVisible()
     * (the last step) while the test case is live. This hook only handles cleanup.
     */
    @After
    public void closeScenario(Scenario scenario) {

        // Log any soft-failed experiment assertions to Cucumber's output (visible in report log tab)
        ExperimentFeatureLogger.logSoftFailuresToScenario(
                scenario,
                experimentCtx.getAssertionResults()
        );

        // Screenshot on core flow failure
        if (scenario.isFailed() && pw.getPage() != null) {
            try {
                Path shot = ScreenshotUtils.capture(pw.getPage(), scenario.getName());
                scenario.attach(Files.readAllBytes(shot), "image/png", "failure-screenshot");
            } catch (IOException e) {
                log.warn("Could not capture screenshot for: {}", scenario.getName(), e);
            }
        }

        if (context != null) context.close();

        log.info("Scenario [{}] — {} — user=[{}]",
                scenario.getStatus(), scenario.getName(),
                experimentCtx.getUser() != null ? experimentCtx.getUser().getUserId() : "unknown");
    }
}
