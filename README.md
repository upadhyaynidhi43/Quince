# Quince E-Commerce Test Automation Framework

A BDD test automation framework for [quince.com](https://www.quince.com) built on **Java 17 · Playwright · Cucumber · TestNG**. It runs core user flows against multiple test users and automatically handles A/B experiment variants — generating feature files, discovering locators via Claude AI, and surfacing experiment details in Allure without ever failing the core flow.

---

## Table of Contents

1. [Tech Stack](#tech-stack)
2. [Project Structure](#project-structure)
3. [Framework Architecture](#framework-architecture)
4. [Experiment Testing Architecture](#experiment-testing-architecture)
5. [Allure Reporting](#allure-reporting)
6. [Configuration](#configuration)
7. [Running Tests](#running-tests)
8. [Adding a New Experiment](#adding-a-new-experiment)
9. [Adding a New Flow](#adding-a-new-flow)

---

## Tech Stack

| Layer | Technology |
|---|---|
| Browser automation | Playwright 1.44 |
| BDD | Cucumber 7.18 (TestNG runner) |
| Test runner | TestNG 7.10 |
| Reporting | Allure 2.27 + allure-cucumber7-jvm |
| Feature flags | LaunchDarkly Java SDK 7.4 |
| AI (locators + test plans) | Anthropic Claude (anthropic-java 2.34) |
| DI | PicoContainer |
| Config | Jackson YAML |
| Build | Gradle 9 |

---

## Project Structure

```
Quince/
├── src/
│   ├── main/java/com/ecommerce/
│   │   ├── config/
│   │   │   ├── FrameworkConfig.java        # YAML config loader (singleton)
│   │   │   └── BrowserFactory.java         # Playwright browser/context factory
│   │   ├── experiments/
│   │   │   ├── ILaunchDarklyClient.java     # LD client interface
│   │   │   ├── LaunchDarklyClient.java      # Real LD SDK implementation
│   │   │   ├── MockLaunchDarklyClient.java  # Offline mock (no SDK key needed)
│   │   │   ├── ExperimentVariant.java       # Resolved variant: flag, variation, description
│   │   │   ├── ExperimentUser.java          # User attributes passed to LD
│   │   │   ├── ExperimentAssertionResult.java # Soft-assert outcome (passed/failed/skipped)
│   │   │   ├── ExperimentLocatorDiscovery.java # DOM snapshot → Claude → CSS selectors
│   │   │   ├── AIFeatureGenerator.java      # Generates .feature + locators JSON per variant
│   │   │   ├── AITestPlanner.java           # Generates TestPlan via Claude (real mode)
│   │   │   ├── GeneratedExperimentArtifact.java # Holds generated .feature + locator map
│   │   │   ├── FeatureGenerationContext.java # Flow metadata for generator (flow-agnostic)
│   │   │   ├── SelfHealingEngine.java       # Heals broken selectors via Claude
│   │   │   ├── TestPlanCache.java           # Disk cache for AI-generated test plans
│   │   │   ├── TestPlanExecutor.java        # Executes a TestPlan against a Playwright page
│   │   │   └── model/
│   │   │       ├── TestPlan.java
│   │   │       ├── TestStep.java
│   │   │       └── StepType.java
│   │   └── utils/
│   │       └── ScreenshotUtils.java
│   └── test/java/com/ecommerce/
│       ├── cucumber/
│       │   ├── CucumberTestRunner.java        # @CucumberOptions + TestNG entry point
│       │   ├── Hooks.java                     # @Before/@After: browser open/close
│       │   ├── PlaywrightContext.java          # Scenario-scoped Playwright page holder
│       │   ├── ExperimentContext.java          # Scenario-scoped experiment state (DI)
│       │   ├── ExperimentAssertionExecutor.java # Runs variant locator checks against live page
│       │   └── ExperimentFeatureLogger.java    # Writes experiment data to Allure
│       ├── experiments/
│       │   └── ExperimentResolver.java         # Resolves all flags for a user, builds artifacts
│       ├── pages/
│       │   ├── PDPPage.java                   # Page actions (navigate, select colour, etc.)
│       │   ├── PDPLocators.java               # CSS selector constants for PDP
│       │   └── HeaderLocators.java            # CSS selector constants for site header
│       ├── steps/
│       │   └── PDPSteps.java                  # Cucumber step definitions
│       ├── validators/
│       │   ├── PDPValidator.java              # Assertions for PDP elements
│       │   └── HeaderValidator.java           # Assertions for header elements
│       ├── tests/product/
│       │   └── PDPTestPlans.java              # Pre-built TestPlan for PDP (real mode)
│       ├── constants/
│       │   └── PDPConstants.java              # PDP URL and other constants
│       └── resources/
│           ├── features/pdp.feature           # Base BDD scenarios
│           ├── config.yaml                    # Framework configuration
│           ├── allure.properties              # Allure results directory
│           └── testng.xml                     # TestNG suite definition
└── temp/
    └── experiment-features/                   # Generated .feature + locators JSON (per variant)
```

---

## Framework Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                        Gradle cucumberTest                           │
│  1. cleanAllureResults   2. testClasses   3. JavaExec(TestNG)        │
│  4. generateAllureReport (always, pass or fail)                      │
└──────────────────────────────┬──────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│                     CucumberTestRunner (TestNG)                      │
│  @CucumberOptions: features=src/test/resources/features              │
│                    glue=com.ecommerce.cucumber, com.ecommerce.steps  │
│                    plugin=AllureCucumber7Jvm                         │
└──────────────────────────────┬──────────────────────────────────────┘
                               │  runs each Scenario row
                               ▼
┌──────────────────────────────────────────────────────────────────┐
│                        Hooks (PicoContainer DI)                   │
│                                                                   │
│  @BeforeAll  launchSuite()                                        │
│    • Playwright.create() + BrowserFactory.createBrowser()        │
│    • ILaunchDarklyClient (real or mock based on -Pmock=true)     │
│    • ExperimentResolver.init(resolver)                            │
│                                                                   │
│  @Before     openScenario()                                       │
│    • BrowserContext + Page → PlaywrightContext                    │
│                                                                   │
│  @After      closeScenario()                                      │
│    • ExperimentFeatureLogger.logSoftFailuresToScenario()         │
│    • Screenshot on failure                                        │
│    • context.close()                                              │
│                                                                   │
│  @AfterAll   closeSuite()                                         │
│    • browser.close() + playwright.close() + ldClient.close()     │
└──────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│                    PDPSteps (step definitions)                    │
│                                                                   │
│  Given "I am the test user"   → setPendingUserId()               │
│  And   "I navigate to PDP"    → PDPPage.navigate()               │
│                                 ExperimentContext.resolveForUser()│
│  When  "I select colour"      → PDPPage.selectColour()           │
│  And   "I open zip modal"     → PDPPage.openZipModal()           │
│  And   "I enter zip code"     → PDPPage.enterZipCode()           │
│  And   "I submit zip"         → PDPPage.submitZipCode()          │
│  Then  "delivery msg visible" → PDPValidator.eddMessageIsVisible()│
│  And   "I click ADD TO BAG"   → experiment ON? variant plan       │
│                                 experiment OFF? PDPPage.addToBag()│
│  Then  "cart icon visible"    → HeaderValidator.cartIconIsVisible()│
│                                 ExperimentAssertionExecutor.run() │
│                                 ExperimentFeatureLogger.inject()  │
│                                 throw if BROKEN/FAILED            │
└──────────────────────────────────────────────────────────────────┘
```

---

## Experiment Testing Architecture

### Core Principle

> The **core user flow always passes** regardless of experiment state. Experiment checks are **soft-asserted** — failures are logged to Allure and colour the test row but never block the scenario's core steps.

### Flow Diagram

```
After PDP navigation:
                                                     
  resolveForUser(userId, page, ctx, baseLocators)    
         │                                           
         ▼                                           
  ExperimentResolver.resolve()                       
         │                                           
         │  for each flag in ALL_FLAGS               
         │                                           
         ├──► LaunchDarkly.getExperimentDetails()    
         │           │                               
         │      experimentOn?                        
         │      ┌────┴────┐                          
         │     NO         YES                        
         │      │          │                         
         │    skip         ▼                         
         │          ExperimentLocatorDiscovery       
         │            • page.evaluate(DOM_SNAPSHOT)  
         │            • Claude API → CSS selectors   
         │            • fallback to null stubs        
         │                  │                        
         │                  ▼                        
         │          mergeLocators(base + variant)    
         │                  │                        
         │                  ▼                        
         │          AIFeatureGenerator.generate()    
         │            • cache hit? load from disk    
         │            • cache miss? Claude or template│
         │            • writes temp/experiment-features/
         │              {flag}__{user}__{variation}.feature
         │              {flag}__{user}__{variation}-locators.json
         │                  │                        
         │                  ▼                        
         │          GeneratedExperimentArtifact      
         │          (featurePath, locators, variant) 
         │                                           
         └──► ExperimentContext populated            
              annotateTestCase() → Allure parameters 
                                                     
                                                     
At "cart icon visible" step (last step, test case live in Allure):
                                                     
  ExperimentAssertionExecutor.run(artifacts, page)   
         │                                           
         │  for each artifact's variant* locators    
         │                                           
         ├── locator is null?                        
         │       └── SKIPPED result                  
         │           "Locator not yet configured"    
         │                                           
         └── locator is set?                         
                 ├── waitForSelector (3000ms)        
                 ├── isVisible()                     
                 ├── pass → PASSED result            
                 └── fail → FAILED result (soft)     
                                                     
  ExperimentFeatureLogger.injectExperimentDetailsStep()
         │                                           
         │  Build "Experiment Details" StepResult    
         │    ├── [EXP] flag → variation  (child)    
         │    │     └── Variant Details attachment   
         │    └── [ASSERT ✓/✗/⊘] per assertion      
         │                                           
         │  Compute parentStatus:                    
         │    failCount  > 0 → FAILED  (red)         
         │    skippedCount > 0 → BROKEN (yellow)     
         │    else           → PASSED  (green)       
         │                                           
         │  updateTestCase() → inject step           
         │                                           
         └── return parentStatus                     
                  │                                  
                  ├── FAILED  → throw AssertionError 
                  │             → Allure row = RED   
                  ├── BROKEN  → throw RuntimeException
                  │             → Allure row = YELLOW
                  └── PASSED  → no throw → GREEN     
```

### Key Classes

| Class | Responsibility |
|---|---|
| `ExperimentResolver` | Orchestrates flag resolution for all experiments in a suite run. Calls LD, merges locators, triggers feature generation. Flow-agnostic. |
| `ILaunchDarklyClient` | Interface with real (`LaunchDarklyClient`) and mock (`MockLaunchDarklyClient`) implementations. Swap via `-Pmock=true`. |
| `ExperimentLocatorDiscovery` | Injects a JavaScript DOM snapshot script via `page.evaluate()`, sends it to Claude with the variant description, receives back CSS selectors. Falls back to null stubs when no API key. |
| `AIFeatureGenerator` | Generates a `.feature` file per variant. Real mode: Claude writes the Gherkin. Mock/no-key mode: template built from base feature steps. Caches to `temp/experiment-features/`. |
| `GeneratedExperimentArtifact` | Holds the generated feature path, locator map, and full `ExperimentVariant` for Allure attribution. |
| `ExperimentContext` | PicoContainer-injected scenario state: user, variant, generated artifacts, soft-assert results. |
| `ExperimentAssertionExecutor` | Iterates all `variant*` locators in each artifact. Null locator → SKIPPED. Non-null → Playwright visibility check. Never throws. |
| `ExperimentFeatureLogger` | Writes Allure parameters (userId, experiment flags) and injects the `Experiment Details` step tree into the live test case. Returns `Status` so the caller can throw to colour the row. |

### Soft Assertion Contract

```
Experiment step fails
        │
        ▼
ExperimentAssertionResult.fail() recorded
        │
        ▼
Core flow continues — no exception propagated
        │
        ▼
After core flow completes (assertCartVisible):
  injectExperimentDetailsStep() → parentStatus = FAILED
  throw AssertionError("Experiment assertions FAILED — core flow passed")
        │
        ▼
AllureCucumber7Jvm maps AssertionError → test row RED
Core scenario steps remain GREEN in the step timeline
```

### Locator Promotion Workflow

When an experiment is validated and ready to ship:

```
temp/experiment-features/{flag}__{user}__{variation}.feature
        │
        ▼
1. Copy @happy-path scenario into src/test/resources/features/pdp.feature
   (remove the promotion header comment)
        │
        ▼
2. Fill in PDPLocators.EXPERIMENT_VARIANT_* with real CSS selectors
   discovered during validation, then rename to permanent constants
        │
        ▼
3. Delete both files from temp/experiment-features/
        │
        ▼
Experiment is now part of the permanent regression suite
```

---

## Allure Reporting

Every test case shows:

| Section | Content |
|---|---|
| **Parameters** | `userId`, `experiment:<flagKey> = variation [ON]` for each active flag |
| **Experiment Details step** | One `[EXP]` child per active experiment + one `[ASSERT ✓/✗/⊘]` per locator check |
| **Variant Details attachment** | Flag, status ON/OFF, assigned variation + description, all variations, evaluation reason, full locator map |
| **Experiment Summary attachment** | User profile + all active experiments + assertion pass/fail/skipped counts |

**Row colours:**

| State | Colour | Trigger |
|---|---|---|
| All experiment checks passed | Green | No throw |
| Variant locators not yet configured (null stubs) | Yellow | `throw RuntimeException` → BROKEN |
| One or more assertion checks failed | Red | `throw AssertionError` → FAILED |
| No experiments active for this user | Green | No throw |

**Report generation** is automatic after every run:
```
build/allure-results/   ← raw JSON (wiped before each run)
build/allure-report/    ← rendered HTML (overwritten each run)
```

---

## Configuration

`src/test/resources/config.yaml`:

```yaml
baseUrl: "https://www.quince.com/"
browser: "chromium"          # chromium | firefox | webkit
headless: false
defaultTimeoutMs: 30000
screenshotDir: "build/screenshots"
environment: "staging"

launchDarklySdkKey: "sdk-your-key-here"   # or env: LD_SDK_KEY
anthropicApiKey:    "sk-ant-your-key-here" # or env: ANTHROPIC_API_KEY

testUserIds:
  - "user-mock-001"   # enrolled in both experiments (mock)
  - "user-mock-002"   # enrolled in experiment 2 only (mock)
  - "user-001"        # no experiment → base flow
testUserCountry: "US"
testUserPlan:    "premium"
experimentFlagKey: "pdp-location-details"
```

All sensitive values can be overridden by environment variables — the YAML values are defaults only.

---

## Running Tests

```bash
# Standard run (real LaunchDarkly + Anthropic keys required)
./gradlew cucumberTest

# Mock mode — no LD or Anthropic key needed
./gradlew cucumberTest -Pmock=true

# Open report after run
allure open build/allure-report

# Run tests and open live report in one step
./gradlew allureServe
```

**What happens on each run:**
1. `cleanAllureResults` — wipes `build/allure-results/`
2. Tests execute; Allure listener writes JSON results
3. `generateAllureReport` — renders fresh HTML to `build/allure-report/`

Screenshots of failed scenarios are saved to `build/screenshots/`.

---

## Adding a New Experiment

1. **Register the flag** in `ExperimentResolver.ALL_FLAGS`:
   ```java
   put("pdp-new-feature", new LinkedHashMap<>() {{
       put("control",   "Original experience");
       put("variant-a", "New feature enabled");
   }});
   ```

2. **Add mock fixture** in `MockLaunchDarklyClient` if needed for offline testing.

3. **Add variant locator stubs** to the relevant `*Locators` class:
   ```java
   public static final String EXPERIMENT_VARIANT_NEW = null; // TODO: fill in
   ```

4. **Run tests** — `AIFeatureGenerator` generates the `.feature` file automatically. Allure will show the step as BROKEN (yellow) until locators are filled in.

5. **Fill in locators** once the variant UI is live, run again — assertions turn green.

6. **Promote** once validated (see [Locator Promotion Workflow](#locator-promotion-workflow)).

---

## Adding a New Flow

The experiment layer is flow-agnostic. To add a new page flow (e.g. Checkout):

1. Create `CheckoutPage.java`, `CheckoutLocators.java`, `CheckoutValidator.java` in the relevant packages.

2. Create `checkout.feature` with a `Scenario Outline` over `testUserIds`.

3. Create `CheckoutSteps.java` with a `resolveForUser()` call after navigation, passing a `FeatureGenerationContext`:
   ```java
   FeatureGenerationContext.builder()
       .flowName("Checkout")
       .pageUrl(CheckoutConstants.URL)
       .locatorsClassName("CheckoutLocators")
       .baseFeatureFile("src/test/resources/features/checkout.feature")
       .build();
   ```

4. Pass `checkoutBaseLocators()` — a map of semantic name → CSS selector for the page's existing elements. Include `variantContainer/variantCta/variantBadge` stubs.

5. Call `ExperimentFeatureLogger.injectExperimentDetailsStep()` from the last step and throw on BROKEN/FAILED — same pattern as `PDPSteps.assertCartVisible()`.

No changes needed to `ExperimentResolver`, `AIFeatureGenerator`, `ExperimentAssertionExecutor`, or `ExperimentFeatureLogger` — they are generic.
