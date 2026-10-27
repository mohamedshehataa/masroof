package eg.masroof.domain.expense;

import eg.masroof.domain.money.Money;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** Validation of the purchased-items list of an expense (REQ-RCPT-031, REQ-RCPT-032). */
public final class ExpenseItemRules {

  public static final int MAX_ITEMS = 100;
  public static final int MAX_NAME = 200;
  public static final BigDecimal MAX_QTY = new BigDecimal("999999");

  private static final Pattern QTY = Pattern.compile("^\\d{1,6}(\\.\\d{1,3})?$");

  private ExpenseItemRules() {}

  /** An item as received: nothing is trusted yet. */
  public record Draft(
      @Nullable String name,
      @Nullable String qty,
      @Nullable Money total,
      boolean hasUnitPrice,
      boolean hasCategory) {}

  /** One offending field such as {@code items[2].total}; never carries the value. */
  public record Violation(String field, String message) {}

  /** The list was rejected; carries every violation found. */
  public static final class InvalidItems extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final transient List<Violation> violations;

    InvalidItems(List<Violation> violations) {
      super("invalid items");
      this.violations = List.copyOf(violations);
    }

    /** All violations, in input order. */
    public List<Violation> violations() {
      return violations;
    }
  }

  /**
   * Validates and normalises the drafts (trimmed names, qty at scale 3), assigning ids from {@code
   * ids}. Input order is kept.
   *
   * @throws InvalidItems when any rule is broken, naming every field
   */
  public static List<ExpenseItem> validate(
      List<Draft> drafts, String currency, long maxMinor, Supplier<UUID> ids) {
    List<Violation> bad = new ArrayList<>();
    if (drafts.size() > MAX_ITEMS) {
      bad.add(new Violation("items", "must have at most " + MAX_ITEMS + " entries"));
      throw new InvalidItems(bad);
    }
    List<ExpenseItem> out = new ArrayList<>(drafts.size());
    for (int i = 0; i < drafts.size(); i++) {
      Draft d = drafts.get(i);
      String at = "items[" + i + "]";
      String name = checkName(d.name(), at, bad);
      BigDecimal qty = checkQty(d.qty(), at, bad);
      Money total = checkTotal(d.total(), currency, maxMinor, at, bad);
      if (d.hasUnitPrice()) {
        bad.add(new Violation(at + ".unitPrice", "is not supported"));
      }
      if (d.hasCategory()) {
        bad.add(new Violation(at + ".categoryId", "is not supported"));
      }
      if (name != null) {
        out.add(new ExpenseItem(ids.get(), name, qty, total));
      }
    }
    if (!bad.isEmpty()) {
      throw new InvalidItems(bad);
    }
    return List.copyOf(out);
  }

  private static @Nullable String checkName(@Nullable String raw, String at, List<Violation> bad) {
    String field = at + ".name";
    if (raw == null) {
      bad.add(new Violation(field, "is required"));
      return null;
    }
    if (raw.indexOf('\u0000') >= 0) {
      bad.add(new Violation(field, "must not contain the NUL character"));
      return null;
    }
    String name = raw.strip();
    int length = name.codePointCount(0, name.length());
    if (length < 1 || length > MAX_NAME) {
      bad.add(new Violation(field, "must be 1 to " + MAX_NAME + " characters"));
      return null;
    }
    return name;
  }

  private static @Nullable BigDecimal checkQty(
      @Nullable String raw, String at, List<Violation> bad) {
    if (raw == null) {
      return null;
    }
    String field = at + ".qty";
    if (!QTY.matcher(raw).matches()) {
      bad.add(new Violation(field, "must be a positive number with at most 3 decimals"));
      return null;
    }
    BigDecimal qty = new BigDecimal(raw);
    if (qty.signum() <= 0 || qty.compareTo(MAX_QTY) > 0) {
      bad.add(new Violation(field, "must be greater than 0 and at most " + MAX_QTY));
      return null;
    }
    return qty.setScale(3);
  }

  private static @Nullable Money checkTotal(
      @Nullable Money total, String currency, long maxMinor, String at, List<Violation> bad) {
    if (total == null) {
      return null;
    }
    String field = at + ".total";
    if (!total.currency().equals(currency)) {
      bad.add(new Violation(field + ".currency", "must be " + currency));
      return null;
    }
    if (total.amountMinor() < 0 || total.amountMinor() > maxMinor) {
      bad.add(new Violation(field + ".amountMinor", "must be between 0 and " + maxMinor));
      return null;
    }
    return total;
  }
}
