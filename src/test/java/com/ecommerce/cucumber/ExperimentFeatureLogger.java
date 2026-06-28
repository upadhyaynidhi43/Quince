package com.ecommerce.cucumber;

import com.ecommerce.experiments.ExperimentAssertionResult;
import com.ecommerce.experiments.ExperimentUser;
import com.ecommerce.experiments.ExperimentVariant;
import com.ecommerce.experiments.GeneratedExperimentArtifact;
import io.cucumber.java.Scenario;
import io.qameta.allure.Allure;
import io.qameta.allure.model.Attachment;
import io.qameta.allure.model.Parameter;
import io.qameta.allure.model.Status;
import io.qameta.allure.model.StatusDetails;
import io.qameta.allure.model.StepResult;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Writes experiment metadata into the Allure report.
 *
 * Phase 1 — annotateTestCase()
 *   Called from resolveForUser() (inside a Cucumber step).
 *   Writes userId + active experiment flag/variation as Allure parameters.
 *   Uses updateTestCase() so duplicate calls are idempotent.
 *
 * Phase 2 — injectExperimentDetailsStep()
 *   Called from assertCartVisible() — the LAST step of every scenario.
 *   Test case is still live in Allure lifecycle at this point so updateTestCase() works.
 *   Injects one "Experiment Details" StepResult into testResult.steps — visible in
 *   the main test body, NOT in the teardown section.
 *   Structure:
 *     Experiment Details  [PASSED|FAILED]
 *       ├─ [EXP] pdp-location-details → show-location  [PASSED + attachment]
 *       ├─ [EXP] pdp-add-to-bag-cta → urgency-cta     [PASSED + attachment]
 *       ├─ [ASSERT ✓] [flag] step description          [PASSED]
 *       └─ [ASSERT ✗] [flag] step description          [FAILED + trace]
 *
 * Phase 3 — logSoftFailuresToScenario()
 *   Called from @After. Writes each soft-failed assertion to scenario.log()
 *   so it appears in Allure's "Log" tab under the teardown fixture section.
 */
@Slf4j
public class ExperimentFeatureLogger {

    private ExperimentFeatureLogger() {}

    // ── Phase 1: parameters (called from step, test case active) ─────────────

    public static void annotateTestCase(ExperimentUser user,
                                        List<GeneratedExperimentArtifact> artifacts) {
        if (user == null) return;

        Allure.getLifecycle().updateTestCase(result -> {
            // Idempotent — remove before re-adding so double-calls don't duplicate
            result.getParameters().removeIf(p ->
                    "userId".equals(p.getName())
                    || "experiments".equals(p.getName())
                    || p.getName().startsWith("experiment:"));

            result.getParameters().add(new Parameter()
                    .setName("userId").setValue(user.getUserId()));

            if (artifacts == null || artifacts.isEmpty()) {
                result.getParameters().add(new Parameter()
                        .setName("experiments").setValue("none active"));
            } else {
                for (GeneratedExperimentArtifact a : artifacts) {
                    result.getParameters().add(new Parameter()
                            .setName("experiment:" + a.getExperimentName())
                            .setValue(a.getAssignedVariation() + "  [ON]"));
                }
            }
        });
    }

    // ── Phase 2: "Experiment Details" step (called from last step, test case active) ──

