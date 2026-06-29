# Quince Framework — Pseudocode

---

## Directory Layout

```
src/
  main/java/com/ecommerce/
    config/
      FrameworkConfig          load config.yaml + env overrides (singleton)
      BrowserFactory           create Playwright Browser and BrowserContext

    experiments/
      ILaunchDarklyClient      interface — real and mock share this contract
      LaunchDarklyClient       real SDK: evaluates flags against live LD service
      MockLaunchDarklyClient   offline fixture table: userId → flagKey → variation
      ExperimentUser           value object: userId, email, country, plan
      ExperimentVariant        value object: flagKey, isOn, variation, allVariations, reason
      ExperimentAssertionResult value object: flagKey, variation, step, passed/failed/skipped
      FeatureGenerationContext  value object: flowName, pageUrl, locatorsClass, baseFeatureFile
      GeneratedExperimentArtifact value object: featurePath, locators map, variant details
      AIFeatureGenerator       generates .feature + locators JSON via Claude (or template)
      AITestPlanner            enriches a base TestPlan with variant steps via Claude
      ExperimentLocatorDiscovery discovers variant CSS selectors from live DOM via Claude
      SelfHealingEngine        recovers broken locators via Claude + page HTML
      TestPlanCache            saves/loads AI-generated plans as JSON (build/experiment-cache/)
      TestPlanExecutor         executes a TestPlan step-by-step against Playwright
      model/
        TestPlan               plan: userId, experimentName, variation, steps[]
        TestStep               step: type, locator, value, description, variantStep flag
        StepType               enum: navigate | click | fill | press_key | assert_*


  test/java/com/ecommerce/
    cucumber/
      CucumberTestRunner       @CucumberOptions entry point, TestNG DataProvider
      Hooks                    @BeforeAll/@Before/@After/@AfterAll lifecycle
      PlaywrightContext         PicoContainer: holds Page for current scenario
      ExperimentContext         PicoContainer: holds resolved experiment state for current scenario
      ExperimentAssertionExecutor  soft-asserts variant locators against the live page
      ExperimentFeatureLogger  writes experiment metadata into the Allure report

    experiments/
      ExperimentResolver       resolves all flag keys for a user, drives AI generation

    steps/
      PDPSteps                 all Cucumber step definitions for the PDP flow

    pages/
      PDPLocators              CSS selector constants for the PDP
      HeaderLocators           CSS selector constants for the site header
      PDPPage                  browser interactions: navigate, click, fill (with self-healing)

    validators/
      PDPValidator             assertions on PDP elements (no browser interactions)
      HeaderValidator          assertions on header elements

    tests/product/
      PDPTestPlans             builds the base JSON TestPlan for real-mode LLM enrichment

    constants/
      PDPConstants             PDP URL, default colour, default zip

  test/resources/
    pdp.test                   Scenario Outline template — {{EXAMPLES}} placeholder for user IDs
    features/pdp.feature       GENERATED at build time — gitignored, do not edit
    testng.xml                 TestNG suite pointing at CucumberTestRunner
    config.yaml                all non-secret config (base URL, browser, timeouts, env)
    logback-test.xml           logging config
    allure.properties          Allure results directory

build/
  generated-features/pdp.feature   written by generateFeatureFiles Gradle task
  experiment-cache/                 AI-generated TestPlan JSON files (keyed by flag+user+variation)
  allure-results/                   raw Allure JSON written during test run
  allure-report/                    HTML report generated after run

temp/
  experiment-features/              AI-generated .feature + locator JSON pairs (mock mode)
```

---

## Entry Point

