package datadog.trace.instrumentation.aws.v2.sqs;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import java.util.Map;
import software.amazon.awssdk.services.sqs.model.Message;

public final class MessageExtractAdapter implements AgentPropagation.ContextVisitor<Message> {
  public static final MessageExtractAdapter GETTER = new MessageExtractAdapter();

  @Override
  public void forEachKey(Message carrier, AgentPropagation.KeyClassifier classifier) {
    for (Map.Entry<String, String> entry : carrier.attributesAsStrings().entrySet()) {
      String key = entry.getKey();
      if ("AWSTraceHeader".equalsIgnoreCase(key)) {
        key = "X-Amzn-Trace-Id";
      }
      if (!classifier.accept(key, entry.getValue())) {
        return;
      }
    }
  }
}
