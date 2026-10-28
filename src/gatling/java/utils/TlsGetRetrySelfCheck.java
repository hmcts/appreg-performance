package utils;

import com.sun.net.httpserver.HttpServer;
import io.gatling.app.Gatling;
import io.gatling.javaapi.core.Session;
import io.gatling.javaapi.core.Simulation;
import io.netty.handler.ssl.SslHandshakeTimeoutException;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;

/** Local-only typed-failure and real Gatling TLS/GET recovery proof. */
public final class TlsGetRetrySelfCheck {
  private static final AtomicInteger accepts = new AtomicInteger();
  private static final AtomicInteger httpHits = new AtomicInteger();
  private static final AtomicInteger gatewayHits = new AtomicInteger();
  private static String plainUrl;
  private static String stalledUrl;
  private static boolean checksCompleted;

  private TlsGetRetrySelfCheck() {}

  public static void main(String[] args) throws Exception {
    if (args.length == 1 && args[0].equals("--help")) {
      System.out.println("Usage: tlsGetRetrySelfCheck (local only; about 55 seconds)");
      return;
    }
    if (args.length != 0) throw new IllegalArgumentException("Use --help");
    System.setProperty("appRegGatewayGetRetries", "2"); // TLS must still stop after one retry.
    var loopback = InetAddress.getLoopbackAddress();
    try (var stalled = new ServerSocket(0, 50, loopback)) {
      var server = HttpServer.create(new InetSocketAddress(loopback, 0), 0);
      server.createContext("/", exchange -> {
        httpHits.incrementAndGet();
        int status = exchange.getRequestURI().getPath().equals("/missing") ? 404 : 200;
        if (exchange.getRequestURI().getPath().equals("/gateway")
            && gatewayHits.incrementAndGet() == 1) status = 502;
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
      });
      server.start();
      // Never complete TLS. Certificates/trust validation are not disabled.
      List<Socket> sockets = new ArrayList<>();
      var acceptor = Thread.ofPlatform().daemon().start(() -> {
        try {
          while (!stalled.isClosed()) {
            var socket = stalled.accept();
            synchronized (sockets) { sockets.add(socket); }
            accepts.incrementAndGet();
          }
        } catch (Exception e) {
          if (!stalled.isClosed()) throw new IllegalStateException(e);
        }
      });
      plainUrl = "http://localhost:" + server.getAddress().getPort();
      stalledUrl = "https://localhost:" + stalled.getLocalPort();
      try {
        Gatling.main(new String[] {"-s", Proof.class.getName(),
            "-rf", "build/reports/tls-get-retry-self-check"});
      } finally {
        stalled.close();
        acceptor.join(1000);
        synchronized (sockets) { for (var socket : sockets) socket.close(); }
        server.stop(0);
      }
    }
  }

  private static void require(boolean condition, String description) {
    if (!condition) throw new IllegalStateException(description);
  }

  private static Session typedChecks(Session session) {
    var request = http("typed probe").get(plainUrl);
    var logger = LoggerFactory.getLogger("io.gatling.http.engine.GatlingHttpListener$");
    var message = "Request 'typed probe' failed for user " + session.userId();
    for (Throwable failure : List.of(
        new SSLException("SslHandshakeTimeoutException: handshake timed out"),
        new SocketTimeoutException(), new ClosedChannelException(),
        new IllegalStateException("timeout"),
        new RuntimeException() { @Override public Throwable getCause() { return this; } })) {
      var watched = TlsHandshakeTimeouts.begin(session, request);
      logger.debug(message, failure);
      require(!TlsHandshakeTimeouts.finish(watched), "Non-matching failure was accepted");
    }
    for (Throwable failure : List.of(new SslHandshakeTimeoutException("timeout"),
        new RuntimeException(new SslHandshakeTimeoutException("timeout")))) {
      var watched = TlsHandshakeTimeouts.begin(session, request);
      logger.debug(message + "0", failure);
      require(!TlsHandshakeTimeouts.finish(watched), "Another actor's failure was accepted");
      watched = TlsHandshakeTimeouts.begin(session, request);
      logger.debug(message, failure);
      require(TlsHandshakeTimeouts.finish(watched), "Typed cause was missed");
      require(!TlsHandshakeTimeouts.finish(watched), "Failure state leaked after consumption");
    }
    try {
      TlsHandshakeTimeouts.begin(session, http("POST must never retry").post(plainUrl));
      throw new IllegalStateException("POST was accepted");
    } catch (IllegalArgumentException expected) {
      // Guard checked before any network request.
    }
    return session;
  }

