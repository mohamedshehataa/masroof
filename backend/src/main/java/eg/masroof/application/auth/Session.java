package eg.masroof.application.auth;

/** What a successful login or refresh hands to the client. */
public record Session(
    String accessToken, String refreshToken, long expiresInSeconds, Account account) {

  /** Never prints tokens. */
  @Override
  public String toString() {
    return "Session[account=" + account + "]";
  }
}
