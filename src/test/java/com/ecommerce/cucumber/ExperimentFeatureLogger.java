package com.ecommerce.cucumber;

import com.ecommerce.experiments.ExperimentAssertionResult;
import com.ecommerce.experiments.ExperimentUser;
import com.ecommerce.experiments.ExperimentVariant;
import com.ecommerce.experiments.GeneratedExperimentArtifact;
import io.cucumber.java.Scenario;
import io.qameta.allure.Allure;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Writes experiment metadata into the Allure report.
 *
 * Two-phase approach:
 *
 *   1. annotateTestCase() — called from the navigation step while the test case is
 *      ACTIVE in the Allure lifecycle. Adds experiment flag + variation as Allure
 *      parameters so they appear at the top of every test result card.
 *
 *   2. logExperimentSummary() — called from @After once assertion results are collected.
 *      - Attaches a formatted text block (summary + per-assertion outcomes).
 *      - Uses scenario.log() for each soft-failed assertion — this fires Cucumber's
 *        WriteEvent which AllureCucumber7Jvm routes to lifecycle.addAttachment(),
 *        reliably landing in the @After fixture section regardless of lifecycle state.
 *      - Never calls startStep/stopStep — those attach to the wrong container in @After.
 */
@Slf4j
public class ExperimentFeatureLogger {

    private ExperimentFeatureLogger() {}

    // ── Phase 1: annotate while test case is live ─────────────────────────────

    /**
     * Adds experiment metadata as Allure parameters.
     * Must be called from a step definition (not @After) so the test case item is active.
     * Parameters appear prominently at the top of the test result card in Allure UI.
     */
    public static void annotateTestCase(ExperimentUser user,
                                        List<GeneratedExperimentArtifact> artifacts) {
        if (user == null) return;

        Allure.parameter("userId", user.getUserId());

        if (artifacts == null || artifacts.isEmpty()) {
            Allure.parameter("experiments", "none active");
        } else {
            for (GeneratedExperimentArtifact a : artifacts) {
                Allure.parameter(
                        "experiment:" + a.getExperimentName(),
                        a.getAssignedVariation() + "  [ON]"
                );
            }
        }
    }

    // ── Phase 2: summary + soft-failure logging from @After ──────────────────

    /**
     * Attaches a full experiment summary and logs each soft-failed assertion.
     * Called from @After — uses only scenario.log() and Allure.addAttachment(),
     * both of which work correctly inside a Cucumber @After fixture context.
     */
    public static void logExperimentSummary(Scenario scenario,
                                            ExperimentUser user,
                                            ExperimentVariant activeVariant,
                                            List<GeneratedExperimentArtifact> artifacts,
                                            List<ExperimentAssertionResult> assertionResults) {
        if (user == null) return;

        // ── Build the formatted summary block ─────────────────────────────
        StringBuilder sb = new StringBuilder();
        sb.append("═══════════════════════════════════════════════\n");
        sb.append("  EXPERIMENT SUMMARY\n");
        sb.append("═══════════════════════════════════════════════\n\n");
        sb.append("  User ID   : ").append(user.getUserId()).append("\n");
        sb.append("  Email     : ").append(user.getEmail()).append("\n\n");

        if (artifacts == null || artifacts.isEmpty()) {
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

        if (assertionResults == null || assertionResults.isEmpty()) {
            sb.append("  Assertions  : none recorded\n\n");
        } else {
            long passed  = assertionResults.stream().filter(ExperimentAssertionResult::passed).count();
            long failed  = assertionResults.stream().filter(r -> !r.passed() && !r.skipped()).count();
            long skipped = assertionResults.stream().filter(ExperimentAssertionResult::skipped).count();

            sb.append("  Assertions  : ")
              .append(passed).append(" passed, ")
              .append(failed).append(" failed, ")
              .append(skipped).append(" skipped\n\n");

            for (ExperimentAssertionResult r : assertionResults) {
                String icon = r.passed() ? "✓" : r.skipped() ? "⊘" : "✗";
                sb.append("  ").append(icon)
                  .append(" [").append(r.flagKey()).append("] [").append(r.variation()).append("] ")
                  .append(r.stepDescription()).append("\n");
                if (r.failureMessage() != null) {
                    sb.append("      → ")
                      .append(r.skipped() ? "SKIPPED" : "FAILED")
                      .append(": ").append(r.failureMessage()).append("\n");
                }
            }
            sb.append("\n");
        }
        sb.append("═══════════════════════════════════════════════\n");

        // Attach full summary — goes into the @After teardown section in Allure
        Allure.addAttachment(
                "Experiment Summary — " + user.getUserId(),
                "text/plain",
                new ByteArrayInputStream(sb.toString().getBytes(StandardCharsets.UTF_8)),
                ".txt"
        );

        // Attach raw feature + locator files per active experiment
        if (artifacts != null) {
            for (GeneratedExperimentArtifact a : artifacts) {
                String fileName = a.getFeaturePath().getFileName().toString();

                Allure.addAttachment(
                        "Experiment Feature: " + fileName,
                        "text/plain",
                        new ByteArrayInputStream(a.getFeatureContent()
                                .getBytes(StandardCharsets.UTF_8)),
                        ".feature"
                );

                StringBuilder locTxt = new StringBuilder();
                locTxt.append("Locators for: ").append(fileName).append("\n\n");
                for (Map.Entry<String, String> e : a.getLocators().entrySet()) {
                    locTxt.append(String.format("  %-28s → %s%n", e.getKey(), e.getValue()));
                }
                Allure.addAttachment(
                        "Experiment Locators: " + fileName,
                        "text/plain",
                        new ByteArrayInputStream(locTxt.toString()
                                .getBytes(StandardCharsets.UTF_8)),
                        ".txt"
                );
            }
        }

        // Log each soft-failed assertion via scenario.log() — fires Cucumber WriteEvent,
        // which AllureCucumber7Jvm routes to lifecycle.addAttachment() reliably from @After
        if (assertionResults != null) {
            for (ExperimentAssertionResult r : assertionResults) {
                if (!r.passed() && !r.skipped()) {
                    String msg = "[EXPERIMENT SOFT FAIL]"
                            + " flag=" + r.flagKey()
                            + " variation=" + r.variation()
                            + " step=\"" + r.stepDescription() + "\""
                            + "\n  Reason: " + r.failureMessage()
                            + "\n  (core flow was NOT affected — soft assertion only)";
                    scenario.log(msg);
                    log.warn("[ExperimentFeatureLogger] Soft failure — {}", msg);
                }
            }
        }

        log.info("[ExperimentFeatureLogger] Summary logged — user={} experiments={} assertions={}",
                user.getUserId(),
                artifacts != null ? artifacts.size() : 0,
                assertionResults != null ? assertionResults.size() : 0);
    }
}
