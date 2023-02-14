package datadog.trace.instrumentation.aws.v1.sqs;

import com.amazonaws.services.sqs.model.Message;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import java.util.Map;

public final class MessageExtractAdapter implements AgentPropagation.ContextVisitor<Message> {
  public static final MessageExtractAdapter GETTER = new MessageExtractAdapter();

  @Override
  public void forEachKey(Message carrier, AgentPropagation.KeyClassifier classifier) {
    for (Map.Entry<String, String> entry : carrier.getAttributes().entrySet()) {
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
