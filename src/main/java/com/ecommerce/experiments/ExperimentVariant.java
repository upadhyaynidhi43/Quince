package com.ecommerce.experiments;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.util.Map;

/**
 * Holds all experiment details resolved for a single user.
 *
 * Fields map directly to the 4 outputs required:
 *  1. experimentOn      — is the experiment flag ON for this user?
 *  2. experimentName    — the flag/experiment key from LaunchDarkly
 *  3. allVariations     — full map of variationKey -> description (experiment details)
 *  4. assignedVariation — which variation key this user was bucketed into
 *  5. assignedDescription — human-readable description of that variation
 */
@Getter
@Builder
@ToString
public class ExperimentVariant {

    /** 1. Is the experiment ON for this user? */
    private final boolean experimentOn;

    /** 2. Experiment/flag key (e.g. "pdp-location-details-experiment") */
    private final String experimentName;

    /** 3. All available variations: variationKey -> description */
    private final Map<String, String> allVariations;

    /** 4. Variation key this user was bucketed into (e.g. "hide-location") */
    private final String assignedVariation;

    /** 4b. Human-readable description of the assigned variation */
    private final String assignedDescription;

    /** Evaluation reason from LD (RULE_MATCH, TARGETING, FALLTHROUGH, etc.) */
    private final String evaluationReason;
}