```
gradle cucumberTest -Pmock=true -PtestUserIds=user-mock-001,user-mock-002,user-001

  1. generateFeatureFiles task:
       read pdp.test
       replace {{EXAMPLES}} with one row per userId from -PtestUserIds or TEST_USER_IDS env var
       write build/generated-features/pdp.feature

  2. testClasses task:
       compile src/main and src/test

  3. cucumberTest task:
       spawn JVM running TestNG
       TestNG loads testng.xml → finds CucumberTestRunner
       Cucumber parses build/generated-features/pdp.feature
       produces 3 pickles (one per Examples row)
       CucumberTestRunner.scenarios() returns those 3 pickles to TestNG DataProvider
       TestNG runs them sequentially (parallel=false)
```

---

## Suite Lifecycle (Hooks)

```
@BeforeAll launchSuite():
    cfg = FrameworkConfig.load("config.yaml")
        for each field: use env var if set, else yaml value
        mock = System.getProperty("mock") == "true"

    playwright = Playwright.create()
    browser    = BrowserFactory.createBrowser(playwright)
        switch cfg.browser:
            "firefox" → playwright.firefox().launch(headless=cfg.headless)
            "webkit"  → playwright.webkit().launch(headless=cfg.headless)
            default   → playwright.chromium().launch(headless=cfg.headless)

    if cfg.mock:
        ldClient = MockLaunchDarklyClient()     // offline fixture, no SDK key needed
    else:
        ldClient = LaunchDarklyClient()         // real LD SDK, needs LD_SDK_KEY

    resolver = ExperimentResolver(cfg, ldClient, AIFeatureGenerator, AITestPlanner, TestPlanCache)
    ExperimentContext.init(resolver)            // store resolver as suite-scoped static

@Before openScenario():                        // runs before each of the 3 scenarios
    context = BrowserFactory.createContext(browser)
        newContext(baseURL=cfg.baseUrl, viewport=1440x900, ignoreHTTPSErrors=true)
    page = context.newPage()
    page.setDefaultTimeout(cfg.defaultTimeoutMs)
    pw.setPage(page)                           // PicoContainer injects pw into steps

@After closeScenario(scenario):               // runs after each scenario
    ExperimentFeatureLogger.logSoftFailuresToScenario(scenario, experimentCtx.assertionResults)
    if scenario.failed:
        shot = page.screenshot(fullPage=true)   // byte[] — no temp file written
        scenario.attach(shot, "image/png")
    context.close()

@AfterAll closeSuite():
    browser.close()
    playwright.close()
    ldClient.close()
```

---

## Scenario Execution (PDPSteps)

