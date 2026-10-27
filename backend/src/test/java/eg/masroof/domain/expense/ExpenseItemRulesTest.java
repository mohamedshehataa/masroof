package eg.masroof.domain.expense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import eg.masroof.domain.expense.ExpenseItemRules.Draft;
import eg.masroof.domain.expense.ExpenseItemRules.InvalidItems;
import eg.masroof.domain.money.Money;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class ExpenseItemRulesTest {

  private static final long MAX = 10_000_000_000_000L;

  private static List<ExpenseItem> validate(List<Draft> drafts) {
    return ExpenseItemRules.validate(drafts, "EGP", MAX, UUID::randomUUID);
  }

  private static Draft draft(@Nullable String name, @Nullable String qty, @Nullable Money total) {
    return new Draft(name, qty, total, false, false);
  }

  private static List<String> fields(List<Draft> drafts) {
    try {
      validate(drafts);
      return List.of();
    } catch (InvalidItems e) {
      return e.violations().stream().map(ExpenseItemRules.Violation::field).toList();
    }
  }

  @Test
  @Tag("REQ-RCPT-031")
  @DisplayName("REQ-RCPT-031 names are trimmed, qty and total are optional, order is kept")
  void normalises() {
    List<ExpenseItem> items =
        validate(
            List.of(
                draft("  Bread ", null, null),
                draft("Milk", "2", Money.of(0, "EGP")),
                draft("Cheese", "1.5", Money.of(12_550, "EGP"))));
    assertThat(items).extracting(ExpenseItem::name).containsExactly("Bread", "Milk", "Cheese");
    assertThat(items.get(0).qty()).isNull();
    assertThat(items.get(0).total()).isNull();
    assertThat(items.get(1).qty()).isEqualByComparingTo("2");
    assertThat(java.util.Objects.requireNonNull(items.get(1).qty()).scale()).isEqualTo(3);
    assertThat(items.get(2).total()).isEqualTo(Money.of(12_550, "EGP"));
    assertThat(validate(List.of())).isEmpty();
  }

  @Test
  @Tag("REQ-RCPT-031")
  @DisplayName("REQ-RCPT-031 every violation names its field path")
  void rejects() {
    assertThat(
            fields(
                List.of(draft("a", null, null), draft("b", null, null), draft("  ", null, null))))
        .containsExactly("items[2].name");
    assertThat(fields(List.of(draft(null, null, null)))).containsExactly("items[0].name");
    assertThat(fields(List.of(draft("a\u0000b", null, null)))).containsExactly("items[0].name");
    assertThat(fields(List.of(draft("x".repeat(201), null, null))))
        .containsExactly("items[0].name");
    assertThat(validate(List.of(draft("x".repeat(200), null, null)))).hasSize(1);
    for (String qty : new String[] {"0", "0.000", "-1", "1.0001", "1000000", "abc", "1e3", ""}) {
      assertThat(fields(List.of(draft("a", qty, null)))).as(qty).containsExactly("items[0].qty");
    }
    assertThat(validate(List.of(draft("a", "999999", null)))).hasSize(1);
    assertThat(fields(List.of(draft("a", null, Money.of(-1, "EGP")))))
        .containsExactly("items[0].total.amountMinor");
    assertThat(fields(List.of(draft("a", null, Money.of(MAX + 1, "EGP")))))
        .containsExactly("items[0].total.amountMinor");
    assertThat(fields(List.of(draft("a", null, Money.of(5, "USD")))))
        .containsExactly("items[0].total.currency");
    assertThat(fields(List.of(new Draft("a", null, null, true, true))))
        .containsExactly("items[0].unitPrice", "items[0].categoryId");
    List<Draft> many = new ArrayList<>();
    for (int i = 0; i < 101; i++) {
      many.add(draft("i" + i, null, null));
    }
    assertThat(fields(many)).containsExactly("items");
    assertThatThrownBy(() -> validate(many)).isInstanceOf(InvalidItems.class);
  }

  @Test
  @Tag("REQ-RCPT-032")
  @DisplayName("REQ-RCPT-032 random valid item lists round-trip unchanged and stay integral")
  void randomValidListsRoundTrip() {
    Random random = new Random(168);
    for (int run = 0; run < 2000; run++) {
      int n = random.nextInt(101);
      List<Draft> drafts = new ArrayList<>();
      for (int i = 0; i < n; i++) {
        String name = randomName(random);
        String qty = null;
        BigDecimal expectedQty = null;
        if (random.nextBoolean()) {
          long thousandths = 1 + (long) random.nextInt(999_999_999);
          expectedQty = BigDecimal.valueOf(thousandths, 3);
          qty = expectedQty.toPlainString();
        }
        Money total = random.nextBoolean() ? Money.of(randomMinor(random), "EGP") : null;
        drafts.add(new Draft(" " + name + "\t", qty, total, false, false));
      }
      List<ExpenseItem> items = validate(drafts);
      assertThat(items).hasSize(n);
      for (int i = 0; i < n; i++) {
        Draft d = drafts.get(i);
        ExpenseItem item = items.get(i);
        assertThat(item.name()).isEqualTo(java.util.Objects.requireNonNull(d.name()).strip());
        assertThat(item.total()).isEqualTo(d.total());
        if (d.qty() == null) {
          assertThat(item.qty()).isNull();
        } else {
          assertThat(item.qty()).isEqualByComparingTo(new BigDecimal(d.qty()));
          assertThat(java.util.Objects.requireNonNull(item.qty()).scale()).isEqualTo(3);
        }
      }
      assertThat(items.stream().map(ExpenseItem::id).distinct().count()).isEqualTo(n);
    }
  }

  private static String randomName(Random random) {
    String alphabet = "abcXYZ 019-_ءآأبتثجحخدذرزسشصضطظعغفقكلمنهوي😀";
    int cps = 1 + random.nextInt(60);
    StringBuilder sb = new StringBuilder("n"); // never starts or ends with whitespace
    int[] points = alphabet.codePoints().toArray();
    for (int i = 0; i < cps; i++) {
      sb.appendCodePoint(points[random.nextInt(points.length)]);
    }
    return sb.append('z').toString();
  }

  private static long randomMinor(Random random) {
    return switch (random.nextInt(4)) {
      case 0 -> 0;
      case 1 -> MAX;
      case 2 -> random.nextInt(100_000);
      default -> (random.nextLong() & Long.MAX_VALUE) % (MAX + 1);
    };
  }
}
