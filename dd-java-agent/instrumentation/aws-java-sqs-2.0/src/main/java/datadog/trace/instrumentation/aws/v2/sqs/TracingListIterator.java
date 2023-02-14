package datadog.trace.instrumentation.aws.v2.sqs;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.closePrevious;

import java.util.ListIterator;
import software.amazon.awssdk.services.sqs.model.Message;

public class TracingListIterator extends TracingIterator<ListIterator<Message>>
    implements ListIterator<Message> {

  public TracingListIterator(ListIterator<Message> delegate) {
    super(delegate);
  }

  @Override
  public boolean hasPrevious() {
    boolean moreMessages = delegate.hasPrevious();
    if (!moreMessages) {
      // no more messages, use this as a signal to close the last iteration scope
      closePrevious(true);
    }
    return moreMessages;
  }

  @Override
  public Message previous() {
    Message prev = delegate.previous();
    startNewMessageSpan(prev);
    return prev;
  }

  @Override
  public int nextIndex() {
    return delegate.nextIndex();
  }

  @Override
  public int previousIndex() {
    return delegate.previousIndex();
  }

  @Override
  public void set(Message message) {
    delegate.set(message);
  }

  @Override
  public void add(Message message) {
    delegate.add(message);
  }
}
