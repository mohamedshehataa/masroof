package eg.masroof.application.expense;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/** Opaque keyset cursor: the sort order plus the last row's sort value and id. */
public record Cursor(ExpenseSort sort, long amountMinor, Instant instant, UUID id) {

  private static final String VERSION = "c1";

  /** Encodes to URL-safe base64 text. */
  public String encode() {
    String value = sort.isAmount() ? Long.toString(amountMinor) : instant.toString();
    String raw = String.join("|", VERSION, sort.key(), value, id.toString());
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  /** Decodes a cursor for the expected sort order; empty when malformed or for another sort. */
  public static Optional<Cursor> decode(String text, ExpenseSort expected) {
    try {
      String raw = new String(Base64.getUrlDecoder().decode(text), StandardCharsets.UTF_8);
      String[] parts = raw.split("\\|", -1);
      if (parts.length != 4 || !VERSION.equals(parts[0]) || !expected.key().equals(parts[1])) {
        return Optional.empty();
      }
      UUID id = UUID.fromString(parts[3]);
      if (expected.isAmount()) {
        return Optional.of(new Cursor(expected, Long.parseLong(parts[2]), Instant.EPOCH, id));
      }
      return Optional.of(new Cursor(expected, 0, Instant.parse(parts[2]), id));
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }
}
