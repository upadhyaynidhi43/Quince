package com.ecommerce.config;

import com.microsoft.playwright.*;
import lombok.extern.slf4j.Slf4j;

/**
 * Creates Playwright Browser instances based on config.
 * Callers own the lifecycle — must close Browser and Playwright themselves.
 */
@Slf4j
public class BrowserFactory {

    private BrowserFactory() {}

    public static Browser createBrowser(Playwright playwright) {
        FrameworkConfig cfg = FrameworkConfig.get();
        BrowserType.LaunchOptions options = new BrowserType.LaunchOptions()
                .setHeadless(cfg.isHeadless());

        log.info("Launching browser={} headless={}", cfg.getBrowser(), cfg.isHeadless());

        return switch (cfg.getBrowser().toLowerCase()) {
            case "firefox"  -> playwright.firefox().launch(options);
            case "webkit"   -> playwright.webkit().launch(options);
            default         -> playwright.chromium().launch(options);
        };
    }

    public static BrowserContext createContext(Browser browser) {
        FrameworkConfig cfg = FrameworkConfig.get();
        return browser.newContext(
                new Browser.NewContextOptions()
                        .setViewportSize(1440, 900)
                        .setIgnoreHTTPSErrors(true)
                        .setBaseURL(cfg.getBaseUrl())
        );
    }
}