```
// Scenario: PDP add-to-bag flow for user-mock-001
// All 3 scenarios run this same flow — the experiment layer is invisible to the steps

Given "I am the test user 'user-mock-001'":
    experimentCtx.setPendingUserId("user-mock-001")
    // userId stored; experiment resolution deferred until after page loads

Given "I navigate to the Leather Jewelry Travel Case PDP":
    PDPPage.navigate()
        page.navigate(PDPConstants.PDP_URL)
        page.waitForLoadState(DOMCONTENTLOADED)
        waitVisible("[data-option-type='Color'] [data-value='Black'] label")

    experimentCtx.resolveForUser(page, pdpFeatureContext(), pdpBaseLocators())
        → ExperimentResolver.resolve("user-mock-001", page, ctx, baseLocators)
        → returns ResolvedExperiment(user, lastVariant, resolvedPlan, artifacts, cacheHit)
        → ExperimentFeatureLogger.annotateTestCase(user, artifacts)
           // writes userId + experiment:flagKey=variation as Allure parameters

When "I select the 'Black' colour":
    PDPPage.selectColour("Black")
        clickHealing("[data-option-type='Color'] [data-value='Black'] label", "Select Black colour")

And "I open the zip code delivery check":
    PDPPage.openZipModal()
        scrollIntoView(ZIP_CODE_BUTTON)
        clickHealing(ZIP_CODE_BUTTON, "Open zip code delivery modal")
        waitVisible(ZIP_INPUT)                 // wait for modal to be ready

And "I enter zip code '10001'":
    PDPPage.enterZipCode("10001")
        fillHealing(ZIP_INPUT, "10001", "Enter zip code")

And "I submit the zip code":
    PDPPage.submitZipCode()
        clickHealing(ZIP_UPDATE, "Submit zip code")
        waitVisible(EDD_MESSAGE)               // confirms zip lookup completed

Then "the estimated delivery message should be visible":
    PDPValidator.eddMessageIsVisible()
        waitForSelector(EDD_MESSAGE, VISIBLE)
        assert EDD_MESSAGE.isVisible()

And "I click 'ADD TO BAG'":
    if experimentCtx.anyExperimentOn AND experimentCtx.resolvedPlan != null:
        // real mode: execute LLM-generated or cached variant plan
        TestPlanExecutor.execute(page, resolvedPlan)
    else:
        // base flow or mock mode: standard click
        PDPPage.addToBag()
            countBefore = cartCountText()
            clickHealing(ADD_TO_BAG_BUTTON, "Add product to cart")
            waitForFunction: CART_COUNT.text != countBefore   // confirms item added

Then "the cart icon should be visible in the header":   // LAST step — Allure lifecycle still active
    HeaderValidator.cartIconIsVisible()
        waitForSelector(CART_ICON, VISIBLE)
        assert CART_ICON.isVisible()

    if experimentCtx.generatedArtifacts not empty:
        results = ExperimentAssertionExecutor.run(artifacts, page)
            for each artifact:
                for each locator entry starting with "variant":
                    if locator is null → record SKIPPED
                    else: waitForSelector(locator, VISIBLE, timeout=3s)
                         if visible → record PASSED
                         else       → record FAILED (soft — does not throw)
        results.forEach(experimentCtx::recordExperimentAssertion)

    status = ExperimentFeatureLogger.injectExperimentDetailsStep(user, variant, artifacts, assertionResults)
        compute: failCount / skippedCount / passedCount from assertionResults
        parentStatus = FAILED if failCount>0 | BROKEN if skippedCount>0 | PASSED
        build StepResult tree:
            "Experiment Details [flagKey=variation] assertions: N✓ N✗ N⊘"
                [EXP] flagKey → variation         PASSED + variant detail attachment
                [ASSERT ✓/✗/⊘] step description  PASSED / FAILED / SKIPPED
        Allure.updateTestCase → inject StepResult into live test result
        return parentStatus

    if status == FAILED → throw AssertionError    // colours Allure row red
    if status == BROKEN → throw RuntimeException  // colours Allure row yellow
    // PASSED → no throw, green row
```

---

## Experiment Resolution (ExperimentResolver)

