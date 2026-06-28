package com.ecommerce.experiments.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Top-level test plan — serialised to/from JSON in build/experiment-cache/.
 *
 * Cache filename pattern: {experimentName}__{userId}__{assignedVariation}.json
 * Base plan (no experiment): steps only, experimentName = "base"
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TestPlan {

    /** User ID this plan was generated for */
    private String userId;

    /** LD flag key, e.g. "pdp-location-details". "base" for control plan. */
    private String experimentName;

    /** Variation the user is in, e.g. "hide-location". Null when experiment is OFF. */
    private String assignedVariation;

    /** Human-readable description of the assigned variation */
    private String variantDescription;

    /** All variations in this experiment: variationKey -> description */
    private Map<String, String> allVariations;

    /** Whether the experiment was ON for this user when the plan was generated */
    private boolean experimentOn;

    /** ISO-8601 timestamp when this plan was generated */
    private String generatedAt;

    /** AI reasoning — why these steps were added/modified for this variant */
    private String aiReasoning;

    /** Ordered list of steps to execute */
    @Builder.Default
    private List<TestStep> steps = new ArrayList<>();
}
