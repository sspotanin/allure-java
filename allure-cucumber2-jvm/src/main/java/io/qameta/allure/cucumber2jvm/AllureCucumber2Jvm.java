package io.qameta.allure.cucumber2jvm;

import cucumber.api.HookType;
import cucumber.api.TestCase;
import cucumber.api.TestStep;
import cucumber.api.Result;
import cucumber.api.PendingException;
import cucumber.api.event.EventHandler;
import cucumber.api.event.EventPublisher;
import cucumber.api.event.TestSourceRead;
import cucumber.api.event.TestCaseStarted;
import cucumber.api.event.TestCaseFinished;
import cucumber.api.event.TestStepStarted;
import cucumber.api.event.TestStepFinished;
import cucumber.api.formatter.Formatter;

import cucumber.runner.UnskipableStep;
import gherkin.ast.Feature;
import gherkin.ast.ScenarioDefinition;
import gherkin.ast.ScenarioOutline;
import gherkin.ast.Examples;
import gherkin.ast.TableRow;
import gherkin.pickles.PickleCell;
import gherkin.pickles.PickleRow;
import gherkin.pickles.PickleTable;
import gherkin.pickles.PickleTag;
import io.qameta.allure.Allure;
import io.qameta.allure.Lifecycle;
import io.qameta.allure.model.Parameter;
import io.qameta.allure.model.Status;
import io.qameta.allure.model.TestResult;
import io.qameta.allure.model.StepResult;
import io.qameta.allure.util.ResultsUtils;

import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Allure plugin for Cucumber JVM 2.0.
 */
@SuppressWarnings({
        "PMD.ExcessiveImports",
        "ClassFanOutComplexity", "ClassDataAbstractionCoupling"
})
public class AllureCucumber2Jvm implements Formatter {

    private final Lifecycle lifecycle;

    private final Map<String, String> scenarioUuids = new HashMap<>();

    private final CucumberSourceUtils cucumberSourceUtils = new CucumberSourceUtils();
    private Feature currentFeature;
    private String currentFeatureFile;
    private TestCase currentTestCase;

    private final EventHandler<TestSourceRead> featureStartedHandler = this::handleFeatureStartedHandler;
    private final EventHandler<TestCaseStarted> caseStartedHandler = this::handleTestCaseStarted;
    private final EventHandler<TestCaseFinished> caseFinishedHandler = this::handleTestCaseFinished;
    private final EventHandler<TestStepStarted> stepStartedHandler = this::handleTestStepStarted;
    private final EventHandler<TestStepFinished> stepFinishedHandler = this::handleTestStepFinished;

    public AllureCucumber2Jvm() {
        this.lifecycle = Allure.getLifecycle();
    }

    @Override
    public void setEventPublisher(final EventPublisher publisher) {
        publisher.registerHandlerFor(TestSourceRead.class, featureStartedHandler);

        publisher.registerHandlerFor(TestCaseStarted.class, caseStartedHandler);
        publisher.registerHandlerFor(TestCaseFinished.class, caseFinishedHandler);

        publisher.registerHandlerFor(TestStepStarted.class, stepStartedHandler);
        publisher.registerHandlerFor(TestStepFinished.class, stepFinishedHandler);
    }

    /*
    Event Handlers
     */

    private void handleFeatureStartedHandler(final TestSourceRead event) {
        cucumberSourceUtils.addTestSourceReadEvent(event.uri, event);
    }

