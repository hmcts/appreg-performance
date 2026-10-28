package utils;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.AppenderBase;
import io.gatling.http.request.builder.HttpRequestBuilder;
import io.gatling.javaapi.core.Session;
import io.gatling.javaapi.http.HttpRequestActionBuilder;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.ssl.SslHandshakeTimeoutException;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Captures typed failures only while a primary GET is being retried. */
public final class TlsHandshakeTimeouts extends AppenderBase<ILoggingEvent> {
  static final String KEY = "gatewayGetTlsFailureKey";
  private static final String LOGGER = "io.gatling.http.engine.GatlingHttpListener$";
  private static final ConcurrentHashMap<String, AtomicBoolean> active = new ConcurrentHashMap<>();

  static {
    var logger = (Logger) LoggerFactory.getLogger(LOGGER);
    var appender = new TlsHandshakeTimeouts();
    appender.setContext(logger.getLoggerContext());
    appender.start();
    logger.addAppender(appender);
    logger.setLevel(Level.DEBUG);
    // Gatling's crash logger can dump requests/credentials; never forward its raw events.
    logger.setAdditive(false);
  }

  static Session begin(Session session, HttpRequestActionBuilder request) {
    var attributes = ((HttpRequestBuilder) request.asScala()).commonAttributes();
    var method = attributes.method().apply(session.asScala()).toOption();
    if (method.isEmpty() || !HttpMethod.GET.equals(method.get())) {
      throw new IllegalArgumentException("GatewayGetRetry accepts only GET requests");
    }
    var name = attributes.requestName().apply(session.asScala()).toOption().get();
    var key = "Request '" + name + "' failed for user " + session.userId();
    active.put(key, new AtomicBoolean());
    return session.set(KEY, key);
  }

  static boolean finish(Session session) {
    var flag = active.remove(session.getString(KEY));
    return flag != null && flag.get();
  }

  @Override
  protected void append(ILoggingEvent event) {
    // Gatling 3.15.1 logs the original Throwable before delivering HttpFailure. This
    // version-specific template is checked by the local TLS proof; upgrade to a
    // public failure hook if Gatling adds one. No formatted errors are matched.
    if (!LOGGER.equals(event.getLoggerName())
        || !(event.getThrowableProxy() instanceof ThrowableProxy proxy)) return;
    var flag = active.get(event.getMessage());
    if (flag == null) return;
    var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
    for (Throwable cause = proxy.getThrowable(); cause != null && seen.add(cause);
         cause = cause.getCause()) {
      if (cause instanceof SslHandshakeTimeoutException) {
        flag.set(true);
        return;
      }
    }
  }
}
