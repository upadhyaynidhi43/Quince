package com.ecommerce.experiments;

import java.io.Closeable;
import java.util.List;
import java.util.Map;

/**
 * Contract shared by the real LaunchDarkly SDK client and the mock.
 * Hooks depends only on this interface — never on the concrete class.
 */
public interface ILaunchDarklyClient extends Closeable {

    /**
     * Resolves experiment details for a single flag + user combination.
     */
    ExperimentVariant getExperimentDetails(
            String flagKey,
            Map<String, String> variationDescriptions,
            ExperimentUser user);

    /**
     * Resolves experiment details for multiple flags at once.
     * Returns one ExperimentVariant per flag key, in the same order.
     */
    List<ExperimentVariant> getExperimentDetails(
            List<String> flagKeys,
            Map<String, Map<String, String>> variationDescriptionsByFlag,
            ExperimentUser user);
}
