package eg.masroof.application.expense;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("REQ-EXP-001")
class CursorTest {

  private static final UUID ID = UUID.fromString("7a1a4a7e-5d3e-4a0e-9d0a-1b2c3d4e5f60");

  @Test
  void instantCursorRoundTrips() {
    Instant at = Instant.parse("2026-10-22T11:05:00.123456Z");
    Cursor c = new Cursor(ExpenseSort.OCCURRED_AT_DESC, 0, at, ID);
    Cursor back = Cursor.decode(c.encode(), ExpenseSort.OCCURRED_AT_DESC).orElseThrow();
    assertThat(back.instant()).isEqualTo(at);
    assertThat(back.id()).isEqualTo(ID);
  }

  @Test
  void amountCursorRoundTrips() {
    Cursor c = new Cursor(ExpenseSort.AMOUNT_ASC, 12_500, Instant.EPOCH, ID);
    Cursor back = Cursor.decode(c.encode(), ExpenseSort.AMOUNT_ASC).orElseThrow();
    assertThat(back.amountMinor()).isEqualTo(12_500);
    assertThat(back.id()).isEqualTo(ID);
  }

  @Test
  void cursorForAnotherSortIsRejected() {
    String text = new Cursor(ExpenseSort.AMOUNT_ASC, 1, Instant.EPOCH, ID).encode();
    assertThat(Cursor.decode(text, ExpenseSort.AMOUNT_DESC)).isEmpty();
    assertThat(Cursor.decode(text, ExpenseSort.OCCURRED_AT_ASC)).isEmpty();
  }

  @Test
  void garbageIsRejected() {
    assertThat(Cursor.decode("not-a-cursor!!", ExpenseSort.OCCURRED_AT_DESC)).isEmpty();
    assertThat(Cursor.decode("", ExpenseSort.OCCURRED_AT_DESC)).isEmpty();
    String badId =
        Base64.getUrlEncoder()
            .encodeToString(
                "c1|-occurredAt|2026-10-22T11:05:00Z|nope".getBytes(StandardCharsets.UTF_8));
    assertThat(Cursor.decode(badId, ExpenseSort.OCCURRED_AT_DESC)).isEmpty();
    String badVersion =
        Base64.getUrlEncoder()
            .encodeToString(
                ("c9|-occurredAt|2026-10-22T11:05:00Z|" + ID).getBytes(StandardCharsets.UTF_8));
    assertThat(Cursor.decode(badVersion, ExpenseSort.OCCURRED_AT_DESC)).isEmpty();
  }

  @Test
  void sortParsingCoversTheContractEnum() {
    for (String key :
        new String[] {
          "occurredAt", "-occurredAt", "amount", "-amount", "createdAt", "-createdAt"
        }) {
      assertThat(ExpenseSort.parse(key)).isPresent();
      assertThat(ExpenseSort.parse(key).orElseThrow().key()).isEqualTo(key);
    }
    assertThat(ExpenseSort.parse("id; drop table expense")).isEmpty();
    assertThat(ExpenseSort.AMOUNT_DESC.descending()).isTrue();
    assertThat(ExpenseSort.AMOUNT_ASC.isAmount()).isTrue();
    assertThat(ExpenseSort.OCCURRED_AT_ASC.isAmount()).isFalse();
  }
}
