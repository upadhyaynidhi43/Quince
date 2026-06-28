package com.ecommerce.experiments;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.ecommerce.config.FrameworkConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Generates a Cucumber .feature file and companion locators JSON for any experiment
 * variant at runtime. Flow-agnostic — all flow-specific details are supplied via
 * {@link FeatureGenerationContext}.
 *
 * Output directory : temp/experiment-features/
 * Per variant      : {experimentName}__{userId}__{variation}.feature
 *                    {experimentName}__{userId}__{variation}-locators.json
 *
 * Both files are written to temp/ so they are:
 *   - not wiped by Gradle clean (unlike build/)
 *   - reviewable as a diff before promotion
 *   - deleted together when the experiment is promoted or abandoned
 *
 * Generation modes:
 *   - Real mode + valid API key → Claude writes the feature text.
 *   - Mock mode / no valid key  → Template built from FeatureGenerationContext step lines.
 *
 * Cache: both files must exist for a cache hit; delete temp/experiment-features/ to regenerate.
 *
 * Promoting a validated experiment:
 *   1. Copy the scenarios into the base .feature file listed in the context,
 *      removing the promotion header comment.
 *   2. Promote EXPERIMENT_VARIANT_* stubs in the flow's Locators class to permanent constants.
 *   3. Delete both files from temp/experiment-features/.
 */
@Slf4j
public class AIFeatureGenerator {

    private static final String OUTPUT_DIR = "temp/experiment-features";

    private static final String SYSTEM_PROMPT_TEMPLATE = """
            You are a senior QA engineer writing Cucumber BDD feature files for an e-commerce site.

            Your task: given an A/B experiment variant, generate a Cucumber .feature file that tests
            the UI change introduced by that variant on the following page:

            URL under test : %s

            Rules:
            1. Use Gherkin syntax (Feature, Scenario, Given/When/Then/And).
            2. Tag the feature with @experiment, @{experimentName}, and @{variation}.
            3. Include exactly 1 scenario tagged @happy-path — variant UI element is present
               and the full flow completes with the experiment active. Base regression is
               covered by the existing production feature suite; do not duplicate it here.
            4. Use ONLY step definitions from this list:
            %s
            5. For variant-specific assertions use:
                 Then the element with locator "{locator}" should be visible
                 Then the element with locator "{locator}" should not be visible
                 Then the element with locator "{locator}" should contain text "{text}"
               Pass "EXPERIMENT_VARIANT_CONTAINER" as the locator placeholder — the tester
               replaces it with a real CSS selector before promoting the experiment.
            6. Prepend the file with the promotion header shown in the user message.
            7. Output ONLY the raw .feature file content — no markdown fences, no commentary.
            """;

    private final AnthropicClient client;
    private final ObjectMapper    mapper;
    private final Path            outputDir;
    private final boolean         useClaude;

    public AIFeatureGenerator() {
        String apiKey = FrameworkConfig.get().getAnthropicApiKey();
        this.useClaude = !FrameworkConfig.get().isMock()
                && apiKey != null
                && !apiKey.isBlank()
                && !apiKey.startsWith("sk-ant-your");
        this.client = useClaude
                ? AnthropicOkHttpClient.builder().apiKey(apiKey).build()
                : null;
        this.mapper    = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        this.outputDir = Paths.get(OUTPUT_DIR);
        if (!useClaude) {
            log.info("[AIFeatureGenerator] Offline/mock mode — feature built from template (no Claude call).");
        }
    }

    /**
     * Generates (or loads from cache) a .feature file and locators JSON for the variant.
     * Both files land in temp/experiment-features/ alongside each other.
     *
     * @param variant  the resolved experiment variant
     * @param user     the test user
     * @param locators pre-built map of semantic name → CSS selector (owned by the caller)
     * @param ctx      flow-specific context: page URL, available steps, scenario step lines
     */
    public GeneratedExperimentArtifact generate(ExperimentVariant variant,
                                                ExperimentUser user,
                                                Map<String, String> locators,
                                                FeatureGenerationContext ctx) {
        String key          = safeKey(variant.getExperimentName(), user.getUserId(), variant.getAssignedVariation());
        Path featurePath    = outputDir.resolve(key + ".feature");
        Path locatorsPath   = outputDir.resolve(key + "-locators.json");

        // Both files present → full cache hit
        if (Files.exists(featurePath) && Files.exists(locatorsPath)) {
            log.info("[AIFeatureGenerator] Cache HIT — reusing [{}]", key);
            return loadFromDisk(featurePath, locatorsPath, variant);
        }

        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            throw new RuntimeException("Cannot create output dir: " + outputDir, e);
        }

