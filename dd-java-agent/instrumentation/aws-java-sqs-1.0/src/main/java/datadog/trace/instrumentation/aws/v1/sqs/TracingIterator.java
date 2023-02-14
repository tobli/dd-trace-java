package datadog.trace.instrumentation.aws.v1.sqs;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.closePrevious;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.instrumentation.aws.v1.sqs.MessageExtractAdapter.GETTER;

import com.amazonaws.services.sqs.model.Message;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Iterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TracingIterator<L extends Iterator<Message>> implements Iterator<Message> {
  private static final Logger log = LoggerFactory.getLogger(TracingIterator.class);

  protected final L delegate;

  public TracingIterator(L delegate) {
    this.delegate = delegate;
  }

  @Override
  public boolean hasNext() {
    boolean moreMessages = delegate.hasNext();
    if (!moreMessages) {
      // no more messages, use this as a signal to close the last iteration scope
      closePrevious(true);
    }
    return moreMessages;
  }

  @Override
  public Message next() {
    Message next = delegate.next();
    startNewMessageSpan(next);
    return next;
  }

  protected void startNewMessageSpan(Message message) {
    try {
      closePrevious(true);
      AgentSpan span, queueSpan = null;
      if (message != null) {
        AgentSpan.Context spanContext = propagate().extract(message, GETTER);
        if (spanContext != null) {
          log.info(
              "======== MESSAGE {}:{}",
              spanContext.getTraceId(),
              spanContext.getSpanId(),
              new Throwable());
        }
      }
    } catch (Exception e) {
      log.debug("Error starting new message span", e);
    }
  }

  @Override
  public void remove() {
    delegate.remove();
  }
}