```
resolve(userId, page, ctx, baseLocators):
    user = ExperimentUser(userId, email=userId+"@example-shop.com", country, plan)

    for each (flagKey, variationDescriptions) in ALL_FLAGS:
        // ALL_FLAGS defined in ExperimentResolver — add new experiments here
        // e.g. "pdp-location-details" → { "show-location": "...", "hide-location": "..." }

        variant = ldClient.getExperimentDetails(flagKey, variationDescriptions, user)

        if variant.experimentOff:
            skip this flag

        if cfg.mock:
            // ── Mock path ────────────────────────────────────────────────────
            locators = mergeLocators(baseLocators, variant, page)
                discovered = ExperimentLocatorDiscovery.discover(page, variant)
                    if no valid API key → return { variantContainer:null, variantCta:null, variantBadge:null }
                    domSnapshot = page.evaluate(JS that extracts interactive elements)
                    response = Claude(system="identify CSS selectors for variant UI",
                                      user=experimentDetails + domSnapshot)
                    return parsed { variantContainer, variantCta, variantBadge }
                merged = baseLocators + discovered (base wins on conflict, nulls filled by discovered)

            artifact = AIFeatureGenerator.generate(variant, user, locators, ctx)
                key = "{experimentName}__{userId}__{variation}"
                if temp/experiment-features/{key}.feature EXISTS:
                    return loadFromDisk()           // cache hit — skip Claude call

                if cfg.anthropicApiKey valid AND NOT mock:
                    featureText = Claude(claude-opus, systemPrompt, userPrompt)
                    featureText = stripMarkdownFences(featureText)
                else:
                    featureText = buildTemplateFeature(variant, user, ctx)
                    // template built from base scenario steps + variant description

                write featureText → temp/experiment-features/{key}.feature
                write locators   → temp/experiment-features/{key}-locators.json
                return GeneratedExperimentArtifact(featurePath, featureText, locators, variant)

            artifacts.add(artifact)

        else:
            // ── Real path ────────────────────────────────────────────────────
            variation = variant.assignedVariation
            if TestPlanCache.exists(flagKey, userId, variation):
                resolvedPlan = TestPlanCache.load(flagKey, userId, variation)  // cache HIT
            else:
                basePlan     = PDPTestPlans.buildBasePlan(userId)
                    // hardcoded base steps: navigate, selectColour, openZipModal, etc.
                resolvedPlan = AITestPlanner.generateVariantPlan(basePlan, variant)
                    // sends basePlan JSON + variant details to Claude
                    // Claude returns enriched plan with additional variant-specific steps
                    // identity fields (userId, experimentName, variation) overwritten from variant
                TestPlanCache.save(resolvedPlan)
                    // written to build/experiment-cache/{flagKey}__{userId}__{variation}.json

    return ResolvedExperiment(user, lastVariant, resolvedPlan, artifacts, cacheHit)
```

---

## LaunchDarkly Clients

```
// Real
LaunchDarklyClient.getExperimentDetails(flagKey, variationDescriptions, user):
    ctx     = user.toLDContext()           // LD context with userId, country, plan attributes
    isOn    = ldClient.boolVariation(flagKey + "-enabled", ctx, default=false)
    detail  = ldClient.stringVariationDetail(flagKey, ctx, default="control")
    reason  = detail.reason.kind           // RULE_MATCH | FALLTHROUGH | OFF | etc.
    return ExperimentVariant(isOn, flagKey, variationDescriptions, detail.value, reason)

// Mock — no network, no SDK key
MockLaunchDarklyClient.getExperimentDetails(flagKey, variationDescriptions, user):
    FIXTURE = {
        "user-mock-001" → { "pdp-location-details": "show-location",
                            "pdp-add-to-bag-cta":   "urgency-cta" },
        "user-mock-002" → { "pdp-add-to-bag-cta":   "urgency-cta" }
        // any other userId → all experiments OFF
    }
    userFixture      = FIXTURE[userId] or {}
    experimentOn     = userFixture.contains(flagKey)
    assignedVariation = userFixture[flagKey] or "control"
    return ExperimentVariant(experimentOn, flagKey, variationDescriptions, assignedVariation, reason)
```

---

## Self-Healing Engine

```
SelfHealingEngine.heal(page, failedStep, plan):
    if no valid API key → return false

    html     = page.content()             // full live DOM HTML
    html     = truncate to 80,000 chars   // stay within Claude context window

    response = Claude(claude-opus,
                 system = "find correct CSS selector for failed locator",
                 user   = "failed locator: {step.locator}
                           step description: {step.description}
                           page HTML: {html}")

    parsed   = JSON parse response → { suggestion, confidence, reasoning }

    if confidence == "low":
        log.warn("Healer not confident — keeping original locator")
        return false

    step.locator = parsed.suggestion       // mutate step in place
    TestPlanCache.save(plan)               // persist healed locator for future runs
    log.info("Healed: {} → {}", original, parsed.suggestion)
    return true

// Caller retries after heal() returns true:
//   clickHealing:      waitVisible(step.locator) → page.click(step.locator)
//   fillHealing:       waitVisible(step.locator) → page.fill(step.locator, value)
//   waitVisibleHealing: waitVisible(step.locator)
```