    /**
     * Returns the computed experiment Status so the caller can throw the right exception
     * to force AllureCucumber7Jvm to colour the test row:
     *   FAILED  → caller throws AssertionError  → red row
     *   BROKEN  → caller throws RuntimeException → yellow row
     *   PASSED  → caller does nothing            → green row
     */
    public static Status injectExperimentDetailsStep(ExperimentUser user,
                                                     ExperimentVariant activeVariant,
                                                     List<GeneratedExperimentArtifact> artifacts,
                                                     List<ExperimentAssertionResult> assertionResults) {
        if (user == null) return Status.PASSED;

        List<ExperimentAssertionResult> safeResults =
                assertionResults != null ? assertionResults : List.of();
        List<GeneratedExperimentArtifact> safeArtifacts =
                artifacts != null ? artifacts : List.of();

        long failCount    = safeResults.stream().filter(r -> !r.passed() && !r.skipped()).count();
        long skippedCount = safeResults.stream().filter(ExperimentAssertionResult::skipped).count();
        long passedCount  = safeResults.stream().filter(ExperimentAssertionResult::passed).count();

        // FAILED  = at least one assertion actually failed (red in Allure)
        // BROKEN  = no failures but some assertions skipped/not-configured (yellow in Allure)
        // PASSED  = all assertions passed or no experiments active (green)
        Status parentStatus = failCount > 0   ? Status.FAILED
                            : skippedCount > 0 ? Status.BROKEN
                            : Status.PASSED;

        List<StepResult> children = new ArrayList<>();

        // One child step per active experiment — shows variant details + locator map
        for (GeneratedExperimentArtifact a : safeArtifacts) {
            String attachSource = writeAttachment(
                    "Variant Details: " + a.getExperimentName(),
                    buildVariantDetailText(a), ".txt");

            List<Attachment> attachments = new ArrayList<>();
            if (attachSource != null) {
                attachments.add(new Attachment()
                        .setName("Variant Details: " + a.getExperimentName())
                        .setSource(attachSource)
                        .setType("text/plain"));
            }

            children.add(new StepResult()
                    .setName("[EXP] " + a.getExperimentName() + " → " + a.getAssignedVariation())
                    .setStatus(Status.PASSED)
                    .setParameters(List.of(
                            new Parameter().setName("flag").setValue(a.getExperimentName()),
                            new Parameter().setName("variation").setValue(a.getAssignedVariation()),
                            new Parameter().setName("status").setValue("ON")))
                    .setAttachments(attachments));
        }

        // One child step per assertion result
        for (ExperimentAssertionResult r : safeResults) {
            children.add(buildAssertionStep(r));
        }

        // Summary attachment on the parent step
        String summarySource = writeAttachment(
                "Experiment Summary — " + user.getUserId(),
                buildSummaryText(user, safeArtifacts, safeResults), ".txt");

        List<Attachment> parentAttachments = new ArrayList<>();
        if (summarySource != null) {
            parentAttachments.add(new Attachment()
                    .setName("Experiment Summary — " + user.getUserId())
                    .setSource(summarySource)
                    .setType("text/plain"));
        }

        // Step name carries a one-line summary so it's readable without expanding
        String activeExps   = safeArtifacts.isEmpty() ? "none"
                : safeArtifacts.stream()
                        .map(a -> a.getExperimentName() + "=" + a.getAssignedVariation())
                        .reduce((a, b) -> a + ", " + b).orElse("");
        String assertSummary = safeResults.isEmpty() ? "no assertions"
                : passedCount + "✓  " + failCount + "✗  " + skippedCount + "⊘";
        String stepName = "Experiment Details  [" + activeExps + "]  assertions: " + assertSummary;

        StepResult parentStep = new StepResult()
                .setName(stepName)
                .setStatus(parentStatus)
                .setSteps(children)
                .setAttachments(parentAttachments);

        if (failCount > 0) {
            parentStep.setStatusDetails(new StatusDetails()
                    .setMessage(failCount + " experiment assertion(s) FAILED — core flow unaffected (soft assertions)")
                    .setTrace(buildFailTrace(safeResults)));
        } else if (skippedCount > 0) {
            parentStep.setStatusDetails(new StatusDetails()
                    .setMessage(skippedCount + " variant locator(s) not yet configured in PDPLocators — fill in selectors before promoting experiment"));
        }

        // Only inject the step — do NOT set result.setStatus() here.
        // AllureCucumber7Jvm overwrites any status set via updateTestCase() when it processes
        // TestCaseFinished (which fires after the step returns). The only reliable way to colour
        // the test row is to throw from the step itself: AssertionError → FAILED (red),
        // RuntimeException → BROKEN (yellow). The caller does this after this method returns.
        Allure.getLifecycle().updateTestCase(result -> result.getSteps().add(parentStep));

        log.info("[ExperimentFeatureLogger] Experiment Details step injected — user={} experiments={} assertions={} failed={} skipped={}",
                user.getUserId(), safeArtifacts.size(), safeResults.size(), failCount, skippedCount);

        return parentStatus;
    }

    // ── Phase 3: soft failure log lines (called from @After) ─────────────────

    public static void logSoftFailuresToScenario(Scenario scenario,
                                                 List<ExperimentAssertionResult> assertionResults) {
        if (assertionResults == null) return;
        for (ExperimentAssertionResult r : assertionResults) {
            if (!r.passed() && !r.skipped()) {
                scenario.log("[EXPERIMENT SOFT FAIL]"
                        + "  flag=" + r.flagKey()
                        + "  variation=" + r.variation()
                        + "\n  step: " + r.stepDescription()
                        + "\n  reason: " + r.failureMessage()
                        + "\n  (core flow was NOT affected — soft assertion only)");
                log.warn("[ExperimentFeatureLogger] Soft failure — flag={} variation={} step=\"{}\" reason={}",
                        r.flagKey(), r.variation(), r.stepDescription(), r.failureMessage());
            }
        }
    }

    // ── Internal builders ─────────────────────────────────────────────────────

    private static StepResult buildAssertionStep(ExperimentAssertionResult r) {
        String prefix;
        Status status;
        if (r.passed()) {
            prefix = "[ASSERT ✓] ";
            status = Status.PASSED;
        } else if (r.skipped()) {
            prefix = "[ASSERT ⊘] ";
            status = Status.SKIPPED;
        } else {
            prefix = "[ASSERT ✗] ";
            status = Status.FAILED;
        }

        StepResult step = new StepResult()
                .setName(prefix + "[" + r.flagKey() + "] " + r.stepDescription())
                .setStatus(status)
                .setParameters(List.of(
                        new Parameter().setName("flag").setValue(r.flagKey()),
                        new Parameter().setName("variation").setValue(r.variation())));

        if (!r.passed() && r.failureMessage() != null) {
            step.setStatusDetails(new StatusDetails()
                    .setMessage(r.skipped()
                            ? "Locator not yet configured — fill in PDPLocators before promoting"
                            : "Soft assertion — experiment check failed, core flow unaffected")
                    .setTrace(r.failureMessage()));
        }
        return step;
    }

