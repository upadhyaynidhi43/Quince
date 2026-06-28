package com.ecommerce.experiments;

import com.launchdarkly.sdk.LDContext;
import lombok.Builder;
import lombok.Getter;

/**
 * Represents a test user for LaunchDarkly context evaluation.
 * Build one per test, pass to LaunchDarklyClient.
 *
 * LD Context requires at minimum a unique key (userId/email).
 * Additional attributes (email, country, plan) are used by LD targeting rules.
 */
@Getter
@Builder
public class ExperimentUser {

    private final String userId;
    private final String email;
    private final String country;
    private final String plan;       // e.g. "free", "premium"

    /**
     * Converts this user into an LDContext that LaunchDarkly SDK understands.
     * "user" is the standard LD context kind for end-users.
     */
    public LDContext toLDContext() {
        var builder = LDContext.builder(userId).kind("user");

        if (email   != null) builder.set("email",   email);
        if (country != null) builder.set("country", country);
        if (plan    != null) builder.set("plan",     plan);

        return builder.build();
    }
}
