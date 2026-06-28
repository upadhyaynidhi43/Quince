package com.ecommerce.experiments;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.ecommerce.config.FrameworkConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Page;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Uses Chrome DevTools (via Playwright's evaluate()) to extract a condensed DOM
 * snapshot of the live PDP, then asks Claude to identify CSS selectors for the
 * experiment variant's UI elements.
 *
 * Called only when the experiment is ON and a valid Anthropic API key is present.
 * Falls back to null values (stub placeholders) when no API key is configured.
 */
@Slf4j
public class ExperimentLocatorDiscovery {

    private static final String LOCATOR_SYSTEM_PROMPT = """
            You are a senior test automation engineer specialising in Playwright CSS selectors
            for the Quince e-commerce site (quince.com).

            You will receive:
              1. A condensed DOM snapshot of the live Quince PDP page (outer HTML of leaf elements
                 that look interactive or variant-relevant — attributes only, no inner text).
              2. A description of the A/B experiment variant that is active for this page view.

            Your task: identify CSS selectors for the variant's UI elements.

            Rules:
            1. Return ONLY a flat JSON object — no markdown, no commentary:
               {
                 "variantContainer": "<cssSelector or null>",
                 "variantCta":       "<cssSelector or null>",
                 "variantBadge":     "<cssSelector or null>"
               }
            2. Prefer data-testid attributes when present: [data-testid='...']
            3. Use partial class matching [class*='...'] for generated class names.
            4. If a slot is not applicable for this variant set its value to null.
            5. Output only the JSON object — nothing else.
            """;

    // JS run inside the browser via Playwright evaluate() to extract a compact DOM snapshot.
    // Collects outerHTML (attributes only) of interactive and structurally relevant elements
    // that are likely to carry experiment-specific markers.
    private static final String DOM_SNAPSHOT_SCRIPT = """
            () => {
              const SELECTOR = [
                '[data-testid]',
                '[data-experiment]',
                '[data-variant]',
                'button',
                '[class*="cta"]',
                '[class*="badge"]',
                '[class*="urgency"]',
                '[class*="location"]',
                '[class*="delivery"]',
                '[class*="experiment"]',
                '[class*="variant"]'
              ].join(',');

              const seen = new Set();
              const rows = [];

              document.querySelectorAll(SELECTOR).forEach(el => {
                // strip inner content — we only want attributes
                const tag   = el.tagName.toLowerCase();
                const attrs = Array.from(el.attributes)
                  .map(a => `${a.name}="${a.value}"`)
                  .join(' ');
                const line = `<${tag} ${attrs}>`;
                if (!seen.has(line)) { seen.add(line); rows.push(line); }
              });

              // cap at 300 elements to keep the prompt manageable
              return rows.slice(0, 300).join('\\n');
            }
            """;

    private final AnthropicClient client;
    private final ObjectMapper    mapper;
    private final boolean         useClaude;

    public ExperimentLocatorDiscovery() {
        String apiKey = FrameworkConfig.get().getAnthropicApiKey();
        this.useClaude = apiKey != null
                && !apiKey.isBlank()
                && !apiKey.startsWith("sk-ant-your");
        this.client = useClaude
                ? AnthropicOkHttpClient.builder().apiKey(apiKey).build()
                : null;
        this.mapper = new ObjectMapper();
        if (!useClaude) {
            log.info("[ExperimentLocatorDiscovery] No valid API key — variant locators will be null stubs.");
        }
    }

    /**
     * Navigates to the live PDP (page must already be on the PDP URL), extracts a DOM
     * snapshot via JS, and asks Claude to return CSS selectors for the variant's elements.
     *
     * @param page    Playwright page already loaded at the PDP URL
     * @param variant the active experiment variant
     * @return map with keys variantContainer, variantCta, variantBadge
     */
    public Map<String, String> discover(Page page, ExperimentVariant variant) {
        if (!useClaude) {
            return nullStubs();
        }

        log.info("[ExperimentLocatorDiscovery] Extracting DOM snapshot for variant [{}]",
                variant.getAssignedVariation());

        String domSnapshot;
        try {
            domSnapshot = (String) page.evaluate(DOM_SNAPSHOT_SCRIPT);
        } catch (Exception e) {
            log.warn("[ExperimentLocatorDiscovery] DOM snapshot failed — falling back to null stubs", e);
            return nullStubs();
        }

        if (domSnapshot == null || domSnapshot.isBlank()) {
            log.warn("[ExperimentLocatorDiscovery] DOM snapshot was empty — falling back to null stubs");
            return nullStubs();
        }

        log.info("[ExperimentLocatorDiscovery] DOM snapshot captured ({} chars) — calling Claude",
                domSnapshot.length());

        String userMessage = buildPrompt(variant, domSnapshot);
        String rawJson     = callClaude(userMessage);

        try {
            Map<String, String> result = mapper.readValue(
                    stripFences(rawJson),
                    new TypeReference<Map<String, String>>() {});
            log.info("[ExperimentLocatorDiscovery] Claude returned locators: {}", result);
            return result;
        } catch (Exception e) {
            log.warn("[ExperimentLocatorDiscovery] Failed to parse Claude locator response — falling back to null stubs. Raw: {}", rawJson, e);
            return nullStubs();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String buildPrompt(ExperimentVariant variant, String domSnapshot) {
        return """
                === EXPERIMENT VARIANT ===
                Experiment Name     : %s
                Assigned Variation  : %s
                Variant Description : %s

                === LIVE DOM SNAPSHOT (attributes only) ===
                %s
                """.formatted(
                variant.getExperimentName(),
                variant.getAssignedVariation(),
                variant.getAssignedDescription(),
                domSnapshot
        );
    }

    private String callClaude(String userMessage) {
        MessageCreateParams params = MessageCreateParams.builder()
                .model(Model.CLAUDE_OPUS_4_7)
                .maxTokens(512)
                .system(LOCATOR_SYSTEM_PROMPT)
                .addUserMessage(userMessage)
                .build();

        Message response = client.messages().create(params);

        return response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(tb -> tb.text())
                .collect(Collectors.joining());
    }

    private static Map<String, String> nullStubs() {
        Map<String, String> stubs = new LinkedHashMap<>();
        stubs.put("variantContainer", null);
        stubs.put("variantCta",       null);
        stubs.put("variantBadge",     null);
        return stubs;
    }

    private static String stripFences(String raw) {
        String s = raw.strip();
        if (s.startsWith("```")) {
            s = s.replaceAll("(?s)^```[a-z]*\\n?", "").replaceAll("```$", "").strip();
        }
        return s;
    }
}
