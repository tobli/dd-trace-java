package datadog.trace.common.writer.ddintake;

import static datadog.trace.api.intake.TrackType.NOOP;
import static datadog.trace.common.writer.DDIntakeWriter.DEFAULT_INTAKE_TIMEOUT;

import datadog.communication.http.OkHttpUtils;
import datadog.communication.http.RetryPolicy;
import datadog.trace.api.intake.TrackType;
import datadog.trace.common.writer.Payload;
import datadog.trace.common.writer.RemoteApi;
import datadog.trace.common.writer.RemoteResponseListener;
import datadog.trace.relocate.api.IOLogger;
import java.io.IOException;
import java.net.ConnectException;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The API pointing to a DD Intake endpoint */
public class DDEvpProxyApi implements RemoteApi {

  private static final String DD_EVP_SUBDOMAIN_HEADER = "X-Datadog-EVP-Subdomain";
  private static final Logger log = LoggerFactory.getLogger(DDEvpProxyApi.class);
  private final IOLogger ioLogger = new IOLogger(log);

  private long totalTraces = 0;
  private long receivedTraces = 0;
  private long sentTraces = 0;
  private long failedTraces = 0;

  public static DDEvpProxyApiBuilder builder() {
    return new DDEvpProxyApiBuilder();
  }

  public static class DDEvpProxyApiBuilder {
    private TrackType trackType = TrackType.NOOP;
    private long timeoutMillis = TimeUnit.SECONDS.toMillis(DEFAULT_INTAKE_TIMEOUT);

    HttpUrl agentUrl = null;
    OkHttpClient httpClient = null;
    RetryPolicy retryPolicy = null;
    String evpProxyEndpoint;

    public DDEvpProxyApiBuilder trackType(final TrackType trackType) {
      this.trackType = trackType;
      return this;
    }

    public DDEvpProxyApiBuilder evpProxyEndpoint(final String evpProxyEndpoint) {
      this.evpProxyEndpoint = evpProxyEndpoint;
      return this;
    }

    public DDEvpProxyApiBuilder timeoutMillis(final long timeoutMillis) {
      this.timeoutMillis = timeoutMillis;
      return this;
    }

    public DDEvpProxyApiBuilder agentUrl(final HttpUrl agentUrl) {
      this.agentUrl = agentUrl;
      return this;
    }

    public DDEvpProxyApiBuilder retryPolicy(final RetryPolicy retryPolicy) {
      this.retryPolicy = retryPolicy;
      return this;
    }

    DDEvpProxyApiBuilder httpClient(final OkHttpClient httpClient) {
      this.httpClient = httpClient;
      return this;
    }

    public DDEvpProxyApi build() {
      final String trackName = (trackType != null ? trackType.name() : NOOP.name()).toLowerCase();
      final String subdomain = String.format("%s-intake", trackName);

      System.out.println(agentUrl.toString() + " + " + evpProxyEndpoint);

      final HttpUrl evpProxyUrl = agentUrl.resolve(evpProxyEndpoint);

      final OkHttpClient client =
          (httpClient != null) ? httpClient : OkHttpUtils.buildHttpClient(evpProxyUrl, timeoutMillis);

      if (null == retryPolicy) {
        retryPolicy = RetryPolicy.builder().withMaxRetry(5).withBackoff(100).build();
      }

      System.out.println(evpProxyUrl.toString());
      return new DDEvpProxyApi(client, agentUrl, subdomain, retryPolicy);
    }
  }

  private final OkHttpClient httpClient;
  private final HttpUrl evpProxyUrl;
  private final String subdomain;
  private final RetryPolicy retryPolicy;

  private DDEvpProxyApi(
      OkHttpClient httpClient, HttpUrl evpProxyUrl, String subdomain, RetryPolicy retryPolicy) {
    this.httpClient = httpClient;
    this.evpProxyUrl = evpProxyUrl;
    this.subdomain = subdomain;
    this.retryPolicy = retryPolicy;
  }

