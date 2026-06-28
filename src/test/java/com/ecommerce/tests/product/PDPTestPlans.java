package com.ecommerce.tests.product;

import com.ecommerce.constants.PDPConstants;
import com.ecommerce.experiments.model.TestPlan;
import com.ecommerce.experiments.model.TestStep;
import com.ecommerce.pages.HeaderLocators;
import com.ecommerce.pages.PDPLocators;

import java.time.Instant;
import java.util.List;

public final class PDPTestPlans {

    private PDPTestPlans() {}

    public static TestPlan buildBasePlan(String userId) {
        TestStep.resetCounter();
        return TestPlan.builder()
                .userId(userId)
                .experimentName("base")
                .assignedVariation(null)
                .experimentOn(false)
                .generatedAt(Instant.now().toString())
                .steps(List.of(
                        TestStep.navigate(PDPConstants.PDP_URL),
                        TestStep.click(PDPLocators.colourLabel(PDPConstants.DEFAULT_COLOUR), "Select Nude colour"),
                        TestStep.click(PDPLocators.ZIP_CODE_BUTTON,                          "Open zip code delivery modal"),
                        TestStep.fill(PDPLocators.ZIP_INPUT, PDPConstants.DEFAULT_ZIP,       "Enter zip code"),
                        TestStep.click(PDPLocators.ZIP_UPDATE,                               "Submit zip code"),
                        TestStep.assertVisible(PDPLocators.EDD_MESSAGE,                      "EDD delivery message visible"),
                        TestStep.pressKey("Escape"),
                        TestStep.click(PDPLocators.ADD_TO_BAG_BUTTON,                        "Add product to cart"),
                        TestStep.assertVisible(HeaderLocators.CART_ICON,                     "Cart icon visible after add to bag")
                ))
                .build();
    }
}
