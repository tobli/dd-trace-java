package datadog.trace.bootstrap.instrumentation.decorator;

import static datadog.trace.util.Strings.toJson;

import datadog.trace.api.Config;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.DDTags;
import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.codeowners.Codeowners;
import datadog.trace.api.civisibility.source.MethodLinesResolver;
import datadog.trace.api.civisibility.source.SourcePathResolver;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import javax.annotation.Nullable;

public abstract class TestDecorator extends BaseDecorator {

  public static final String TEST_TYPE = "test";
  public static final String TEST_PASS = "pass";
  public static final String TEST_FAIL = "fail";
  public static final String TEST_SKIP = "skip";
  public static final UTF8BytesString CIAPP_TEST_ORIGIN = UTF8BytesString.create("ciapp-test");

  private volatile TestState testModuleState;
  private final ConcurrentMap<TestSuiteDescriptor, Integer> testSuiteNestedCallCounters =
      new ConcurrentHashMap<>();

  private final ConcurrentMap<TestSuiteDescriptor, TestState> testSuiteStates =
      new ConcurrentHashMap<>();

  public boolean isCI() {
    return InstrumentationBridge.isCi();
  }

  public Map<String, String> getCiTags() {
    return InstrumentationBridge.getCiTags();
  }

  protected abstract String testFramework();

  protected String testType() {
    return TEST_TYPE;
  }

  protected String testSpanKind() {
    return Tags.SPAN_KIND_TEST;
  }

  protected String testSuiteSpanKind() {
    return Tags.SPAN_KIND_TEST_SUITE;
  }

  protected String testModuleSpanKind() {
    return Tags.SPAN_KIND_TEST_MODULE;
  }

  protected String runtimeName() {
    return System.getProperty("java.runtime.name");
  }

  protected String runtimeVendor() {
    return System.getProperty("java.vendor");
  }

  protected String runtimeVersion() {
    return System.getProperty("java.version");
  }

  protected String osArch() {
    return System.getProperty("os.arch");
  }

  protected String osPlatform() {
    return System.getProperty("os.name");
  }

  protected String osVersion() {
    return System.getProperty("os.version");
  }

  protected UTF8BytesString origin() {
    return CIAPP_TEST_ORIGIN;
  }

  @Override
  protected CharSequence spanType() {
    return null;
  }

  @Override
  public AgentSpan afterStart(final AgentSpan span) {
    span.setTag(Tags.TEST_FRAMEWORK, testFramework());
    span.setTag(Tags.TEST_TYPE, testType());
    span.setSamplingPriority(PrioritySampling.SAMPLER_KEEP);
    span.setTag(Tags.RUNTIME_NAME, runtimeName());
    span.setTag(Tags.RUNTIME_VENDOR, runtimeVendor());
    span.setTag(Tags.RUNTIME_VERSION, runtimeVersion());
    span.setTag(Tags.OS_ARCHITECTURE, osArch());
    span.setTag(Tags.OS_PLATFORM, osPlatform());
    span.setTag(Tags.OS_VERSION, osVersion());
    span.setTag(DDTags.ORIGIN_KEY, CIAPP_TEST_ORIGIN);

    Map<String, String> ciTags = InstrumentationBridge.getCiTags();
    for (final Map.Entry<String, String> ciTag : ciTags.entrySet()) {
      span.setTag(ciTag.getKey(), ciTag.getValue());
    }

    return super.afterStart(span);
  }

  protected void afterTestModuleStart(final AgentSpan span, final @Nullable String version) {
    span.setSpanType(InternalSpanTypes.TEST_MODULE_END);
    span.setTag(Tags.SPAN_KIND, testModuleSpanKind());

    span.setResourceName(InstrumentationBridge.getModule());
    span.setTag(Tags.TEST_MODULE, InstrumentationBridge.getModule());
    span.setTag(Tags.TEST_BUNDLE, InstrumentationBridge.getModule());

    // Version can be null. The testing framework version extraction is best-effort basis.
    if (version != null) {
      span.setTag(Tags.TEST_FRAMEWORK_VERSION, version);
    }

    testModuleState = new TestState(span.getSpanId());

    afterStart(span);
  }

  protected void beforeTestModuleFinish(AgentSpan span) {
    span.setTag(Tags.TEST_MODULE_ID, testModuleState.id);

    String testModuleStatus = (String) span.getTag(Tags.TEST_STATUS);
    if (testModuleStatus == null) {
      span.setTag(Tags.TEST_STATUS, testModuleState.getStatus());
    }

    beforeFinish(span);
  }

