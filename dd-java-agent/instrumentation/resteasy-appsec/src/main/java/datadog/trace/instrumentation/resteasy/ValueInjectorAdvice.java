package datadog.trace.instrumentation.resteasy;

import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.source.WebModule;
import net.bytebuddy.asm.Advice;

public class ValueInjectorAdvice {
  @Advice.OnMethodExit
  public static void onExit(@Advice.Return(readOnly = true) Object result) {
    if (result instanceof String) {
      System.out.println(
          "Value Injector Instrumentation Called with value ["
              + result
              + "] identity hash code: "
              + System.identityHashCode(result));
      final WebModule module = InstrumentationBridge.WEB;

      if (module != null) {
        try {
          module.onParameterValue(null, (String) result);
        } catch (final Throwable e) {
          module.onUnexpectedException("ValueInjectorAdvice.onExit threw", e);
        }
      } else {
        System.out.println("Module is null");
      }
    }
  }
}
