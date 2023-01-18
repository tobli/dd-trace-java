package com.datadog.iast;

import static com.datadog.iast.IastTag.ANALYZED;
import static com.datadog.iast.IastTag.SKIPPED;

import com.datadog.iast.HasDependencies.Dependencies;
import com.datadog.iast.overhead.OverheadController;
import com.datadog.iast.taint.TaintedObjects;
import com.datadog.iast.telemetry.IastTelemetry;
import datadog.trace.api.TraceSegment;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.IGSpanInfo;
import datadog.trace.api.gateway.RequestContext;
import java.util.function.BiFunction;
import javax.annotation.Nonnull;

public class RequestEndedHandler implements BiFunction<RequestContext, IGSpanInfo, Flow<Void>> {

  private final OverheadController overheadController;
  private final IastTelemetry telemetry;

  public RequestEndedHandler(@Nonnull final Dependencies dependencies) {
    this.overheadController = dependencies.getOverheadController();
    this.telemetry = dependencies.getTelemetry();
  }

  @Override
  public Flow<Void> apply(final RequestContext requestContext, final IGSpanInfo igSpanInfo) {
    final TraceSegment traceSegment = requestContext.getTraceSegment();
    final IastRequestContext iastRequestContext = IastRequestContext.get(requestContext);
    if (iastRequestContext != null) {
      ANALYZED.setTagTop(traceSegment);
      final TaintedObjects taintedObjects = iastRequestContext.getTaintedObjects();
      if (taintedObjects != null) {
        taintedObjects.release();
      }
      telemetry.onRequestEnded(iastRequestContext, traceSegment);
      overheadController.releaseRequest();
    } else {
      SKIPPED.setTagTop(traceSegment);
    }
    return Flow.ResultFlow.empty();
  }
}
