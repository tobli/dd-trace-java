package datadog.trace.instrumentation.jersey3;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class ParamConverterInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForTypeHierarchy {

  public ParamConverterInstrumentation() {
    super("ParamConverter");
  }

  @Override
  public String hierarchyMarkerType() {
    return "jakarta.ws.rs.ext.ParamConverter";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named("jakarta.ws.rs.ext.ParamConverter"));
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("fromString").and(isPublic()), packageName + ".ParamConverterAdvice");
  }
}
