package dataddog.trace.instrumentation.jersey3

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.source.WebModule
import foo.bar.GenericClass
import jakarta.ws.rs.ext.ParamConverter
import org.glassfish.jersey.internal.inject.ParamConverters.StringConstructor

class ParamConverterTest extends AgentTestRunner {

  def 'ParamConverter test'(){
    setup:
    WebModule iastModule = Mock(WebModule)
    InstrumentationBridge.registerIastModule(iastModule)
    ParamConverter paramConverter =
      new StringConstructor()
      .getConverter(String, new GenericClass(String).getMyType(), null)

    when:
    paramConverter.fromString("Pepe")

    then:
    1 * iastModule.onParameterValue(null, "Pepe")
  }
}