---

## Allure Reporting (ExperimentFeatureLogger)

```
Phase 1 — annotateTestCase(user, artifacts):
// Called from ExperimentContext.resolveForUser() — test case is live
    Allure.updateTestCase(result → {
        params = mutableCopy(result.getParameters())
        params.removeIf(userId / experiments / experiment:*)  // idempotent
        params.add("userId" = user.userId)
        if artifacts empty:
            params.add("experiments" = "none active")
        else:
            for each artifact:
                params.add("experiment:{flagKey}" = "{variation}  [ON]")
        result.setParameters(params)
    })

Phase 2 — injectExperimentDetailsStep(user, variant, artifacts, assertionResults):
// Called from assertCartVisible() — last step, test case still live
    failCount    = assertionResults where !passed && !skipped
    skippedCount = assertionResults where skipped
    passedCount  = assertionResults where passed

    parentStatus = FAILED if failCount>0
                 | BROKEN if skippedCount>0
                 | PASSED

    children = []
    for each artifact:
        attach "Variant Details: {flagKey}" text file with full variant info + locator map
        children.add(StepResult "[EXP] {flagKey} → {variation}" PASSED + attachment)

    for each assertionResult:
        children.add(StepResult "[ASSERT ✓/✗/⊘] [{flagKey}] {stepDescription}" PASSED/FAILED/SKIPPED)

    attach "Experiment Summary — {userId}" text file
    parentStep = StepResult("Experiment Details [{activeExps}] assertions: N✓ N✗ N⊘",
                             parentStatus, children, summaryAttachment)

    Allure.updateTestCase(result → result.steps.add(parentStep))
    return parentStatus
    // caller throws AssertionError (FAILED) or RuntimeException (BROKEN) to colour the row

Phase 3 — logSoftFailuresToScenario(scenario, assertionResults):
// Called from @After hook
    for each result where !passed && !skipped:
        scenario.log("[EXPERIMENT SOFT FAIL] flag={} variation={} step={} reason={}")
        // appears in Allure Log tab under the teardown section
```

---

## Config Loading (FrameworkConfig)

```
load() — called once at class-loading time, result cached as singleton:
    data = YAML.parse("config.yaml")
    for each field:
        value = System.getenv(ENV_KEY) if set and non-blank
                else data.yamlField

    mock = System.getProperty("mock")   // set by -Pmock=true in Gradle cucumberTest task
         ?: System.getenv("MOCK")
         default false

Secrets — never stored in config.yaml:
    LD_SDK_KEY        → forwarded by Gradle cucumberTest → LaunchDarklyClient
    ANTHROPIC_API_KEY → forwarded by Gradle cucumberTest → AIFeatureGenerator,
                                                           AITestPlanner,
                                                           SelfHealingEngine,
                                                           ExperimentLocatorDiscovery
```

---

## Feature File Generation (Gradle)

```
generateFeatureFiles task:
    templateFile = src/test/resources/features/pdp.test
    outputFile   = build/generated-features/pdp.feature

    ids = project.findProperty("testUserIds")
        ?: System.getenv("TEST_USER_IDS")
        ?: "user-mock-001,user-mock-002,user-001"

    rows = ids.split(",").map { "      | {it.trim()} |" }.join("\n")
    outputFile.text = templateFile.text.replace("{{EXAMPLES}}", rows)

// pdp.test (committed):
//   Scenario Outline: PDP add-to-bag flow for user <userId>
//     ...steps...
//     Examples:
//       | userId |
//       {{EXAMPLES}}          ← replaced at build time

// build/generated-features/pdp.feature (gitignored, generated):
//   Scenario Outline: PDP add-to-bag flow for user <userId>
//     ...steps...
//     Examples:
//       | userId        |
//       | user-mock-001 |
//       | user-mock-002 |
//       | user-001      |
```
