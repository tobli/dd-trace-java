import datadog.smoketest.AbstractServerSmokeTest
import datadog.trace.test.agent.decoder.DecodedSpan
import groovy.json.JsonSlurper
import groovy.transform.CompileDynamic
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import spock.util.concurrent.PollingConditions

import java.util.function.Function
import java.util.function.Predicate

import static datadog.trace.api.config.IastConfig.*

@CompileDynamic
class IastSpringBootSmokeTest extends AbstractServerSmokeTest {

  private static final String TAG_NAME = '_dd.iast.json'

  private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8")

  @Override
  def logLevel() {
    return "debug"
  }

  @Override
  Closure decodedTracesCallback() {
    return {} // force traces decoding
  }

  @Override
  ProcessBuilder createProcessBuilder() {
    String springBootShadowJar = System.getProperty("datadog.smoketest.springboot.shadowJar.path")

    List<String> command = new ArrayList<>()
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.addAll([
      withSystemProperty(IAST_ENABLED, true),
      withSystemProperty(IAST_REQUEST_SAMPLING, 100),
      withSystemProperty(IAST_DEBUG_ENABLED, true)
    ])
    command.addAll((String[]) ["-jar", springBootShadowJar, "--server.port=${httpPort}"])
    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
    // Spring will print all environment variables to the log, which may pollute it and affect log assertions.
    processBuilder.environment().clear()
    processBuilder
  }

  void "IAST subsystem starts"() {
    given: 'an initial request has succeeded'
    String url = "http://localhost:${httpPort}/greeting"
    def request = new Request.Builder().url(url).get().build()
    client.newCall(request).execute()

    when: 'logs are read'
    String startMsg = null
    String errorMsg = null
    checkLog {
      if (it.contains("Not starting IAST subsystem")) {
        errorMsg = it
      }
      if (it.contains("IAST is starting")) {
        startMsg = it
      }
      // Check that there's no logged exception about missing classes from Datadog.
      // We had this problem before with JDK9StackWalker.
      if (it.contains("java.lang.ClassNotFoundException: datadog/")) {
        errorMsg = it
      }
    }

    then: 'there are no errors in the log and IAST has started'
    errorMsg == null
    startMsg != null
    !logHasErrors
  }

  void "default home page without errors"() {
    setup:
    String url = "http://localhost:${httpPort}/greeting"
    def request = new Request.Builder().url(url).get().build()

    when:
    def response = client.newCall(request).execute()

    then:
    def responseBodyStr = response.body().string()
    responseBodyStr != null
    responseBodyStr.contains("Sup Dawg")
    response.body().contentType().toString().contains("text/plain")
    response.code() == 200

    checkLog()
    !logHasErrors
  }

  void "iast.enabled tag is present"() {
    setup:
    String url = "http://localhost:${httpPort}/greeting"
    def request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    waitForSpan(new PollingConditions(timeout: 5), hasMetric('_dd.iast.enabled', 1))
  }

  void "weak hash vulnerability is present"() {
    setup:
    String url = "http://localhost:${httpPort}/weakhash"
    def request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    waitForSpan(new PollingConditions(timeout: 5),
    hasVulnerability(type('WEAK_HASH').and(evidence('MD5'))))
  }

  void "weak hash vulnerability is present on boot"() {
    setup:
    String url = "http://localhost:${httpPort}/greeting"
    def request = new Request.Builder().url(url).get().build()

    when: 'ensure the controller is loaded'
    client.newCall(request).execute()

    then: 'a vulnerability pops in the logs (startup traces might not always be available)'
    hasVulnerabilityInLogs(type('WEAK_HASH').and(evidence('SHA1')).and(withSpan()))
  }

  void "weak hash vulnerability is present on thread"() {
    setup:
    String url = "http://localhost:${httpPort}/async_weakhash"
    def request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    waitForSpan(new PollingConditions(timeout: 5),
    hasVulnerability(type('WEAK_HASH').and(evidence('MD4')).and(withSpan())))
  }

  void "getParameter taints string"() {
    setup:
    String url = "http://localhost:${httpPort}/getparameter?param=A"
    def request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    hasTainted { tainted ->
      tainted.value == 'A' &&
        tainted.ranges[0].source.name == 'param' &&
        tainted.ranges[0].source.origin == 'http.request.parameter'
    }
  }

  void "command injection is present with runtime"() {
    setup:
    final url = "http://localhost:${httpPort}/cmdi/runtime?cmd=ls"
    final request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    waitForSpan(new PollingConditions(timeout: 5), hasVulnerability(type("COMMAND_INJECTION")))
  }

  void "command injection is present with process builder"() {
    setup:
    final url = "http://localhost:${httpPort}/cmdi/process_builder?cmd=ls"
    final request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    waitForSpan(new PollingConditions(timeout: 5), hasVulnerability(type("COMMAND_INJECTION")))
  }

  void "path traversal is present with file"() {
    setup:
    final url = "http://localhost:${httpPort}/path_traversal/file?path=test"
    final request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    waitForSpan(new PollingConditions(timeout: 5), hasVulnerability(type("PATH_TRAVERSAL")))
  }

  void "path traversal is present with paths"() {
    setup:
    final url = "http://localhost:${httpPort}/path_traversal/paths?path=test"
    final request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    waitForSpan(new PollingConditions(timeout: 5), hasVulnerability(type("PATH_TRAVERSAL")))
  }