  public static final class Proof extends Simulation {
    public Proof() {
      GatewayGetRetry.resetCounts();
      setUp(scenario("TLS retry proof")
          .exec(TlsGetRetrySelfCheck::typedChecks)
          .exec(GatewayGetRetry.retryingGet("recover", http("recover")
              // The first real HTTPS attempt stalls; only the local retry uses HTTP.
              .get(session -> accepts.get() == 0 ? stalledUrl : plainUrl)))
          .exec(session -> {
            require(!session.isFailed(), "TLS recovery left the session failed");
            require(httpHits.get() == 1, "Successful GET was replayed");
            return session;
          })
          .exec(GatewayGetRetry.retryingGet("exhaust", http("exhaust").get(stalledUrl)))
          .exec(session -> {
            require(session.isFailed(), "Repeated TLS failure was hidden");
            require(accepts.get() == 3, "TLS retried more than once");
            return session.markAsSucceeded();
          })
          .exec(GatewayGetRetry.retryingGet("ordinary 404", http("ordinary 404").get(plainUrl + "/missing")))
          .exec(session -> {
            require(session.isFailed(), "Ordinary HTTP failure was hidden");
            require(httpHits.get() == 2, "Ordinary HTTP failure was retried");
            return session.markAsSucceeded();
          })
          .exec(GatewayGetRetry.retryingGet("existing gateway retry", http("existing gateway retry")
              .get(plainUrl + "/gateway")))
          .exec(session -> {
            require(!session.isFailed() && gatewayHits.get() == 2, "Existing 502 retry regressed");
            return session.markAsFailed();
          })
          .exec(GatewayGetRetry.retryingGet("prior failure", http("prior failure").get(stalledUrl)))
          .exec(session -> {
            require(session.isFailed() && accepts.get() == 4, "An unrelated failure was cleared");
            System.setProperty("appRegGatewayGetRetries", "0");
            return session.markAsSucceeded();
          })
          .exec(GatewayGetRetry.retryingGet("disabled retry", http("disabled retry").get(stalledUrl)))
          .exec(session -> {
            require(session.isFailed() && accepts.get() == 5, "Zero-retry setting was ignored");
            return session.markAsSucceeded();
          })
          .exec(GatewayGetRetry.retryingGet("guarded POST", http("guarded POST").post(plainUrl)))
          .exec(session -> {
            require(session.isFailed() && httpHits.get() == 4, "POST reached the network");
            checksCompleted = true;
            return session;
          }).injectOpen(atOnceUsers(1))).protocols(http.baseUrl(plainUrl));
    }

    @Override
    public void after() {
      require(checksCompleted, "Proof did not complete");
      require(GatewayGetRetry.tlsFailures() == 5, "TLS incidents were not retained");
      require(GatewayGetRetry.tlsRecovered() == 1, "Recovery count incorrect");
      require(GatewayGetRetry.tlsExhausted() == 3, "Exhaustion count incorrect");
      require(GatewayGetRetry.transientFailures() == 1, "TLS counted as an HTTP gateway failure");
      require(GatewayGetRetry.recoveredOperations() == 1, "Gateway recovery counter regressed");
      GatewayGetRetry.resetCounts();
      require(GatewayGetRetry.tlsFailures() == 0 && GatewayGetRetry.tlsRecovered() == 0
          && GatewayGetRetry.tlsExhausted() == 0, "TLS counters did not reset");
      System.out.println("TLS GET retry self-check passed (native KO records intentionally retained)");
    }
  }
}