  @Override
  public RemoteApi.Response sendSerializedTraces(Payload payload) {
    final int sizeInBytes = payload.sizeInBytes();
    boolean shouldRetry;
    int retry = 1;

    try {
      final Request request =
          new Request.Builder()
              .url(evpProxyUrl)
              .addHeader(DD_EVP_SUBDOMAIN_HEADER, subdomain)
              .post(payload.toRequest())
              .build();
      this.totalTraces += payload.traceCount();
      this.receivedTraces += payload.traceCount();

      int httpCode = 0;
      IOException lastException = null;
      Response lastResponse = null;
      while (true) {
        // Exponential backoff retry when http code >= 500 or ConnectException is thrown.
        try (final okhttp3.Response response = httpClient.newCall(request).execute()) {
          httpCode = response.code();
          shouldRetry = httpCode >= 500 && retryPolicy.shouldRetry(retry);
          if (!shouldRetry && httpCode >= 400) {
            lastResponse = new Response(httpCode, response.message(), getResponseBody(response));
          }
        } catch (ConnectException ex) {
          shouldRetry = retryPolicy.shouldRetry(retry);
          lastException = ex;
        }

        if (shouldRetry) {
          long backoffMs = retryPolicy.backoff(retry);
          try {
            Thread.sleep(backoffMs);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
          }
          retry++;
        } else {
          if (httpCode < 200 || httpCode >= 300) {
            countAndLogFailedSend(payload.traceCount(), sizeInBytes, lastResponse, null);
            return RemoteApi.Response.failed(httpCode);
          } else if (lastException != null) {
            throw lastException;
          }
          countAndLogSuccessfulSend(payload.traceCount(), sizeInBytes);
          return RemoteApi.Response.success(httpCode);
        }
      }
    } catch (final IOException e) {
      countAndLogFailedSend(payload.traceCount(), sizeInBytes, null, e);
      return RemoteApi.Response.failed(e);
    }
  }

  private void countAndLogSuccessfulSend(final int traceCount, final int sizeInBytes) {
    // count the successful traces
    this.sentTraces += traceCount;

    ioLogger.success(createSendLogMessage(traceCount, sizeInBytes, "Success"));
  }

  private void countAndLogFailedSend(
      int traceCount,
      int sizeInBytes,
      final DDEvpProxyApi.Response response,
      final IOException outer) {
    // count the failed traces
    this.failedTraces += traceCount;
    // these are used to catch and log if there is a failure in debug logging the response body
    String intakeError = response != null ? response.body : "";
    String sendErrorString =
        createSendLogMessage(
            traceCount, sizeInBytes, intakeError.isEmpty() ? "Error" : intakeError);

    ioLogger.error(sendErrorString, toLoggerResponse(response), outer);
  }

  private static IOLogger.Response toLoggerResponse(DDEvpProxyApi.Response response) {
    if (response == null) {
      return null;
    }
    return new IOLogger.Response(response.code, response.message, response.body);
  }

  private static String getResponseBody(okhttp3.Response response) {
    if (response != null) {
      try {
        return response.body().string().trim();
      } catch (NullPointerException | IOException ignored) {
      }
    }
    return "";
  }

  private String createSendLogMessage(
      final int traceCount, final int sizeInBytes, final String prefix) {
    String sizeString = sizeInBytes > 1024 ? (sizeInBytes / 1024) + "KB" : sizeInBytes + "B";
    return prefix
        + " while sending "
        + traceCount
        + " (size="
        + sizeString
        + ")"
        + " traces to the Agent EVP Proxy."
        + " Total: "
        + this.totalTraces
        + ", Received: "
        + this.receivedTraces
        + ", Sent: "
        + this.sentTraces
        + ", Failed: "
        + this.failedTraces
        + ".";
  }

  @Override
  public void addResponseListener(RemoteResponseListener listener) {}

  private static class Response {
    private final int code;
    private final String message;
    private final String body;

    public Response(final int code, final String message, final String body) {
      this.code = code;
      this.message = message;
      this.body = body;
    }
  }
}
