@pdp @regression
Feature: Add product to cart on the Product Detail Page — multi-user experiment coverage
  As a shopper (or test user under experiment)
  I want to select a colour, verify delivery availability, and add the product to my bag
  So that the correct experience is served based on my experiment assignment

  # Runs once per user in the Examples table.
  # user-mock-001 → enrolled in both experiments  → Claude generates feature + locators
  # user-mock-002 → enrolled in experiment 2 only → Claude generates feature + locators
  # user-001      → not enrolled in any experiment → base flow runs as-is
  @smoke @multiuser
  Scenario Outline: PDP add-to-bag flow for user <userId>
    Given I am the test user "<userId>"
    And I navigate to the Leather Jewelry Travel Case PDP
    When I select the "Black" colour
    And I open the zip code delivery check
    And I enter zip code "10001"
    And I submit the zip code
    Then the estimated delivery message should be visible
    And I click "ADD TO BAG"
    Then the cart icon should be visible in the header

    Examples:
      | userId         |
      | user-mock-001  |
      | user-mock-002  |
      | user-001       |
