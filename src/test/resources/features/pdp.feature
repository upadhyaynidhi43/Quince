@pdp @regression
Feature: Add product to cart on the Product Detail Page
  As a shopper
  I want to select a colour, verify delivery availability, and add the product to my bag
  So that I can complete a purchase of the Leather Jewelry Travel Case

  @smoke @blocker
  Scenario: Add Black colour to cart after confirming delivery to New York
    Given I navigate to the Leather Jewelry Travel Case PDP
    When I select the "Black" colour
    And I open the zip code delivery check
    And I enter zip code "10001"
    And I submit the zip code
    Then the estimated delivery message should be visible
    And I click "ADD TO BAG"
    Then the cart icon should be visible in the header

