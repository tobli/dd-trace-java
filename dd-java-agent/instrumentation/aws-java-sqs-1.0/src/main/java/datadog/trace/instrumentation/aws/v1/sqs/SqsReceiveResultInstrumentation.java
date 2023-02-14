package datadog.trace.instrumentation.aws.v1.sqs;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.amazonaws.services.sqs.model.Message;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.List;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class SqsReceiveResultInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {
  public SqsReceiveResultInstrumentation() {
    super("aws-sdk");
  }

  @Override
  public String instrumentedType() {
    return "com.amazonaws.services.sqs.model.ReceiveMessageResult";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".MessageExtractAdapter",
      packageName + ".TracingIterator",
      packageName + ".TracingList",
      packageName + ".TracingListIterator"
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(named("getMessages")), getClass().getName() + "$GetMessagesAdvice");
  }

  public static class GetMessagesAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.Return(readOnly = false) List<Message> messages) {
      if (activeSpan() instanceof AgentTracer.NoopAgentSpan
          || Config.get().isLegacyTracingEnabled(false, "aws-sdk")) {
        return; // don't wrap if we're still inside the request or this is a legacy request
      }
      if (null != messages && !(messages instanceof TracingList)) {
        messages = new TracingList(messages);
      }
    }
  }
}
