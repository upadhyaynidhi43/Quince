package com.ecommerce.experiments;

import com.ecommerce.config.FrameworkConfig;
import com.launchdarkly.sdk.EvaluationDetail;
import com.launchdarkly.sdk.EvaluationReason;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.server.LDClient;
import com.launchdarkly.sdk.server.LDConfig;
import lombok.extern.slf4j.Slf4j;

import java.io.Closeable;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin wrapper around the LaunchDarkly server-side SDK.
 *
 * Lifecycle: one instance per test suite — init once in @BeforeSuite,
 * close in @AfterSuite. Thread-safe (LDClient is thread-safe by design).
 *
 * Core method: getExperimentDetails(flagKey, variationDescriptions, user)
 *
 * How LD multivariate flags map to our use-case:
 *   - Flag key        → experiment name
 *   - Flag ON/OFF     → is the experiment active for this user
 *   - Variation value → the JSON/string key that identifies which variant
 *   - variationDescriptions (passed in) → human-readable label per variant key
 */
@Slf4j
public class LaunchDarklyClient implements Closeable {

    private final LDClient ldClient;

    public LaunchDarklyClient() {
        String sdkKey = FrameworkConfig.get().getLaunchDarklySdkKey();
        LDConfig config = new LDConfig.Builder()
                .startWait(java.time.Duration.ofSeconds(10))
                .build();
        this.ldClient = new LDClient(sdkKey, config);

        if (!ldClient.isInitialized()) {
            log.warn("LaunchDarkly client did not fully initialize — check SDK key or network.");
        } else {
            log.info("LaunchDarkly client initialized successfully.");
        }
    }

    /**
     * Resolves all 4 experiment details for a user against one flag.
     *
     * @param flagKey               LD feature flag key  (e.g. "pdp-location-details")
     * @param variationDescriptions map of variationValue -> human description
     *                              e.g. { "hide-location" -> "PDP will NOT show location details",
     *                                     "show-location" -> "PDP will show location details" }
     * @param user                  the test user to evaluate the flag for
     * @return                      ExperimentVariant with all 4 fields populated
     */
    public ExperimentVariant getExperimentDetails(
            String flagKey,
            Map<String, String> variationDescriptions,
            ExperimentUser user) {

        LDContext context = user.toLDContext();

        // --- 1. Is experiment ON for this user? ---
        // LD's boolVariationDetail tells us if the flag is on AND why.
        // For multivariate flags we use stringVariationDetail for the variation value.
        boolean isOn = ldClient.boolVariation(flagKey + "-enabled", context, false);

        // --- 4. Which variation is the user in? (string multivariate flag) ---
        EvaluationDetail<String> detail = ldClient.stringVariationDetail(flagKey, context, "control");

        String assignedVariation  = detail.getValue();
        EvaluationReason reason   = detail.getReason();
        String reasonStr          = reason != null ? reason.getKind().name() : "UNKNOWN";

        // If flag is OFF entirely, LD returns the off-variation — mark experiment as off
        boolean experimentOn = isOn && reason != null
                && reason.getKind() != EvaluationReason.Kind.OFF
                && reason.getKind() != EvaluationReason.Kind.ERROR;

        // --- 3. Description for assigned variation ---
        String assignedDescription = variationDescriptions.getOrDefault(
                assignedVariation, "No description mapped for variation: " + assignedVariation);

        log.info("LD Experiment [{}] for user [{}]: ON={} | variation={} | reason={}",
                flagKey, user.getUserId(), experimentOn, assignedVariation, reasonStr);

        return ExperimentVariant.builder()
                .experimentOn(experimentOn)
                .experimentName(flagKey)
                .allVariations(new LinkedHashMap<>(variationDescriptions))
                .assignedVariation(assignedVariation)
                .assignedDescription(assignedDescription)
                .evaluationReason(reasonStr)
                .build();
    }

    /**
     * Resolves experiment details for a user across multiple flags at once.
     * Returns one ExperimentVariant per flag key.
     */
    public List<ExperimentVariant> getExperimentDetails(
            List<String> flagKeys,
            Map<String, Map<String, String>> variationDescriptionsByFlag,
            ExperimentUser user) {

        return flagKeys.stream()
                .map(flag -> getExperimentDetails(
                        flag,
                        variationDescriptionsByFlag.getOrDefault(flag, Map.of()),
                        user))
                .toList();
    }

    @Override
    public void close() throws IOException {
        if (ldClient != null) {
            ldClient.close();
            log.info("LaunchDarkly client closed.");
        }
    }
}