    private static String buildVariantDetailText(GeneratedExperimentArtifact a) {
        ExperimentVariant v = a.getVariant();
        StringBuilder sb = new StringBuilder();
        sb.append("┌─────────────────────────────────────────────────────\n");
        sb.append("│  EXPERIMENT VARIANT DETAILS\n");
        sb.append("├─────────────────────────────────────────────────────\n");
        sb.append("│  Flag              : ").append(v.getExperimentName()).append("\n");
        sb.append("│  Status            : ").append(v.isExperimentOn() ? "ON" : "OFF").append("\n");
        sb.append("│  Assigned Variation: ").append(v.getAssignedVariation()).append("\n");
        sb.append("│  Description       : ").append(v.getAssignedDescription()).append("\n");
        sb.append("│  Evaluation Reason : ").append(v.getEvaluationReason()).append("\n");
        sb.append("│\n");
        sb.append("│  All Variations:\n");
        if (v.getAllVariations() != null) {
            v.getAllVariations().forEach((key, desc) -> {
                String marker = key.equals(v.getAssignedVariation()) ? " ◀ assigned" : "";
                sb.append("│    • ").append(key).append(" — ").append(desc).append(marker).append("\n");
            });
        }
        sb.append("│\n");
        sb.append("│  Feature File      : ").append(a.getFeaturePath().getFileName()).append("\n");
        sb.append("│\n");
        sb.append("│  Locators:\n");
        for (Map.Entry<String, String> e : a.getLocators().entrySet()) {
            String tag = e.getKey().startsWith("variant") ? "[variant]" : "[base]   ";
            sb.append(String.format("│    %s  %-26s → %s%n",
                    tag, e.getKey(),
                    e.getValue() != null ? e.getValue() : "(not yet configured)"));
        }
        sb.append("└─────────────────────────────────────────────────────\n");
        return sb.toString();
    }

    private static String buildSummaryText(ExperimentUser user,
                                           List<GeneratedExperimentArtifact> artifacts,
                                           List<ExperimentAssertionResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("═══════════════════════════════════════════════\n");
        sb.append("  EXPERIMENT SUMMARY\n");
        sb.append("═══════════════════════════════════════════════\n\n");
        sb.append("  User ID   : ").append(user.getUserId()).append("\n");
        sb.append("  Email     : ").append(user.getEmail()).append("\n\n");

        if (artifacts.isEmpty()) {
            sb.append("  Experiments : all OFF for this user\n\n");
        } else {
            sb.append("  Active Experiments:\n");
            for (GeneratedExperimentArtifact a : artifacts) {
                sb.append("  ┌─ Flag      : ").append(a.getExperimentName()).append("\n");
                sb.append("  │  Variation : ").append(a.getAssignedVariation()).append("\n");
                sb.append("  │  Status    : ON\n");
                sb.append("  └─ Feature   : ").append(a.getFeaturePath().getFileName()).append("\n\n");
            }
        }

        if (results.isEmpty()) {
            sb.append("  Assertions  : none recorded\n\n");
        } else {
            long passed  = results.stream().filter(ExperimentAssertionResult::passed).count();
            long failed  = results.stream().filter(r -> !r.passed() && !r.skipped()).count();
            long skipped = results.stream().filter(ExperimentAssertionResult::skipped).count();
            sb.append("  Assertions  : ").append(passed).append(" passed, ")
              .append(failed).append(" failed, ").append(skipped).append(" skipped\n\n");

            for (ExperimentAssertionResult r : results) {
                String icon = r.passed() ? "✓" : r.skipped() ? "⊘" : "✗";
                sb.append("  ").append(icon)
                  .append(" [").append(r.flagKey()).append("] [").append(r.variation()).append("] ")
                  .append(r.stepDescription()).append("\n");
                if (r.failureMessage() != null) {
                    sb.append("      → ").append(r.skipped() ? "SKIPPED" : "FAILED")
                      .append(": ").append(r.failureMessage()).append("\n");
                }
            }
        }
        sb.append("\n═══════════════════════════════════════════════\n");
        return sb.toString();
    }

    private static String buildFailTrace(List<ExperimentAssertionResult> results) {
        StringBuilder sb = new StringBuilder();
        for (ExperimentAssertionResult r : results) {
            if (!r.passed() && !r.skipped()) {
                sb.append("[").append(r.flagKey()).append("] ")
                  .append(r.stepDescription()).append("\n")
                  .append("  Reason: ").append(r.failureMessage()).append("\n\n");
            }
        }
        return sb.toString().trim();
    }

    private static String writeAttachment(String name, String content, String extension) {
        try {
            String source = Allure.getLifecycle().prepareAttachment(name, "text/plain", extension);
            Allure.getLifecycle().writeAttachment(source,
                    new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
            return source;
        } catch (Exception e) {
            log.warn("[ExperimentFeatureLogger] Could not write attachment '{}': {}", name, e.getMessage());
            return null;
        }
    }
}
