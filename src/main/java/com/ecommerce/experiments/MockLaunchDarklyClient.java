package com.ecommerce.experiments;

import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Offline mock for LaunchDarklyClient — no SDK key or network needed.
 *
 * Fixture data:
 *   Experiment 1 : "pdp-location-details"
 *     variations : variantA = "show-location", variantB = "hide-location"
 *   Experiment 2 : "pdp-add-to-bag-cta"
 *     variations : variantA = "standard-cta",  variantB = "urgency-cta"
 *
 *   user-mock-001 → exp1 (variantA) + exp2 (variantB)   — in BOTH experiments
 *   user-mock-002 → exp2 (variantB) only                 — NOT in exp1
 *
 * Activate with: ./gradlew cucumberTest -Pmock=true
 */
@Slf4j
public class MockLaunchDarklyClient implements ILaunchDarklyClient {

    // ── Experiment keys ───────────────────────────────────────────────────────
    public static final String EXP_1 = "pdp-location-details";
    public static final String EXP_2 = "pdp-add-to-bag-cta";

    // ── User keys ─────────────────────────────────────────────────────────────
    public static final String USER_1 = "user-mock-001";
    public static final String USER_2 = "user-mock-002";

    // ── Variation keys ────────────────────────────────────────────────────────
    public static final String EXP1_VARIANT_A = "show-location";
    public static final String EXP1_VARIANT_B = "hide-location";
    public static final String EXP2_VARIANT_A = "standard-cta";
    public static final String EXP2_VARIANT_B = "urgency-cta";

    // ── Fixture table: userId -> flagKey -> assigned variation ────────────────
    // user-mock-001 : in exp1 (variantA) AND exp2 (variantB)
    // user-mock-002 : NOT in exp1        AND exp2 (variantB)
    private static final Map<String, Map<String, String>> FIXTURE = Map.of(
            USER_1, Map.of(
                    EXP_1, EXP1_VARIANT_A,
                    EXP_2, EXP2_VARIANT_B
            ),
            USER_2, Map.of(
                    EXP_2, EXP2_VARIANT_B
                    // EXP_1 intentionally absent → experiment OFF for this user
            )
    );

    // ── Default variation descriptions (used when caller doesn't supply them) ─
    private static final Map<String, Map<String, String>> DEFAULT_DESCRIPTIONS = Map.of(
            EXP_1, new LinkedHashMap<>(Map.of(
                    EXP1_VARIANT_A, "PDP shows full location / delivery details",
                    EXP1_VARIANT_B, "PDP hides location details (control)"
            )),
            EXP_2, new LinkedHashMap<>(Map.of(
                    EXP2_VARIANT_A, "Standard 'Add to Bag' CTA (control)",
                    EXP2_VARIANT_B, "Urgency CTA — 'Add to Bag · Only 3 left!'"
            ))
    );

    @Override
    public ExperimentVariant getExperimentDetails(
            String flagKey,
            Map<String, String> variationDescriptions,
            ExperimentUser user) {

        String userId = user.getUserId();
        Map<String, String> userFixture = FIXTURE.getOrDefault(userId, Map.of());
        boolean experimentOn = userFixture.containsKey(flagKey);
        String assignedVariation = userFixture.getOrDefault(flagKey, "control");

        Map<String, String> descriptions = (variationDescriptions != null && !variationDescriptions.isEmpty())
                ? variationDescriptions
                : DEFAULT_DESCRIPTIONS.getOrDefault(flagKey, Map.of());

        String assignedDescription = descriptions.getOrDefault(
                assignedVariation, "No description mapped for variation: " + assignedVariation);

        log.info("[MOCK] LD Experiment [{}] for user [{}]: ON={} | variation={}",
                flagKey, userId, experimentOn, assignedVariation);

        return ExperimentVariant.builder()
                .experimentOn(experimentOn)
                .experimentName(flagKey)
                .allVariations(new LinkedHashMap<>(descriptions))
                .assignedVariation(assignedVariation)
                .assignedDescription(assignedDescription)
                .evaluationReason(experimentOn ? "MOCK_RULE_MATCH" : "MOCK_OFF")
                .build();
    }

    @Override
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
    public void close() {
        log.info("[MOCK] MockLaunchDarklyClient closed (no-op).");
    }
}
