package com.ecommerce.experiments;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.ecommerce.config.FrameworkConfig;
import com.ecommerce.experiments.model.TestPlan;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.stream.Collectors;

/**
 * Uses Claude API to enrich a base TestPlan with variant-specific steps.
 *
 * Input  : base plan JSON + full experiment details (all 4 fields)
 * Output : TestPlan with additional/modified steps for the assigned variant,
 *          plus an aiReasoning field explaining what was changed and why.
 */
@Slf4j
public class AITestPlanner {

    private static final String SYSTEM_PROMPT = """
            You are a senior test automation engineer specialising in A/B experiment validation.

            Your task: given a base JSON test plan and the full details of an A/B experiment,
            return an enriched test plan JSON that validates the specific UI change caused by the
            assigned variation.

            Rules:
            1. Copy ALL original base steps verbatim (same id, type, locator, value, description).
            2. Add new steps at the end for the variant-specific assertions. Mark them with "variantStep": true.
            3. Use "assert_not_visible" when the variant hides something, "assert_visible" when it shows something.
            4. Locator values must be realistic data-testid CSS selectors, e.g. [data-testid='location-section'].
            5. Set the "aiReasoning" field: explain in 1-2 sentences what changed and why you added these steps.
            6. Keep the JSON valid — return ONLY the JSON object, no markdown fences, no commentary outside the JSON.
            7. Preserve all existing TestPlan fields (userId, experimentName, assignedVariation, etc.).

            TestStep type values: navigate | click | fill | assert_visible | assert_not_visible | assert_text
            """;

    private final AnthropicClient client;
    private final ObjectMapper mapper;

    public AITestPlanner() {
        this.client = AnthropicOkHttpClient.builder()
                .apiKey(FrameworkConfig.get().getAnthropicApiKey())
                .build();
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * Enriches the base plan with variant-specific steps by calling Claude.
     * The returned plan is a new object — basePlan is not mutated.
     */
    public TestPlan generateVariantPlan(TestPlan basePlan, ExperimentVariant variant) {
        log.info("Calling Claude to generate variant plan for experiment [{}] variation [{}]",
                variant.getExperimentName(), variant.getAssignedVariation());

        String userMessage = buildUserMessage(basePlan, variant);

        MessageCreateParams params = MessageCreateParams.builder()
                .model(Model.CLAUDE_OPUS_4_7)
                .maxTokens(2048)
                .system(SYSTEM_PROMPT)
                .addUserMessage(userMessage)
                .build();

        Message response = client.messages().create(params);

        String rawJson = response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(textBlock -> textBlock.text())
                .collect(Collectors.joining());

        log.debug("Claude raw response:\n{}", rawJson);

        TestPlan enrichedPlan = parseResponse(rawJson, basePlan, variant);
        enrichedPlan.setGeneratedAt(Instant.now().toString());
        enrichedPlan.setExperimentOn(true);
        return enrichedPlan;
    }

    private String buildUserMessage(TestPlan basePlan, ExperimentVariant variant) {
        try {
            String basePlanJson = mapper.writeValueAsString(basePlan);

            String allVariationsText = variant.getAllVariations().entrySet().stream()
                    .map(e -> "  \"" + e.getKey() + "\": \"" + e.getValue() + "\"")
                    .collect(Collectors.joining("\n"));

            return """
                    === BASE TEST PLAN (JSON) ===
                    %s

                    === EXPERIMENT DETAILS ===
                    Experiment Name    : %s
                    All Variations     :
                    %s
                    Assigned Variation : %s
                    Variant Description: %s

                    === YOUR TASK ===
                    Return the enriched TestPlan JSON. Preserve all base steps exactly.
                    Add variant-specific assertion steps based on the experiment change described above.
                    """.formatted(
                    basePlanJson,
                    variant.getExperimentName(),
                    allVariationsText,
                    variant.getAssignedVariation(),
                    variant.getAssignedDescription()
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialise base plan for Claude prompt", e);
        }
    }

    private TestPlan parseResponse(String rawJson, TestPlan basePlan, ExperimentVariant variant) {
        // Strip any accidental markdown fences Claude might include
        String json = rawJson.strip();
        if (json.startsWith("```")) {
            json = json.replaceAll("(?s)^```[a-z]*\\n?", "").replaceAll("```$", "").strip();
        }

        try {
            TestPlan parsed = mapper.readValue(json, TestPlan.class);
            // Ensure identity fields are always set correctly from source of truth
            parsed.setUserId(basePlan.getUserId());
            parsed.setExperimentName(variant.getExperimentName());
            parsed.setAssignedVariation(variant.getAssignedVariation());
            parsed.setVariantDescription(variant.getAssignedDescription());
            parsed.setAllVariations(variant.getAllVariations());
            return parsed;
        } catch (Exception e) {
            log.error("Failed to parse Claude response as TestPlan. Raw response:\n{}", rawJson);
            throw new RuntimeException("Claude returned invalid JSON for test plan", e);
        }
    }
}
