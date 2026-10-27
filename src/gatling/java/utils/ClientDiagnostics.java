package utils;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import io.gatling.http.client.Request;
import io.gatling.http.client.impl.request.WritableRequest;
import io.gatling.http.response.Response;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import java.net.InetSocketAddress;
import java.util.regex.Pattern;
import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/** Temporary, opt-in transport diagnostics for Gatling 3.15.1; never format HTTP log arguments. */
public final class ClientDiagnostics extends AppenderBase<ILoggingEvent> {
  private static final Pattern TRACEPARENT =
    Pattern.compile("00-([0-9a-f]{32})-([0-9a-f]{16})-01");

  @Name("appreg.ClientTransport")
  @Label("AppReg client transport")
  @Category("AppReg")
  @StackTrace(false)
  public static final class TransportEvent extends Event {
    public String stage;
    public String traceId;
    public String spanId;
    public String channel;
    public String remoteHost;
    public int status;
    public long requestStartMillis;
    public long responseEndMillis;
  }

  @Override
  protected void append(ILoggingEvent loggingEvent) {
    var event = transportEvent(loggingEvent);
    if (event != null) event.commit();
  }

  public static void requestBoundary(String stage, Request request) {
    if (!Boolean.getBoolean("appRegClientDiagnostics")) return;
    var event = tracedEvent(stage, request.getHeaders().get("traceparent"));
    if (event != null) event.commit();
  }

  public static Response responseCompleted(Response response) {
    if (Boolean.getBoolean("appRegClientDiagnostics")) {
      var event = tracedEvent("response_completed", response.request().getHeaders().get("traceparent"));
      if (event != null) {
        event.requestStartMillis = response.startTimestamp();
        event.responseEndMillis = response.endTimestamp();
        event.status = response.status().code();
        event.commit();
      }
    }
    return response;
  }

  // Exact templates are deliberately version-specific. Unknown messages and all raw arguments
  // are discarded: Gatling's ordinary DEBUG output can contain passwords, cookies and bodies.
  static TransportEvent transportEvent(ILoggingEvent loggingEvent) {
    var args = loggingEvent.getArgumentArray();
    if (args == null) return null;
    var event = new TransportEvent();
    event.stage = switch (loggingEvent.getMessage()) {
      case "Opening new channel to remote={} from local={}" -> "tcp_connect_start";
      case "Connected to remoteAddress={} from localAddress={}" -> "tcp_connected";
      case "TLS handshake successful: peerHost={} peerPort={} protocol={} cipher suite={}" -> "tls_complete";
      case "Retrieved HTTP/1 channel from pool for key {}",
          "Retrieved HTTP/2 channel from pool for key {}",
          "Retrieved channel from coalescing pool for domain {}" -> "pool_reuse";
      case "No channel in the pool for key {}" -> "pool_miss";
      case "Offering channel entry {} to pool" -> "pool_return";
      case "{} WRITE: {}, [{}: {}], {}" -> "dns_query";
      case "{} RECEIVED: UDP [{}: {}], {}" -> "dns_answer";
      default -> null;
    };
    if ("Write request {}".equals(loggingEvent.getMessage()) && args.length > 0) {
      var request = args[0] instanceof WritableRequest writable ? writable.getRequest() : args[0];
      if (request instanceof HttpRequest httpRequest) {
        return tracedEvent("request_write", httpRequest.headers().get("traceparent"));
      }
    }
    if ("Read msg='{}'".equals(loggingEvent.getMessage())
        && args.length > 0 && args[0] instanceof HttpResponse response) {
      event.stage = "response_headers";
      event.status = response.status().code();
    }
    if (event.stage == null) return null;
    for (var arg : args) {
      if (arg instanceof Channel channel) event.channel = channel.id().asShortText();
      if (arg instanceof InetSocketAddress address) event.remoteHost = address.getHostString();
    }
    return event;
  }

  private static TransportEvent tracedEvent(String stage, String traceparent) {
    if (traceparent == null) return null;
    var match = TRACEPARENT.matcher(traceparent);
    if (!match.matches()) return null;
    var event = new TransportEvent();
    event.stage = stage;
    event.traceId = match.group(1);
    event.spanId = match.group(2);
    return event;
  }
}
