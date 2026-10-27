package utils;

import ch.qos.logback.classic.spi.LoggingEvent;
import io.gatling.core.session.Session$;
import io.gatling.http.client.RequestBuilder;
import io.gatling.http.client.HttpListener;
import io.gatling.http.client.Request;
import io.gatling.http.client.impl.request.WritableRequestBuilder;
import io.gatling.http.client.uri.Uri;
import io.gatling.http.response.Response;
import io.gatling.javaapi.core.Session;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;

/** No network or credentials required: checks event capture and rejection of sensitive arguments. */
public final class ClientDiagnosticsSelfCheck {
  private ClientDiagnosticsSelfCheck() {}

  public static void main(String[] args) throws Exception {
    var traceparent = "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01";
    var headers = new DefaultHttpHeaders().set("traceparent", traceparent)
      .set("Cookie", "SECRET-cookie").set("Authorization", "Bearer SECRET-token");
    var request = new RequestBuilder("self-check", HttpMethod.GET,
      Uri.create(Environment.BASE_URL + "/?SECRET-query"), null)
      .setHeaders(headers).build();
    var httpRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/?SECRET-query", headers.copy());
    var response = new Response(request, 100, 200, HttpResponseStatus.OK, headers, null, null, false);
    var channel = new EmbeddedChannel();
    var originalProperty = System.getProperty("appRegClientDiagnostics");
    var directory = Files.createDirectories(Path.of("build/client-diagnostics-selfcheck"));
    var file = Files.createTempFile(directory, "transport-", ".jfr");
    var configuration = Configuration.create(Path.of("build/client-diagnostics/client.jfc"));
    for (var name : new String[] {"InitialEnvironmentVariable", "InitialSystemProperty", "JVMInformation",
        "SystemProcess", "ProcessStart", "InitialSecurityProperty", "SecurityPropertyModification",
        "JavaExceptionThrow", "JavaErrorThrow", "OldObjectSample"}) {
      if (!"false".equals(configuration.getSettings().get("jdk." + name + "#enabled"))) {
        throw new AssertionError("Unsafe JFR event enabled: " + name);
      }
    }
    try (var recording = new Recording(configuration)) {
      recording.enable("appreg.ClientTransport");
      recording.start();
      var appender = new ClientDiagnostics();
      appender.start();
      String[][] cases = {
        {"Opening new channel to remote={} from local={}", "tcp_connect_start"},
        {"Connected to remoteAddress={} from localAddress={}", "tcp_connected"},
        {"TLS handshake successful: peerHost={} peerPort={} protocol={} cipher suite={}", "tls_complete"},
        {"Retrieved HTTP/1 channel from pool for key {}", "pool_reuse"},
        {"Retrieved HTTP/2 channel from pool for key {}", "pool_reuse"},
        {"Retrieved channel from coalescing pool for domain {}", "pool_reuse"},
        {"No channel in the pool for key {}", "pool_miss"},
        {"Offering channel entry {} to pool", "pool_return"},
        {"{} WRITE: {}, [{}: {}], {}", "dns_query"},
        {"{} RECEIVED: UDP [{}: {}], {}", "dns_answer"},
        {"Write request {}", "request_write"},
        {"Read msg='{}'", "response_headers"}
      };
      for (var item : cases) {
        Object argument = item[1].equals("request_write") ? httpRequest
          : item[1].equals("response_headers") ? new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
          : channel;
        var loggingEvent = message(item[0], argument, new InetSocketAddress("127.0.0.1", 443));
        var event = ClientDiagnostics.transportEvent(loggingEvent);
        if (event == null || !item[1].equals(event.stage)) throw new AssertionError(item[0]);
        appender.doAppend(loggingEvent);
      }
      var writable = WritableRequestBuilder.buildRequest(request, ByteBufAllocator.DEFAULT, false, new HttpListener() {
        @Override public void onFinalClientRequest(Request value) {}
        @Override public void onHttpResponse(HttpResponseStatus status, HttpHeaders value) {}
        @Override public void onHttpResponseBodyChunk(ByteBuf chunk, boolean last) {}
        @Override public void onThrowable(Throwable cause) { throw new AssertionError(cause); }
      });
      var writeEvent = message("Write request {}", writable);
      var transport = ClientDiagnostics.transportEvent(writeEvent);
      if (transport == null || !transport.traceId.equals("0123456789abcdef0123456789abcdef")) {
        throw new AssertionError("Lost Gatling's writable-request trace");
      }
      appender.doAppend(writeEvent);
      var unsafe = new Object() {
        @Override public String toString() { throw new AssertionError("Formatted a raw HTTP argument"); }
      };
      for (var template : new String[] {"Unknown SECRET-message {}", "Read msg='{}'", "Write request {}"}) {
        var event = message(template, unsafe);
        if (ClientDiagnostics.transportEvent(event) != null) throw new AssertionError("Accepted unknown payload");
        appender.doAppend(event);
      }
      var noArgs = new LoggingEvent();
      noArgs.setMessage("Read msg='{}'");
      if (ClientDiagnostics.transportEvent(noArgs) != null) throw new AssertionError("Accepted missing arguments");
      httpRequest.headers().set("traceparent", "SECRET-invalid");
      if (ClientDiagnostics.transportEvent(message("Write request {}", httpRequest)) != null) {
        throw new AssertionError("Accepted invalid traceparent");
      }
      httpRequest.headers().remove("traceparent");
      if (ClientDiagnostics.transportEvent(message("Write request {}", httpRequest)) != null) {
        throw new AssertionError("Accepted untraced request");
      }
      System.setProperty("appRegClientDiagnostics", "false");
      ClientDiagnostics.requestBoundary("disabled", request);
      ClientDiagnostics.responseCompleted(response);
      System.setProperty("appRegClientDiagnostics", "true");
      ClientDiagnostics.requestBoundary("request_signed", request);
      ClientDiagnostics.requestBoundary("request_ready", request);
      var session = AppRegTraceContext.startOperation(
        new Session(Session$.MODULE$.apply("diagnostic-self-check", 1, channel.eventLoop())),
        "self-check", "other_operations", 1);
      AppRegTraceContext.signAppRegRequest(request, session);
      if (ClientDiagnostics.responseCompleted(response) != response) throw new AssertionError("Changed response");
      appender.stop();
      recording.stop();
      recording.dump(file);
      var events = RecordingFile.readAllEvents(file).stream()
        .filter(event -> event.getEventType().getName().equals("appreg.ClientTransport")).toList();
      if (events.size() != cases.length + 6) throw new AssertionError("Missing or unexpected events: " + events.size());
      for (var event : events) {
        if (event.toString().contains("SECRET")) throw new AssertionError("Sensitive value recorded");
      }
      var last = events.getLast();
      if (last.getLong("requestStartMillis") != 100 || last.getLong("responseEndMillis") != 200
          || last.getInt("status") != 200) throw new AssertionError("Lost response timings");
      System.out.println("Client diagnostics capture and secret-rejection self-check passed");
    } finally {
      channel.finishAndReleaseAll();
      Files.deleteIfExists(file);
      if (originalProperty == null) System.clearProperty("appRegClientDiagnostics");
      else System.setProperty("appRegClientDiagnostics", originalProperty);
    }
  }

  private static LoggingEvent message(String template, Object... args) {
    var event = new LoggingEvent();
    event.setMessage(template);
    event.setArgumentArray(args);
    return event;
  }
}