    private void handleTestCaseStarted(final TestCaseStarted event) {
        currentFeatureFile = event.testCase.getUri();
        currentFeature = cucumberSourceUtils.getFeature(currentFeatureFile);

        currentTestCase = event.testCase;

        final Deque<PickleTag> tags = new LinkedList<>();
        tags.addAll(event.testCase.getTags());

        final LabelBuilder labelBuilder = new LabelBuilder(currentFeature, event.testCase, tags);

        final TestResult result = new TestResult()
                .setUuid(getTestCaseUuid(event.testCase))
                .setHistoryId(getHistoryId(event.testCase))
                .setName(event.testCase.getName())
                .setLabels(labelBuilder.getScenarioLabels())
                .setLinks(labelBuilder.getScenarioLinks());

        final ScenarioDefinition scenarioDefinition =
                cucumberSourceUtils.getScenarioDefinition(currentFeatureFile, currentTestCase.getLine());
        if (scenarioDefinition instanceof ScenarioOutline) {
            result.setParameters(
                    getExamplesAsParameters((ScenarioOutline) scenarioDefinition)
            );
        }

        if (currentFeature.getDescription() != null && !currentFeature.getDescription().isEmpty()) {
            result.setDescription(currentFeature.getDescription());
        }

        lifecycle.scheduleTestCase(result);
        lifecycle.startTest(getTestCaseUuid(event.testCase));
    }

    private void handleTestCaseFinished(final TestCaseFinished event) {
        final StatusDetails statusDetails =
                ResultsUtils.getStatusDetails(event.result.getError()).orElse(new StatusDetails());

        if (statusDetails.getMessage() != null && statusDetails.getTrace() != null) {
            lifecycle.updateTestCase(getTestCaseUuid(event.testCase), scenarioResult ->
                    scenarioResult
                            .setStatus(translateTestCaseStatus(event.result))
                            .setStatusDetails(statusDetails));
        } else {
            lifecycle.updateTestCase(getTestCaseUuid(event.testCase), scenarioResult ->
                    scenarioResult
                            .setStatus(translateTestCaseStatus(event.result)));
        }

        lifecycle.stopTestCase(getTestCaseUuid(event.testCase));
        lifecycle.writeTestCase(getTestCaseUuid(event.testCase));
    }

    private void handleTestStepStarted(final TestStepStarted event) {
        if (!event.testStep.isHook()) {
            final String stepKeyword = Optional.ofNullable(
                    cucumberSourceUtils.getKeywordFromSource(currentFeatureFile, event.testStep.getStepLine())
            ).orElse("UNDEFINED");

            final StepResult stepResult = new StepResult()
                    .setName(String.format("%s %s", stepKeyword, event.testStep.getPickleStep().getText()))
                    .setStart(System.currentTimeMillis());

            lifecycle.startStep(getTestCaseUuid(currentTestCase), getStepUuid(event.testStep), stepResult);

            event.testStep.getStepArgument().stream()
                    .filter(argument -> argument instanceof PickleTable)
                    .findFirst()
                    .ifPresent(table -> createDataTableAttachment((PickleTable) table));
        } else if (event.testStep.isHook() && event.testStep instanceof UnskipableStep) {
            final StepResult stepResult = new StepResult()
                    .setName(event.testStep.getHookType().toString())
                    .setStart(System.currentTimeMillis());

            lifecycle.startStep(getTestCaseUuid(currentTestCase), getHookStepUuid(event.testStep), stepResult);
        }
    }

    private void handleTestStepFinished(final TestStepFinished event) {
        if (event.testStep.isHook() && event.testStep instanceof UnskipableStep) {
            handleHookStep(event);
        } else {
            handlePickleStep(event);
        }
    }

    /*
    Utility Methods
     */

    private String getTestCaseUuid(final TestCase testCase) {
        return scenarioUuids.computeIfAbsent(getHistoryId(testCase), it -> UUID.randomUUID().toString());
    }

    private String getStepUuid(final TestStep step) {
        return currentFeature.getName() + getTestCaseUuid(currentTestCase)
                + step.getPickleStep().getText() + step.getStepLine();
    }

    private String getHookStepUuid(final TestStep step) {
        return currentFeature.getName() + getTestCaseUuid(currentTestCase)
                + step.getHookType().toString() + step.getCodeLocation();
    }

    private String getHistoryId(final TestCase testCase) {
        final String testCaseLocation = testCase.getUri() + ":" + testCase.getLine();
        return Utils.md5(testCaseLocation);
    }

    private Status translateTestCaseStatus(final Result testCaseResult) {
        Status allureStatus;
        if (testCaseResult.getStatus() == Result.Type.UNDEFINED || testCaseResult.getStatus() == Result.Type.PENDING) {
            allureStatus = Status.SKIPPED;
        } else {
            try {
                allureStatus = Status.fromValue(testCaseResult.getStatus().lowerCaseName());
            } catch (IllegalArgumentException e) {
                allureStatus = Status.BROKEN;
            }
        }
        return allureStatus;
    }

