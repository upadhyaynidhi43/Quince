package com.ecommerce.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;

/**
 * Loads framework configuration from config.yaml (or env overrides).
 * Single instance per JVM — thread-safe via class-loading.
 */
@Slf4j
public class FrameworkConfig {

    private static final FrameworkConfig INSTANCE = load();

    @Getter private final String baseUrl;
    @Getter private final String browser;
    @Getter private final boolean headless;
    @Getter private final int defaultTimeoutMs;
    @Getter private final String screenshotDir;
    @Getter private final String environment;
    @Getter private final String launchDarklySdkKey;
    @Getter private final String anthropicApiKey;
    @Getter private final String testUserId;
    @Getter private final String testUserCountry;
    @Getter private final String testUserPlan;
    @Getter private final String experimentFlagKey;

    private FrameworkConfig(ConfigData data) {
        this.baseUrl             = resolve("BASE_URL",             data.baseUrl);
        this.browser             = resolve("BROWSER",              data.browser);
        this.headless            = Boolean.parseBoolean(resolve("HEADLESS", String.valueOf(data.headless)));
        this.defaultTimeoutMs    = data.defaultTimeoutMs;
        this.screenshotDir       = data.screenshotDir;
        this.environment         = resolve("ENV",                  data.environment);
        this.launchDarklySdkKey  = resolve("LD_SDK_KEY",           data.launchDarklySdkKey);
        this.anthropicApiKey     = resolve("ANTHROPIC_API_KEY",    data.anthropicApiKey);
        this.testUserId          = resolve("TEST_USER_ID",         data.testUserId);
        this.testUserCountry     = resolve("TEST_USER_COUNTRY",    data.testUserCountry);
        this.testUserPlan        = resolve("TEST_USER_PLAN",       data.testUserPlan);
        this.experimentFlagKey   = resolve("EXPERIMENT_FLAG_KEY",  data.experimentFlagKey);
    }

    public static FrameworkConfig get() {
        return INSTANCE;
    }

    private static FrameworkConfig load() {
        try (InputStream is = FrameworkConfig.class
                .getClassLoader()
                .getResourceAsStream("config.yaml")) {
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            ConfigData data = mapper.readValue(is, ConfigData.class);
            log.info("Config loaded — env={} baseUrl={}", data.environment, data.baseUrl);
            return new FrameworkConfig(data);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load config.yaml", e);
        }
    }

    /** Env var overrides yaml value when present. */
    private static String resolve(String envKey, String yamlValue) {
        String env = System.getenv(envKey);
        return (env != null && !env.isBlank()) ? env : yamlValue;
    }

    // Inner POJO mapped from YAML
    static class ConfigData {
        public String baseUrl             = "https://www.example-shop.com";
        public String browser             = "chromium";
        public boolean headless           = true;
        public int defaultTimeoutMs       = 30000;
        public String screenshotDir       = "build/screenshots";
        public String environment         = "staging";
        public String launchDarklySdkKey  = "";
        public String anthropicApiKey     = "";
        public String testUserId          = "user-pdp-001";
        public String testUserCountry     = "IN";
        public String testUserPlan        = "premium";
        public String experimentFlagKey   = "pdp-location-details";
    }
}