  void "path traversal is present with path"() {
    setup:
    final url = "http://localhost:${httpPort}/path_traversal/path?path=test"
    final request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    waitForSpan(new PollingConditions(timeout: 5), hasVulnerability(type("PATH_TRAVERSAL")))
  }

  void "parameter binding taints bean strings"() {
    setup:
    String url = "http://localhost:${httpPort}/param_binding/test?name=parameter&value=binding"
    def request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    hasTainted { tainted ->
      tainted.value == 'binding' &&
        tainted.ranges[0].source.name == 'value' &&
        tainted.ranges[0].source.origin == 'http.request.parameter'
    }
  }

  void "request header taint string"() {
    setup:
    String url = "http://localhost:${httpPort}/request_header/test"
    def request = new Request.Builder().url(url).header("test-header", "test").get().build()

    when:
    client.newCall(request).execute()

    then:
    hasTainted { tainted ->
      tainted.value == 'test' &&
        tainted.ranges[0].source.name == 'test-header' &&
        tainted.ranges[0].source.origin == 'http.request.header'
    }
  }

  void "path param taint string"() {
    setup:
    String url = "http://localhost:${httpPort}/path_param?param=test"
    def request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    hasTainted { tainted ->
      tainted.value == 'test' &&
        tainted.ranges[0].source.name == 'param' &&
        tainted.ranges[0].source.origin == 'http.request.parameter'
    }
  }

  void "request body taint json"() {
    setup:
    String url = "http://localhost:${httpPort}/request_body/test"
    def request = new Request.Builder().url(url).post(RequestBody.create(JSON, '{"name": "nameTest", "value" : "valueTest"}')).build()

    when:
    client.newCall(request).execute()

    then:
    hasTainted { tainted ->
      tainted.value == 'nameTest' &&
        tainted.ranges[0].source.origin == 'http.request.body'
    }
  }

  void 'request query string'() {
    given:
    final url = "http://localhost:${httpPort}/query_string?key=value"
    final request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    hasTainted { tainted ->
      tainted.value == 'key=value' &&
        tainted.ranges[0].source.origin == 'http.request.query'
    }
  }

  void 'request cookie propagation'() {
    given:
    final url = "http://localhost:${httpPort}/cookie"
    final request = new Request.Builder().url(url).header('Cookie', 'name=value').get().build()

    when:
    client.newCall(request).execute()

    then:
    hasTainted { tainted ->
      tainted.value == 'name' &&
        tainted.ranges[0].source.origin == 'http.request.cookie.name'
    }
    hasTainted { tainted ->
      tainted.value == 'value' &&
        tainted.ranges[0].source.name == 'name' &&
        tainted.ranges[0].source.origin == 'http.request.cookie.value'
    }
  }

  private static Function<DecodedSpan, Boolean> hasMetric(final String name, final Object value) {
    return new Function<DecodedSpan, Boolean>() {
        @Override
        Boolean apply(DecodedSpan decodedSpan) {
          return value == decodedSpan.metrics.get(name)
        }
      }
  }

  private static Function<DecodedSpan, Boolean> hasVulnerability(final Predicate<?> predicate) {
    return { span ->
      final iastMeta = span.meta.get(TAG_NAME)
      if (!iastMeta) {
        return false
      }
      final vulnerabilities = parseVulnerabilities(iastMeta)
      return vulnerabilities.stream().anyMatch(predicate)
    }
  }

  private boolean hasVulnerabilityInLogs(final Predicate<?> predicate) {
    def found = false
    checkLog { final String log ->
      final index = log.indexOf(TAG_NAME)
      if (index >= 0) {
        final vulnerabilities = parseVulnerabilities(log, index)
        found |= vulnerabilities.stream().anyMatch(predicate)
      }
    }
    return found
  }

  private void hasTainted(final Closure<Boolean> matcher) {
    final slurper = new JsonSlurper()
    final tainteds = []
    checkLog { String log ->
      final index = log.indexOf('tainted=')
      if (index >= 0) {
        final tainted = slurper.parse(new StringReader(log.substring(index + 8)))
        tainteds.add(tainted)
      }
    }
    assert tainteds.any(matcher)
  }

  private static Collection<?> parseVulnerabilities(final String log, final int startIndex) {
    final chars = log.toCharArray()
    final builder = new StringBuilder()
    def level = 0
    for (int i = log.indexOf('{', startIndex); i < chars.length; i++) {
      final current = chars[i]
      if (current == '{' as char) {
        level++
      } else if (current == '}' as char) {
        level--
      }
      builder.append(chars[i])
      if (level == 0) {
        break
      }
    }
    return parseVulnerabilities(builder.toString())
  }

  private static Collection<?> parseVulnerabilities(final String iastJson) {
    final slurper = new JsonSlurper()
    final parsed = slurper.parseText(iastJson)
    return parsed['vulnerabilities'] as Collection
  }

  private static Predicate<?> type(final String type) {
    return new Predicate<Object>() {
        @Override
        boolean test(Object vul) {
          return vul['type'] == type
        }
      }
  }

  private static Predicate<?> evidence(final String value) {
    return new Predicate<Object>() {
        @Override
        boolean test(Object vul) {
          return vul['evidence']['value'] == value
        }
      }
  }

  private static Predicate<?> withSpan() {
    return new Predicate<Object>() {
        @Override
        boolean test(Object vul) {
          return vul['location']['spanId'] > 0
        }
      }
  }

  private static String withSystemProperty(final String config, final Object value) {
    return "-Ddd.${config}=${value}"
  }
}
