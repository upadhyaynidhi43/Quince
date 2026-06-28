# ─────────────────────────────────────────────────────────────────────
# EXPERIMENT FILE — DO NOT RUN IN PRODUCTION SUITE UNTIL PROMOTED
# Flow       : PDP
# Experiment : pdp-add-to-bag-cta
# Variation  : urgency-cta
# Status     : PENDING VALIDATION
# Promote    : Once the experiment is validated:
#              1. Copy the scenarios below into src/test/resources/features/pdp.feature
#                 and remove this header comment.
#              2. Replace EXPERIMENT_VARIANT_* stubs in PDPLocators
#                 with real CSS selectors and rename to permanent constants.
#              3. Delete both files from temp/experiment-features/.
# NOTE     : Base regression is covered by the existing production suite.
# ─────────────────────────────────────────────────────────────────────

@experiment @pdp-add-to-bag-cta @urgency-cta
Feature: [pdp-add-to-bag-cta] Experiment variant "urgency-cta" — Urgency CTA — 'Add to Bag · Only 3 left!'

  # Core flow: runs to completion regardless of experiment state.
  # Experiment assertions below are soft-asserted (failures logged to Allure only).
  @happy-path
  Scenario: [urgency-cta] Urgency CTA — 'Add to Bag · Only 3 left!' — happy path
    Given I am the test user "user-mock-002"
    And I navigate to the Leather Jewelry Travel Case PDP
    When I select the "Black" colour
    And I open the zip code delivery check
    And I enter zip code "10001"
    And I submit the zip code
    Then the estimated delivery message should be visible
    And I click "ADD TO BAG"
    Then the cart icon should be visible in the header
    # ── Experiment assertions (soft-asserted — failure logged, not scenario-blocking) ──
    Then the element with locator "EXPERIMENT_VARIANT_CONTAINER" should be visible

