package com.ecommerce.experiments;

import com.ecommerce.experiments.model.TestPlan;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * Persists and retrieves AI-generated test plans as JSON files.
 *
 * Cache directory: build/experiment-cache/
 * Filename format: {experimentName}__{userId}__{assignedVariation}.json
 *
 * Plans for users with experiment OFF are never written (no variation key).
 */
@Slf4j
public class TestPlanCache {

    private static final String CACHE_DIR = "build/experiment-cache";
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final Path cacheDir;

    public TestPlanCache() {
        this.cacheDir = Paths.get(CACHE_DIR);
    }

    public TestPlanCache(String cacheDirectory) {
        this.cacheDir = Paths.get(cacheDirectory);
    }

    /** Writes a plan to disk. Creates the cache directory if it doesn't exist. */
    public void save(TestPlan plan) {
        try {
            Files.createDirectories(cacheDir);
            Path file = cacheDir.resolve(fileName(plan.getExperimentName(), plan.getUserId(), plan.getAssignedVariation()));
            MAPPER.writeValue(file.toFile(), plan);
            log.info("TestPlan cached → {}", file.toAbsolutePath());
        } catch (IOException e) {
            throw new RuntimeException("Failed to save test plan to cache", e);
        }
    }

    /** Returns the cached plan if it exists, empty otherwise. */
    public Optional<TestPlan> load(String experimentName, String userId, String variation) {
        Path file = cacheDir.resolve(fileName(experimentName, userId, variation));
        if (!Files.exists(file)) {
            log.debug("No cached plan for [{} / {} / {}]", experimentName, userId, variation);
            return Optional.empty();
        }
        try {
            TestPlan plan = MAPPER.readValue(file.toFile(), TestPlan.class);
            log.info("Loaded cached plan ← {}", file.toAbsolutePath());
            return Optional.of(plan);
        } catch (IOException e) {
            log.warn("Corrupt cache file at {} — will regenerate", file, e);
            return Optional.empty();
        }
    }

    public boolean exists(String experimentName, String userId, String variation) {
        return Files.exists(cacheDir.resolve(fileName(experimentName, userId, variation)));
    }

    /** Updates an existing cached plan (used by self-healing to persist locator fixes). */
    public void update(TestPlan plan) {
        save(plan);
        log.info("Cache updated with healed locators for [{} / {}]", plan.getUserId(), plan.getAssignedVariation());
    }

    public Path cacheFilePath(String experimentName, String userId, String variation) {
        return cacheDir.resolve(fileName(experimentName, userId, variation));
    }

    private static String fileName(String experimentName, String userId, String variation) {
        // Sanitise to filesystem-safe characters
        String safe = (experimentName + "__" + userId + "__" + variation)
                .replaceAll("[^a-zA-Z0-9_\\-]", "-");
        return safe + ".json";
    }
}