  protected boolean tryTestSuiteStart(String testSuiteName, Class<?> testClass) {
    TestSuiteDescriptor testSuiteDescriptor = new TestSuiteDescriptor(testSuiteName, testClass);
    Integer counter = testSuiteNestedCallCounters.merge(testSuiteDescriptor, 1, Integer::sum);
    return counter == 1;
  }

  protected boolean tryTestSuiteFinish(String testSuiteName, Class<?> testClass) {
    TestSuiteDescriptor testSuiteDescriptor = new TestSuiteDescriptor(testSuiteName, testClass);
    Integer counter =
        testSuiteNestedCallCounters.merge(
            testSuiteDescriptor, -1, (a, b) -> a + b > 0 ? a + b : null);
    return counter == null;
  }

  protected void afterTestSuiteStart(
      final AgentSpan span,
      final String testSuiteName,
      final @Nullable String version,
      final @Nullable Class<?> testClass,
      final @Nullable List<String> categories) {
    span.setSpanType(InternalSpanTypes.TEST_SUITE_END);
    span.setTag(Tags.SPAN_KIND, testSuiteSpanKind());

    span.setResourceName(testSuiteName);
    span.setTag(Tags.TEST_SUITE, testSuiteName);
    span.setTag(Tags.TEST_MODULE, InstrumentationBridge.getModule());
    span.setTag(Tags.TEST_BUNDLE, InstrumentationBridge.getModule());

    // Version can be null. The testing framework version extraction is best-effort basis.
    if (version != null) {
      span.setTag(Tags.TEST_FRAMEWORK_VERSION, version);
    }

    if (categories != null && !categories.isEmpty()) {
      span.setTag(
          Tags.TEST_TRAITS, toJson(Collections.singletonMap("category", toJson(categories))));
    }

    if (testClass != null) {
      if (Config.get().isCiVisibilitySourceDataEnabled()) {
        SourcePathResolver sourcePathResolver = InstrumentationBridge.getSourcePathResolver();
        String sourcePath = sourcePathResolver.getSourcePath(testClass);
        if (sourcePath != null && !sourcePath.isEmpty()) {
          span.setTag(Tags.TEST_SOURCE_FILE, sourcePath);
        }
      }
    }

    TestSuiteDescriptor testSuiteDescriptor = new TestSuiteDescriptor(testSuiteName, testClass);
    testSuiteStates.put(testSuiteDescriptor, new TestState(span.getSpanId()));

    afterStart(span);
  }

  protected void beforeTestSuiteFinish(
      AgentSpan span, final String testSuiteName, final @Nullable Class<?> testClass) {
    TestSuiteDescriptor testSuiteDescriptor = new TestSuiteDescriptor(testSuiteName, testClass);
    TestState testSuiteState = testSuiteStates.remove(testSuiteDescriptor);

    span.setTag(Tags.TEST_SUITE_ID, testSuiteState.id);
    span.setTag(Tags.TEST_MODULE_ID, testModuleState.id);

    String testSuiteStatus = (String) span.getTag(Tags.TEST_STATUS);
    if (testSuiteStatus == null) {
      testSuiteStatus = testSuiteState.getStatus();
    }

    span.setTag(Tags.TEST_STATUS, testSuiteStatus);
    testModuleState.reportChildStatus(testSuiteStatus);

    beforeFinish(span);
  }

  protected void afterTestStart(
      final AgentSpan span,
      final String testSuiteName,
      final String testName,
      final @Nullable String testParameters,
      final @Nullable String version,
      final @Nullable Class<?> testClass,
      final @Nullable Method testMethod,
      final @Nullable List<String> categories) {

    span.setSpanType(InternalSpanTypes.TEST);
    span.setTag(Tags.SPAN_KIND, testSpanKind());

    span.setResourceName(testSuiteName + "." + testName);
    span.setTag(Tags.TEST_NAME, testName);
    span.setTag(Tags.TEST_SUITE, testSuiteName);
    span.setTag(Tags.TEST_MODULE, InstrumentationBridge.getModule());
    span.setTag(Tags.TEST_BUNDLE, InstrumentationBridge.getModule());

    if (testParameters != null) {
      span.setTag(Tags.TEST_PARAMETERS, testParameters);
    }

    // Version can be null. The testing framework version extraction is best-effort basis.
    if (version != null) {
      span.setTag(Tags.TEST_FRAMEWORK_VERSION, version);
    }

    if (categories != null && !categories.isEmpty()) {
      span.setTag(
          Tags.TEST_TRAITS, toJson(Collections.singletonMap("category", toJson(categories))));
    }

    if (Config.get().isCiVisibilitySourceDataEnabled()) {
      populateSourceDataTags(span, testClass, testMethod);
    }

    afterStart(span);
  }

