package utils;

import com.sun.net.httpserver.HttpServer;
import io.gatling.app.Gatling;
import io.gatling.http.client.impl.PrematureCloseException;
import io.gatling.javaapi.core.Session;
import io.gatling.javaapi.core.Simulation;
import io.netty.handler.ssl.SslHandshakeTimeoutException;
import org.slf4j.LoggerFactory;
import scenarios.ActivityAuditReportScenario;
import scenarios.AppRegScenario;
import scenarios.BulkApplicationUploadScenario;

import javax.net.ssl.SSLException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.exitBlockOnFail;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;

/** Local-only proof: native TLS/partial-response failures, four-attempt cap and GET coverage. */
public final class GetRetrySelfCheck {
  private static final AtomicInteger tlsAccepts = new AtomicInteger();
  private static final ConcurrentHashMap<String, AtomicInteger> hits = new ConcurrentHashMap<>();
  private static String plainUrl;
  private static String stalledUrl;
  private static boolean checksCompleted;
  private static String firstFailure;

  private GetRetrySelfCheck() {}

  public static void main(String[] args) throws Exception {
    if (args.length == 1 && args[0].equals("--help")) {
      System.out.println("Usage: getRetrySelfCheck (local only; about 50 seconds)");
      return;
    }
    if (args.length != 0) throw new IllegalArgumentException("Use --help");
    checkCoverage();
    System.clearProperty("appRegGatewayGetRetries");
    for (String value : List.of("-1", "3601", "NaN", "Infinity", "invalid")) {
      System.setProperty("appRegGatewayGetRetryDelaySeconds", value);
      try {
        GatewayGetRetry.retryDelaySeconds();
        throw new IllegalStateException("Invalid delay accepted");
      } catch (IllegalArgumentException expected) {
        // Invalid or non-finite delays must fail before any scenario starts.
      }
    }
    System.clearProperty("appRegGatewayGetRetryDelaySeconds");
    require(GatewayGetRetry.retries() == 3, "Default must be three retries");
    for (String value : List.of("-1", "4", "invalid")) {
      System.setProperty("appRegGatewayGetRetries", value);
      try {
        GatewayGetRetry.retries();
        throw new IllegalStateException("Invalid retry limit accepted: " + value);
      } catch (IllegalArgumentException expected) {
        // Java and Jenkins must both reject more than three retries.
      }
    }
    System.clearProperty("appRegGatewayGetRetries");
    var loopback = InetAddress.getLoopbackAddress();
    try (var stalled = new ServerSocket(0, 50, loopback)) {
      var server = HttpServer.create(new InetSocketAddress(loopback, 0), 0);
      server.createContext("/", exchange -> {
        // Avoid Gatling's built-in stale-pool reconnect so each injected fault is one recorded attempt.
        exchange.getResponseHeaders().add("Connection", "close");
        var path = exchange.getRequestURI().getPath();
        int attempt = hits.computeIfAbsent(path, ignored -> new AtomicInteger()).incrementAndGet();
        boolean close = path.startsWith("/close")
            && (!path.startsWith("/close-recover") || attempt <= 3);
        close |= path.endsWith("/download") && attempt == 1;
        close |= path.equals("/application-lists/local-list/entries") && attempt == 1;
        if (close) {
          // Headers arrive, but the body is truncated: no Gatling pre-header reconnect ambiguity.
          exchange.sendResponseHeaders(200, 100);
          exchange.getResponseBody().write('x');
          exchange.getResponseBody().flush();
          exchange.close();
          return;
        }
        int status = switch (path) {
          case "/gateway", "/gateway-exhaust" -> 502;
          case "/missing" -> 404;
          case "/unauthorized" -> 401;
          case "/forbidden" -> 403;
          case "/server-error" -> 500;
          case "/reports/activity-audit/jobs", "/application-lists/local-list/entries/bulk-import",
              "/application-lists/failed-list/entries/bulk-import" -> 202;
          default -> 200;
        };
        String body = "{}";
        if (path.equals("/") || path.equals("/applications-list")) {
          body = "<title>HMCTS Applications Register - Home - GOV.UK</title>"
              + "<script src=\"main-proof.js\"></script>";
        } else if (path.equals("/bad-check")) {
          body = "{\"authenticated\":false}";
        } else if (path.equals("/reports")) {
          exchange.getResponseHeaders().add("Set-Cookie", "XSRF-TOKEN=local-proof; Path=/");
        } else if (path.equals("/reports/activity-audit/jobs")) {
          require(exchange.getRequestMethod().equals("POST"), "Wrong report creation method");
          body = "{\"id\":\"local-job\"}";
        } else if (path.equals("/jobs/local-job")) {
          status = attempt == 1 ? 504 : 200;
          body = "{\"status\":\"" + (attempt == 2 ? "PROCESSING" : "COMPLETED") + "\"}";
        } else if (path.equals("/application-lists/local-list/entries/bulk-import")
            || path.equals("/application-lists/failed-list/entries/bulk-import")) {
          require(exchange.getRequestMethod().equals("POST"), "Wrong upload method");
          exchange.getRequestBody().transferTo(OutputStream.nullOutputStream());
          body = "{\"id\":\"" + (path.contains("failed-list") ? "failed-upload" : "upload-job") + "\"}";
        } else if (path.equals("/jobs/upload-job")) {
          status = attempt == 1 ? 502 : 200;
          body = "{\"status\":\"" + switch (attempt) {
            case 2 -> "RECEIVED";
            case 3 -> "PROCESSING";
            default -> "COMPLETED";
          } + "\"}";
        } else if (path.equals("/jobs/failed-upload")) {
          body = "{\"status\":\"FAILED\"}";
        }
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type",
            path.equals("/") || path.equals("/applications-list") ? "text/html" : "application/json");
        if (path.equals("/cached-page") || path.equals("/main-proof.js")) {
          exchange.getResponseHeaders().add("Cache-Control", "public, max-age=3600");
        }
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
        exchange.close();
      });
      server.start();
      // Never complete TLS. Certificates/trust verification are not disabled.
      List<Socket> sockets = new ArrayList<>();
      var acceptor = Thread.ofPlatform().daemon().start(() -> {
        try {
          while (!stalled.isClosed()) {
            var socket = stalled.accept();
            synchronized (sockets) { sockets.add(socket); }
            tlsAccepts.incrementAndGet();
          }
        } catch (Exception e) {
          if (!stalled.isClosed()) throw new IllegalStateException(e);
        }
      });
      plainUrl = "http://localhost:" + server.getAddress().getPort();
      stalledUrl = "https://localhost:" + stalled.getLocalPort();
      try {
        Gatling.main(new String[] {"-s", Proof.class.getName(),
            "-rf", "build/reports/get-retry-self-check"});
      } finally {
        stalled.close();
        acceptor.join(1000);
        synchronized (sockets) { for (var socket : sockets) socket.close(); }
        server.stop(0);
      }
    }
  }

  private static int hits(String path) {
    var count = hits.get(path);
    return count == null ? 0 : count.get();
  }

  private static void require(boolean condition, String description) {
    if (!condition) {
      if (firstFailure == null) firstFailure = description;
      throw new IllegalStateException(description);
    }
  }

  private static void checkCoverage() throws Exception {
    // This guards our direct http("name").get(...) style, not arbitrary Java ASTs.
    // Introduce an AST-based check if requests start being assembled through variables.
    var get = Pattern.compile("http\\(\"([^\"\\n]+)\"\\)\\s*\\.get\\(");
    var wrapper = Pattern.compile("retryingGet(?:Before)?\\(\\s*\"[^\"\\n]+\"\\s*,\\s*$");
    var excluded = Set.of("AppReg SSO login redirect", "Entra authorize", "Entra authorize reload",
        "Entra KMSI Redirect 1", "Entra KMSI Redirect 2");
    int covered = 0;
    int auth = 0;
    try (var sources = Files.walk(Path.of("src/gatling/java"))) {
      for (var path : sources.filter(p -> p.toString().endsWith(".java")
          && !p.getFileName().toString().contains("SelfCheck")).toList()) {
        var text = Files.readString(path);
        var requests = get.matcher(text);
        while (requests.find()) {
          if (wrapper.matcher(text.substring(0, requests.start())).find()) {
            covered++;
          } else {
            require(path.getFileName().toString().equals("SsoAuthentication.java")
                && excluded.contains(requests.group(1)), "Unprotected GET: " + path + " " + requests.group(1));
            auth++;
          }
        }
      }
    }
    require(covered == 47 && auth == 5, "GET inventory drifted: " + covered + "/" + auth);
    for (String file : List.of("Jenkinsfile_nightly", "Jenkinsfile_CNP")) {
      var text = Files.readString(Path.of(file));
      require(text.contains("env.WORKLOAD_GATEWAY_GET_RETRIES ?: '3'")
          && text.contains("workloadGatewayGetRetries.toInteger() > 3"), "Jenkins retry limit drifted");
    }
    System.out.println("GET coverage check passed: 47 protected reads; 5 SSO exchanges excluded");
  }

  private static Session typedChecks(Session session) {
    var request = http("typed probe").get(plainUrl);
    var logger = LoggerFactory.getLogger("io.gatling.http.engine.GatlingHttpListener$");
    var message = "Request 'typed probe' failed for user " + session.userId();
    for (Throwable failure : List.of(
        new SSLException("SslHandshakeTimeoutException: handshake timed out"),
        new SocketTimeoutException("PrematureCloseException"), new ClosedChannelException(),
        new IllegalStateException("timeout"),
        new RuntimeException() { @Override public Throwable getCause() { return this; } })) {
      var watched = GetTransportFailures.begin(session, request);
      logger.debug(message, failure);
      require(GetTransportFailures.finish(watched) == null, "Non-matching failure was accepted");
    }
    for (Throwable failure : List.of(new SslHandshakeTimeoutException("timeout"),
        new RuntimeException(new SslHandshakeTimeoutException("timeout")),
        PrematureCloseException.INSTANCE, new RuntimeException(PrematureCloseException.INSTANCE))) {
      var watched = GetTransportFailures.begin(session, request);
      logger.debug(message + "0", failure);
      require(GetTransportFailures.finish(watched) == null, "Another actor's failure was accepted");
      watched = GetTransportFailures.begin(session, request);
      logger.debug(message, failure);
      require(GetTransportFailures.finish(watched) != null, "Typed cause was missed");
      require(GetTransportFailures.finish(watched) == null, "Consumed failure state leaked");
    }
    try {
      GetTransportFailures.begin(session, http("POST must never retry").post(plainUrl));
      throw new IllegalStateException("POST was accepted");
    } catch (IllegalArgumentException expected) {
      // Also exercised through the actual helper below, proving no POST reaches the server.
    }
    return session;
  }

  public static final class Proof extends Simulation {
    public Proof() {
      GatewayGetRetry.resetCounts();
      var proof = scenario("Safe GET retry proof")
          .exec(GetRetrySelfCheck::typedChecks)
          .exec(http("cache warmup").get(plainUrl + "/cached-page"))
          .exec(GatewayGetRetry.retryingGet("cached GET", http("cached GET")
              .get(plainUrl + "/cached-page")))
          .exec(session -> {
            require(!session.isFailed() && hits("/cached-page") == 1,
                "A successful cache hit failed the GET helper");
            return session;
          })
          .exitHereIfFailed()
          .exec(GatewayGetRetry.retryingGet("close recovery", http("close recovery")
              .get(plainUrl + "/close-recover-first")))
          .exec(session -> {
            require(!session.isFailed() && hits("/close-recover-first") == 4, "Third retry did not recover");
            return session;
          })
          .exec(GatewayGetRetry.retryingGet("fresh budget", http("fresh budget")
              .get(plainUrl + "/close-recover-second")))
          .exec(session -> {
            require(!session.isFailed() && hits("/close-recover-second") == 4, "Budget was not per GET");
            return session;
          })
          .exec(GatewayGetRetry.retryingGet("TLS recovery", http("TLS recovery")
              .get(session -> tlsAccepts.get() == 0 ? stalledUrl : plainUrl)))
          .exec(session -> {
            require(!session.isFailed(), "TLS recovery left the session failed");
            return session;
          })
          .exec(exitBlockOnFail().on(GatewayGetRetry.retryingGet("mixed recovery", http("mixed recovery")
              .get(session -> switch (session.getInt("gatewayGetAttempt")) {
                case 0 -> plainUrl + "/gateway";
                case 1 -> stalledUrl;
                case 2 -> plainUrl + "/close-mixed";
                default -> plainUrl;
              }))))
          .exec(session -> {
            require(!session.isFailed() && hits("/gateway") == 1
                && tlsAccepts.get() == 2 && hits("/close-mixed") == 1, "Mixed failure recovery failed");
            return session;
          })
          .exec(GatewayGetRetry.retryingGet("exhaust", http("exhaust").get(plainUrl + "/close-exhaust")))
          .exec(session -> {
            require(session.isFailed() && hits("/close-exhaust") == 4, "More than three retries occurred");
            return session.markAsSucceeded();
          });
      proof = proof.exec(exitBlockOnFail().on(
          GatewayGetRetry.retryingGet("guard exhaustion", http("guard exhaustion")
              .get(plainUrl + "/close-guard")),
          exec(http("must not mutate").post(plainUrl + "/must-not-mutate"))))
          .exec(session -> {
            require(session.isFailed() && hits("/close-guard") == 4 && hits("/must-not-mutate") == 0,
                "Exhaustion did not preserve the outer failure guard");
            return session.markAsSucceeded();
          });
      proof = proof.exec(GatewayGetRetry.retryingGet("gateway exhaustion", http("gateway exhaustion")
          .get(plainUrl + "/gateway-exhaust"))).exec(session -> {
            require(session.isFailed() && hits("/gateway-exhaust") == 4, "Gateway retry cap failed");
            return session.markAsSucceeded();
          });
      for (var path : List.of("/missing", "/unauthorized", "/forbidden", "/server-error", "/bad-check")) {
        proof = proof.exec(GatewayGetRetry.retryingGet(path, http(path).get(plainUrl + path),
            jsonPath("$.authenticated").is("true"))).exec(session -> {
              require(session.isFailed() && hits(path) == 1, "Non-transient error/check was replayed");
              return session.markAsSucceeded();
            });
      }
      proof = proof
          .exec(session -> session.markAsFailed())
          .exec(GatewayGetRetry.retryingGet("prior failure", http("prior failure").get(plainUrl + "/close-prior")))
          .exec(session -> {
            require(session.isFailed() && hits("/close-prior") == 1, "An unrelated failure was cleared");
            System.setProperty("appRegGatewayGetRetries", "0");
            return session.markAsSucceeded();
          })
          .exec(GatewayGetRetry.retryingGet("disabled", http("disabled").get(plainUrl + "/close-disabled")))
          .exec(session -> {
            require(session.isFailed() && hits("/close-disabled") == 1, "Zero retry setting ignored");
            System.clearProperty("appRegGatewayGetRetries");
            return session.markAsSucceeded().set("testDeadline", System.nanoTime() - 1);
          })
          .exec(GatewayGetRetry.retryingGetBefore("expired", http("expired").get(plainUrl + "/expired"),
              "testDeadline"))
          .exec(session -> {
            require(session.isFailed() && hits("/expired") == 0, "Expired poll sent a request");
            System.setProperty("appRegGatewayGetRetryDelaySeconds", "120");
            return session.markAsSucceeded().set("testDeadline", System.nanoTime() + 60_000_000_000L);
          })
          .exec(GatewayGetRetry.retryingGetBefore("deadline", http("deadline").get(plainUrl + "/close-deadline"),
              "testDeadline"))
          .exec(session -> {
            require(session.isFailed() && hits("/close-deadline") == 1, "Retry exceeded polling budget");
            System.clearProperty("appRegGatewayGetRetryDelaySeconds");
            return session.markAsSucceeded();
          })
          .exec(GatewayGetRetry.retryingGet("guarded POST", http("guarded POST").post(plainUrl + "/post")))
          .exec(session -> {
            require(session.isFailed() && hits("/post") == 0, "POST reached the network");
            return session.markAsSucceeded();
          })
          .exec(http("page warmup").get(plainUrl))
          .exec(AppRegScenario.applicationsList(true))
          .exec(ActivityAuditReportScenario.generateActivityAuditReport())
          .exec(session -> {
            require(!session.isFailed(), "Local page/report proof failed");
            require(hits("/reports/activity-audit/jobs") == 1, "Report creation POST was replayed");
            require(hits("/jobs/local-job") == 3 && hits("/reports/jobs/local-job/download") == 2,
                "Report poll/download did not recover on the same job");
            return session;
          })
          .exec(session -> session.set("applicationListId", "local-list"))
          .exec(BulkApplicationUploadScenario.bulkUploadApplications())
          .exec(session -> {
            require(!session.isFailed(), "Local upload polling proof failed");
            require(hits("/application-lists/local-list/entries/bulk-import") == 1
                && hits("/jobs/upload-job") == 4, "Upload creation/poll recovery was incorrect");
            return session.set("applicationListId", "failed-list");
          })
          .exec(BulkApplicationUploadScenario.bulkUploadApplications())
          .exec(session -> {
            require(session.isFailed() && hits("/jobs/failed-upload") == 1
                && hits("/application-lists/failed-list/entries/bulk-import") == 1,
                "A terminal job failure was hidden or replayed");
            require(BulkApplicationUploadScenario.pollTimeoutSeconds() > 0, "Invalid polling configuration");
            checksCompleted = true;
            return session;
          });
      setUp(proof.injectOpen(atOnceUsers(1))).protocols(
          http.baseUrl(plainUrl).inferHtmlResources().silentResources());
    }

    @Override
    public void after() {
      require(firstFailure == null, "Earlier assertion failed: " + firstFailure);
      require(checksCompleted, "Proof did not complete");
      require(GatewayGetRetry.transportFailures() == 22, "Transport incidents were not retained");
      require(GatewayGetRetry.transportRecovered() == 6, "Transport recovery count incorrect");
      require(GatewayGetRetry.transportExhausted() == 5, "Transport exhaustion count incorrect");
      require(GatewayGetRetry.transientFailures() == 7, "Gateway failure count incorrect");
      require(GatewayGetRetry.recoveredOperations() == 3, "Mixed gateway recovery was not counted");
      require(GatewayGetRetry.exhaustedFailures() == 1, "Gateway exhaustion was not counted");
      GatewayGetRetry.resetCounts();
      require(GatewayGetRetry.transportFailures() == 0 && GatewayGetRetry.transportRecovered() == 0
          && GatewayGetRetry.transportExhausted() == 0, "Counters did not reset");
      System.out.println("Safe GET retry self-check passed (native transport KO records intentionally retained)");
    }
  }
}
