package com.ecommerce.experiments;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.ecommerce.config.FrameworkConfig;
import com.ecommerce.experiments.model.TestPlan;
import com.ecommerce.experiments.model.TestStep;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Page;
import lombok.extern.slf4j.Slf4j;

import java.util.stream.Collectors;

/**
 * Self-healing engine: when a Playwright locator fails, captures the live DOM
 * via page.content() (CDP under the hood) and asks Claude for a corrected locator.
 *
 * On a successful heal:
 *  - Updates TestStep.locator in the plan
 *  - Persists the fixed plan to cache so future runs benefit immediately
 */
@Slf4j
public class SelfHealingEngine {

    private static final String SYSTEM_PROMPT = """
            You are a test automation self-healing engine.

            A Playwright locator failed during test execution. You will receive:
            1. The failed locator string
            2. The step description (what the step is trying to do)
            3. The full page HTML at the time of failure

            Your job: find the correct CSS selector (preferably data-testid) that matches
            the intended element on the given page.

            Respond with ONLY a JSON object — no markdown, no explanation outside JSON:
            {
              "suggestion": "<the corrected CSS selector>",
              "confidence": "high | medium | low",
              "reasoning": "<one sentence why this selector is correct>"
            }

            If you cannot find a reliable match, set confidence to "low" and suggestion to the original locator.
            """;

    private final AnthropicClient client;
    private final TestPlanCache cache;
    private final ObjectMapper mapper;

    public SelfHealingEngine(TestPlanCache cache) {
        this.cache = cache;
        this.client = AnthropicOkHttpClient.builder()
                .apiKey(FrameworkConfig.get().getAnthropicApiKey())
                .build();
        this.mapper = new ObjectMapper();
    }

    /**
     * Attempts to heal a broken step by inspecting the live DOM.
     *
     * @param page        live Playwright page
     * @param failedStep  the step whose locator failed (mutated in-place on success)
     * @param plan        full plan (persisted to cache after healing)
     * @return true if a high/medium confidence fix was found and applied
     */
    public boolean heal(Page page, TestStep failedStep, TestPlan plan) {
        log.warn("Self-healing triggered for step [{}] locator: {}", failedStep.getId(), failedStep.getLocator());

        String pageHtml = page.content();

        // Truncate HTML to stay within context window (~80k chars is safe)
        if (pageHtml.length() > 80_000) {
            pageHtml = pageHtml.substring(0, 80_000) + "\n<!-- HTML TRUNCATED -->";
        }

        String userMessage = """
                Failed locator  : %s
                Step description: %s

                === PAGE HTML ===
                %s
                """.formatted(failedStep.getLocator(), failedStep.getDescription(), pageHtml);

        MessageCreateParams params = MessageCreateParams.builder()
                .model(Model.CLAUDE_OPUS_4_7)
                .maxTokens(512)
                .system(SYSTEM_PROMPT)
                .addUserMessage(userMessage)
                .build();

        Message response = client.messages().create(params);

        String rawJson = response.content().stream()
                .flatMap(b -> b.text().stream())
                .map(textBlock -> textBlock.text())
                .collect(Collectors.joining())
                .strip();

        return applyHeal(rawJson, failedStep, plan);
    }

    private boolean applyHeal(String rawJson, TestStep failedStep, TestPlan plan) {
        try {
            JsonNode node = mapper.readTree(rawJson);
            String suggestion  = node.path("suggestion").asText();
            String confidence  = node.path("confidence").asText("low");
            String reasoning   = node.path("reasoning").asText();

            log.info("Self-heal suggestion: {} (confidence={}) — {}", suggestion, confidence, reasoning);

            if (confidence.equalsIgnoreCase("low") || suggestion.isBlank()) {
                log.warn("Low confidence heal — keeping original locator, step will likely fail");
                return false;
            }

            String originalLocator = failedStep.getLocator();
            failedStep.setLocator(suggestion);

            log.info("Healed locator: [{}] → [{}]", originalLocator, suggestion);

            // Persist the fixed plan so the next run uses the healed locator
            cache.update(plan);
            return true;

        } catch (Exception e) {
            log.error("Self-heal response parse error: {}", rawJson, e);
            return false;
        }
    }
}