  private void populateSourceDataTags(AgentSpan span, Class<?> testClass, Method testMethod) {
    if (testClass == null) {
      return;
    }

    SourcePathResolver sourcePathResolver = InstrumentationBridge.getSourcePathResolver();
    String sourcePath = sourcePathResolver.getSourcePath(testClass);
    if (sourcePath == null || sourcePath.isEmpty()) {
      return;
    }

    span.setTag(Tags.TEST_SOURCE_FILE, sourcePath);

    if (testMethod != null) {
      MethodLinesResolver methodLinesResolver = InstrumentationBridge.getMethodLinesResolver();
      MethodLinesResolver.MethodLines testMethodLines = methodLinesResolver.getLines(testMethod);
      if (testMethodLines.isValid()) {
        span.setTag(Tags.TEST_SOURCE_START, testMethodLines.getStartLineNumber());
        span.setTag(Tags.TEST_SOURCE_END, testMethodLines.getFinishLineNumber());
      }
    }

    Codeowners codeowners = InstrumentationBridge.getCodeowners();
    Collection<String> testCodeOwners = codeowners.getOwners(sourcePath);
    if (testCodeOwners != null) {
      span.setTag(Tags.TEST_CODEOWNERS, toJson(testCodeOwners));
    }
  }

  protected void beforeTestFinish(
      AgentSpan span, String testSuiteName, @Nullable Class<?> testClass) {
    TestSuiteDescriptor testSuiteDescriptor = new TestSuiteDescriptor(testSuiteName, testClass);
    TestState testSuiteState = testSuiteStates.get(testSuiteDescriptor);
    if (testSuiteState != null) {
      span.setTag(Tags.TEST_SUITE_ID, testSuiteState.id);
      span.setTag(Tags.TEST_MODULE_ID, testModuleState.id);

      String testCaseStatus = (String) span.getTag(Tags.TEST_STATUS);
      testSuiteState.reportChildStatus(testCaseStatus);

    } else {
      // TODO log a warning (when test suite level visibility support is there for all frameworks
    }

    beforeFinish(span);
  }

  public List<Method> testMethods(
      final Class<?> testClass, final Class<? extends Annotation> testAnnotation) {
    final List<Method> testMethods = new ArrayList<>();

    final Method[] methods = testClass.getMethods();
    for (final Method method : methods) {
      if (method.getAnnotation(testAnnotation) != null) {
        testMethods.add(method);
      }
    }
    return testMethods;
  }

  public boolean isTestSpan(@Nullable final AgentSpan activeSpan) {
    if (activeSpan == null) {
      return false;
    }

    return DDSpanTypes.TEST.equals(activeSpan.getSpanType())
        && testType().equals(activeSpan.getTag(Tags.TEST_TYPE));
  }

  public boolean isTestSuiteSpan(@Nullable final AgentSpan activeSpan) {
    if (activeSpan == null) {
      return false;
    }

    return DDSpanTypes.TEST_SUITE_END.equals(activeSpan.getSpanType())
        && testType().equals(activeSpan.getTag(Tags.TEST_TYPE));
  }

  public boolean isTestModuleSpan(@Nullable final AgentSpan activeSpan) {
    if (activeSpan == null) {
      return false;
    }

    return DDSpanTypes.TEST_MODULE_END.equals(activeSpan.getSpanType())
        && testType().equals(activeSpan.getTag(Tags.TEST_TYPE));
  }

  private static final class TestSuiteDescriptor {
    private final String testSuiteName;
    private final Class<?> testClass;

    private TestSuiteDescriptor(String testSuiteName, Class<?> testClass) {
      this.testSuiteName = testSuiteName;
      this.testClass = testClass;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      TestSuiteDescriptor that = (TestSuiteDescriptor) o;
      return Objects.equals(testSuiteName, that.testSuiteName)
          && Objects.equals(testClass, that.testClass);
    }

    @Override
    public int hashCode() {
      return Objects.hash(testSuiteName, testClass);
    }
  }

  private static final class TestState {
    private final long id;
    private final LongAdder childrenPassed = new LongAdder();
    private final LongAdder childrenFailed = new LongAdder();
    private final AtomicInteger nested = new AtomicInteger(0);

    private TestState(long id) {
      this.id = id;
    }

    public void reportChildStatus(String status) {
      switch (status) {
        case TEST_PASS:
          childrenPassed.increment();
          break;
        case TEST_FAIL:
          childrenFailed.increment();
          break;
        default:
          break;
      }
    }

    public String getStatus() {
      if (childrenFailed.sum() > 0) {
        return TEST_FAIL;
      } else if (childrenPassed.sum() > 0) {
        return TEST_PASS;
      } else {
        return TEST_SKIP;
      }
    }
  }
}
