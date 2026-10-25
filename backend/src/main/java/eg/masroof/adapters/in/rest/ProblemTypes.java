package eg.masroof.adapters.in.rest;

import java.net.URI;

/** Stable problem {@code type} URIs clients can switch on; titles stay human text. */
final class ProblemTypes {

  static final String BASE = "https://masroof.app/problems/";

  private ProblemTypes() {}

  static URI of(String code) {
    return URI.create(BASE + code);
  }

  /** Generic type for a status, or {@code about:blank} when none is defined. */
  static URI forStatus(int status) {
    return switch (status) {
      case 400, 422 -> of("validation");
      case 401 -> of("unauthorized");
      case 404 -> of("not-found");
      case 409 -> of("conflict");
      case 429 -> of("rate-limited");
      default -> URI.create("about:blank");
    };
  }
}