    private Set<Parameter> getExamplesAsParameters(final ScenarioOutline scenarioOutline) {
        final Examples examples = scenarioOutline.getExamples().get(0);
        final TableRow row = examples.getTableBody().stream()
                .filter(example -> example.getLocation().getLine() == currentTestCase.getLine())
                .findFirst().get();
        return IntStream.range(0, examples.getTableHeader().getCells().size()).mapToObj(index -> {
            final String name = examples.getTableHeader().getCells().get(index).getValue();
            final String value = row.getCells().get(index).getValue();
            return new Parameter().setName(name).setValue(value);
        }).collect(Collectors.toSet());
    }

    private void createDataTableAttachment(final PickleTable pickleTable) {
        final List<PickleRow> rows = pickleTable.getRows();

        final StringBuilder dataTableCsv = new StringBuilder();
        if (!rows.isEmpty()) {
            rows.forEach(dataTableRow -> {
                dataTableCsv.append(
                        dataTableRow.getCells().stream()
                                .map(PickleCell::getValue)
                                .collect(Collectors.joining("\t"))
                );
                dataTableCsv.append('\n');
            });

            final String attachmentSource = lifecycle
                    .prepareAttachment("Data table", "text/tab-separated-values", "csv");
            lifecycle.writeAttachment(attachmentSource,
                    new ByteArrayInputStream(dataTableCsv.toString().getBytes(Charset.forName("UTF-8"))));
        }
    }

    private void handleHookStep(final TestStepFinished event) {
        final String uuid = getHookStepUuid(event.testStep);
        Consumer<StepResult> stepResult = result -> result.setStatus(translateTestCaseStatus(event.result));

        if (!Status.PASSED.equals(translateTestCaseStatus(event.result))) {
            final StatusDetails statusDetails = ResultsUtils.getStatusDetails(event.result.getError()).get();
            if (event.testStep.getHookType() == HookType.Before) {
                final TagParser tagParser = new TagParser(currentFeature, currentTestCase);
                statusDetails
                        .setMessage("Before is failed: " + event.result.getError().getLocalizedMessage())
                        .setFlaky(tagParser.isFlaky())
                        .setMuted(tagParser.isMuted())
                        .setKnown(tagParser.isKnown());
                lifecycle.updateTestCase(getTestCaseUuid(currentTestCase), scenarioResult ->
                        scenarioResult.setStatus(Status.SKIPPED)
                                .setStatusDetails(statusDetails));
            }
            stepResult = result -> result
                    .setStatus(translateTestCaseStatus(event.result))
                    .setStatusDetails(statusDetails);
        }

        lifecycle.updateStep(uuid, stepResult);
        lifecycle.stopStep(uuid);
    }

    private void handlePickleStep(final TestStepFinished event) {
        final StatusDetails statusDetails;
        if (event.result.getStatus() == Result.Type.UNDEFINED) {
            statusDetails =
                    ResultsUtils.getStatusDetails(new PendingException("TODO: implement me"))
                            .orElse(new StatusDetails());
            lifecycle.updateTestCase(getTestCaseUuid(currentTestCase), scenarioResult ->
                    scenarioResult
                            .setStatus(translateTestCaseStatus(event.result))
                            .setStatusDetails(statusDetails));
        } else {
            statusDetails =
                    ResultsUtils.getStatusDetails(event.result.getError())
                            .orElse(new StatusDetails());
        }

        final TagParser tagParser = new TagParser(currentFeature, currentTestCase);
        statusDetails
                .setFlaky(tagParser.isFlaky())
                .setMuted(tagParser.isMuted())
                .setKnown(tagParser.isKnown());

        lifecycle.updateStep(getStepUuid(event.testStep), stepResult ->
                stepResult.setStatus(translateTestCaseStatus(event.result)));
        lifecycle.stopStep(getStepUuid(event.testStep));
    }
}
