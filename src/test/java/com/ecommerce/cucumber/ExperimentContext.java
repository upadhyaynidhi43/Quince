package com.ecommerce.cucumber;

import com.ecommerce.experiments.ExperimentUser;
import com.ecommerce.experiments.ExperimentVariant;
import com.ecommerce.experiments.model.TestPlan;
import lombok.Getter;
import lombok.Setter;

/**
 * Shared state for a single Cucumber experiment scenario.
 * Passed between Hooks and PDPSteps via PicoContainer DI.
 */
@Getter
@Setter
public class ExperimentContext {

    private ExperimentUser    user;
    private ExperimentVariant variant;
    private TestPlan          resolvedPlan;

    /** True when the plan was loaded from local cache; false when LLM was called. */
    private boolean cacheHit;
}
