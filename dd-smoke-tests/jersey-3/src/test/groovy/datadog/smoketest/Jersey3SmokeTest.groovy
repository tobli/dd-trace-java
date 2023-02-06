package datadog.smoketest

import okhttp3.Request

import static datadog.trace.api.config.IastConfig.*

class Jersey3SmokeTest extends AbstractServerSmokeTest {

  @Override
  def logLevel(){
    return "debug"
  }

  @Override
  ProcessBuilder createProcessBuilder() {
    String jarPath = System.getProperty("datadog.smoketest.jersey3.jar.path")

    List<String> command = new ArrayList<>()
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.addAll([
      withSystemProperty(IAST_ENABLED, true),
      withSystemProperty(IAST_REQUEST_SAMPLING, 100),
      withSystemProperty(IAST_DEBUG_ENABLED, true),
      withSystemProperty(IAST_DEDUPLICATION_ENABLED, false),
      withSystemProperty("integration.grizzly.enabled", true)
    ])
    //command.add("-Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=8000")
    //command.add("-Xdebug")
    command.addAll((String[]) ["-jar", jarPath, httpPort])
    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
  }

  def "Test path parameter in Jersey"() {
    setup:
    def url = "http://localhost:${httpPort}/hello/bypathparam/pathParamValue"
    boolean sqlInjectionFound = false

    when:
    def request = new Request.Builder().url(url).get().build()
    def response = client.newCall(request).execute()
    checkLog {
      if (it.contains("SQL_INJECTION") && it.contains("smoketest.DB") && it.contains("pathParamValue")) {
        sqlInjectionFound = true
      }
    }

    then:
    String body = response.body().string()
    assert body != null
    assert response.body().contentType().toString().contains("text/plain")
    assert body.contains("Jersey: hello pathParamValue")
    assert response.code() == 200
    assert sqlInjectionFound
  }

  def "Test query parameter in Jersey"() {
    setup:
    def url = "http://localhost:${httpPort}/hello/byqueryparam?param=queryParamValue"
    boolean sqlInjectionFound = false

    when:
    def request = new Request.Builder().url(url).get().build()
    def response = client.newCall(request).execute()
    checkLog {
      if (it.contains("SQL_INJECTION") && it.contains("smoketest.DB") && it.contains("queryParamValue")) {
        sqlInjectionFound = true
      }
    }

    then:
    String body = response.body().string()
    assert body != null
    assert response.body().contentType().toString().contains("text/plain")
    assert body.contains("Jersey: hello queryParamValue")
    assert response.code() == 200
    assert sqlInjectionFound
  }

  private static String withSystemProperty(final String config, final Object value) {
    return "-Ddd.${config}=${value}"
  }
}
