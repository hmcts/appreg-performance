package utils;

import io.gatling.javaapi.core.ChainBuilder;

/** Shared authentication entry points for proofs and multi-user simulations. */
public final class AuthenticationStage {
  private AuthenticationStage() {}

  /** Authenticates a framework-proof user while retaining that user's session and cookie jar. */
  public static ChainBuilder authenticateFrameworkProof() {
    return SsoAuthentication.login();
  }

  /** Authenticates one virtual user while retaining that user's Gatling session and cookie jar. */
  public static ChainBuilder authenticate() {
    return SsoAuthentication.login();
  }
}
