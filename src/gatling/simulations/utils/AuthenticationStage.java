package utils;

import io.gatling.javaapi.core.ChainBuilder;

/** Shared authentication entry points for proofs and multi-user simulations. */
public final class AuthenticationStage {
  private AuthenticationStage() {}

  /** Framework proofs deliberately authenticate one user and run one focused journey. */
  public static ChainBuilder authenticateFrameworkProof() {
    return SsoAuthentication.login();
  }

  /** Authenticates one virtual user while retaining that user's Gatling session and cookie jar. */
  public static ChainBuilder authenticate() {
    return SsoAuthentication.login();
  }
}