        String featureContent = useClaude
                ? stripFences(callClaude(buildSystemPrompt(ctx), buildUserPrompt(variant, user, ctx)))
                : buildTemplateFeature(variant, user, ctx);

        try {
            Files.writeString(featurePath, featureContent);
            Files.writeString(locatorsPath, mapper.writeValueAsString(locators));
            log.info("[AIFeatureGenerator] Wrote feature  → {}", featurePath.toAbsolutePath());
            log.info("[AIFeatureGenerator] Wrote locators → {} ({} entries, variant* keys are experiment-specific)",
                    locatorsPath.toAbsolutePath(), locators.size());
        } catch (IOException e) {
            throw new RuntimeException("Failed to write experiment files", e);
        }

        return new GeneratedExperimentArtifact(featurePath, featureContent, locators, variant);
    }

    // ── Prompt builders ───────────────────────────────────────────────────────

    private String buildSystemPrompt(FeatureGenerationContext ctx) {
        String stepList = extractStepsFromFeatureFile(ctx.getBaseFeatureFile()).stream()
                .map(s -> "   - " + s)
                .collect(Collectors.joining("\n"));
        return SYSTEM_PROMPT_TEMPLATE.formatted(ctx.getPageUrl(), stepList);
    }

    /**
     * Extracts the first concrete scenario's step lines from the base feature file
     * and substitutes the actual userId for any <userId> outline placeholder.
     * These steps represent the user's intent — they are reused verbatim in the
     * generated happy-path scenario so intent is never manually maintained.
     */
    private String extractBaseScenario(String featureFilePath, String userId) {
        try {
            List<String> lines = Files.readAllLines(Paths.get(featureFilePath));
            List<String> steps = new ArrayList<>();
            boolean inScenario = false;

            for (String line : lines) {
                String trimmed = line.trim();
                // Start collecting at the first Scenario or Scenario Outline
                if (!inScenario && (trimmed.startsWith("Scenario:") || trimmed.startsWith("Scenario Outline:"))) {
                    inScenario = true;
                    continue;
                }
                if (inScenario) {
                    // Stop at Examples table, next Scenario, Feature, or tag line
                    if (trimmed.startsWith("Examples:") || trimmed.startsWith("Scenario")
                            || trimmed.startsWith("Feature:") || trimmed.startsWith("@")) break;
                    if (trimmed.startsWith("Given ") || trimmed.startsWith("When ")
                            || trimmed.startsWith("Then ") || trimmed.startsWith("And ")
                            || trimmed.startsWith("But ")) {
                        // Substitute outline placeholder with the real userId
                        steps.add("    " + trimmed.replace("<userId>", userId));
                    }
                }
            }
            return steps.isEmpty() ? "    # (no steps extracted from base feature)" : String.join("\n", steps);
        } catch (IOException e) {
            log.warn("[AIFeatureGenerator] Could not read base feature [{}]: {}", featureFilePath, e.getMessage());
            return "    # (base feature file not found)";
        }
    }

    /**
     * Reads the base .feature file and extracts unique step patterns for Claude's system prompt.
     * Lines beginning with Given/When/Then/And/But are collected; example table rows and
     * comments are skipped. Falls back to an empty list if the file cannot be read.
     */
    private List<String> extractStepsFromFeatureFile(String featureFilePath) {
        try {
            return Files.readAllLines(Paths.get(featureFilePath)).stream()
                    .map(String::trim)
                    .filter(line -> line.startsWith("Given ")
                                 || line.startsWith("When ")
                                 || line.startsWith("Then ")
                                 || line.startsWith("And ")
                                 || line.startsWith("But "))
                    .distinct()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.warn("[AIFeatureGenerator] Could not read base feature file [{}] — step list will be empty: {}",
                    featureFilePath, e.getMessage());
            return List.of();
        }
    }

    private String buildUserPrompt(ExperimentVariant variant,
                                   ExperimentUser user,
                                   FeatureGenerationContext ctx) {
        String allVariations = variant.getAllVariations().entrySet().stream()
                .map(e -> "  " + e.getKey() + " → " + e.getValue())
                .collect(Collectors.joining("\n"));

        String baseScenario = extractBaseScenario(ctx.getBaseFeatureFile(), user.getUserId());

        return """
                === EXPERIMENT DETAILS ===
                Flow                : %s
                Experiment Name     : %s
                User ID             : %s
                All Variations      :
                %s
                Assigned Variation  : %s
                Variant Description : %s

                === USER INTENT (base scenario from %s) ===
                %s

                === YOUR TASK ===
                The base scenario above expresses the user's intent for this flow.
                Reproduce that intent as a @happy-path scenario, keeping every step
                intact. Then append one or more experiment-specific assertion steps at
                the end to validate the variant UI change — using the locator step
                patterns listed in the system prompt.

                === PROMOTION HEADER (prepend verbatim) ===
                %s
                """.formatted(
                ctx.getFlowName(),
                variant.getExperimentName(),
                user.getUserId(),
                allVariations,
                variant.getAssignedVariation(),
                variant.getAssignedDescription(),
                ctx.getBaseFeatureFile(),
                baseScenario,
                promotionHeader(variant, ctx)
        );
    }

    // ── Template builder (mock / no-API-key mode) ─────────────────────────────

    private String buildTemplateFeature(ExperimentVariant variant,
                                        ExperimentUser user,
                                        FeatureGenerationContext ctx) {
        String expName   = variant.getExperimentName();
        String variation = variant.getAssignedVariation();
        String desc      = variant.getAssignedDescription();

        // Core user intent steps — derived from base feature, run unconditionally
        String coreSteps = extractBaseScenario(ctx.getBaseFeatureFile(), user.getUserId());

        // Experiment-specific assertion block — these are SOFT-ASSERTED at runtime:
        // failures are logged to Allure but never block the core flow.
        String experimentAssertions =
                "    # ── Experiment assertions (soft-asserted — failure logged, not scenario-blocking) ──\n"
              + "    Then the element with locator \"EXPERIMENT_VARIANT_CONTAINER\" should be visible\n";

        return promotionHeader(variant, ctx) + "\n"
                + "@experiment @" + expName + " @" + variation + "\n"
                + "Feature: [" + expName + "] Experiment variant \"" + variation + "\" — " + desc + "\n\n"
                + "  # Core flow: runs to completion regardless of experiment state.\n"
                + "  # Experiment assertions below are soft-asserted (failures logged to Allure only).\n"
                + "  @happy-path\n"
                + "  Scenario: [" + variation + "] " + desc + " — happy path\n"
                + coreSteps + "\n"
                + experimentAssertions + "\n";
    }

    // ── Promotion header ──────────────────────────────────────────────────────

    private String promotionHeader(ExperimentVariant variant, FeatureGenerationContext ctx) {
        return "# ─────────────────────────────────────────────────────────────────────\n"
             + "# EXPERIMENT FILE — DO NOT RUN IN PRODUCTION SUITE UNTIL PROMOTED\n"
             + "# Flow       : " + ctx.getFlowName() + "\n"
             + "# Experiment : " + variant.getExperimentName() + "\n"
             + "# Variation  : " + variant.getAssignedVariation() + "\n"
             + "# Status     : PENDING VALIDATION\n"
             + "# Promote    : Once the experiment is validated:\n"
             + "#              1. Copy the scenarios below into " + ctx.getBaseFeatureFile() + "\n"
             + "#                 and remove this header comment.\n"
             + "#              2. Replace EXPERIMENT_VARIANT_* stubs in " + ctx.getLocatorsClassName() + "\n"
             + "#                 with real CSS selectors and rename to permanent constants.\n"
             + "#              3. Delete both files from temp/experiment-features/.\n"
             + "# NOTE     : Base regression is covered by the existing production suite.\n"
             + "# ─────────────────────────────────────────────────────────────────────\n";
    }

    // ── Claude helper ─────────────────────────────────────────────────────────

    private String callClaude(String systemPrompt, String userMessage) {
        MessageCreateParams params = MessageCreateParams.builder()
                .model(Model.CLAUDE_OPUS_4_7)
                .maxTokens(2048)
                .system(systemPrompt)
                .addUserMessage(userMessage)
                .build();

        Message response = client.messages().create(params);

        return response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(tb -> tb.text())
                .collect(Collectors.joining());
    }

    // ── Disk helpers ──────────────────────────────────────────────────────────

    private GeneratedExperimentArtifact loadFromDisk(Path featurePath, Path locatorsPath, ExperimentVariant variant) {
        try {
            String featureContent = Files.readString(featurePath);
            Map<String, String> locators = mapper.readValue(
                    locatorsPath.toFile(), new TypeReference<Map<String, String>>() {});
            return new GeneratedExperimentArtifact(featurePath, featureContent, locators, variant);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load cached experiment files", e);
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private static String stripFences(String raw) {
        String s = raw.strip();
        if (s.startsWith("```")) {
            s = s.replaceAll("(?s)^```[a-z]*\\n?", "").replaceAll("```$", "").strip();
        }
        return s;
    }

    private static String safeKey(String experimentName, String userId, String variation) {
        return (experimentName + "__" + userId + "__" + variation)
                .replaceAll("[^a-zA-Z0-9_\\-]", "-");
    }
}
