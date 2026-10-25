package eg.masroof.application.expense;

import java.util.Optional;

/** Allowed sort orders of the expense list; the id is always the tiebreaker. */
public enum ExpenseSort {
  OCCURRED_AT_ASC("occurredAt", "occurred_at", false),
  OCCURRED_AT_DESC("-occurredAt", "occurred_at", true),
  AMOUNT_ASC("amount", "amount_minor", false),
  AMOUNT_DESC("-amount", "amount_minor", true),
  CREATED_AT_ASC("createdAt", "created_at", false),
  CREATED_AT_DESC("-createdAt", "created_at", true);

  private final String key;
  private final String column;
  private final boolean descending;

  ExpenseSort(String key, String column, boolean descending) {
    this.key = key;
    this.column = column;
    this.descending = descending;
  }

  /** The API value (prefix {@code -} means descending). */
  public String key() {
    return key;
  }

  /** The database column (a fixed whitelist, safe to put in SQL). */
  public String column() {
    return column;
  }

  /** Whether larger values come first. */
  public boolean descending() {
    return descending;
  }

  /** Whether the column holds an amount rather than an instant. */
  public boolean isAmount() {
    return this == AMOUNT_ASC || this == AMOUNT_DESC;
  }

  /** Parses the API value. */
  public static Optional<ExpenseSort> parse(String key) {
    for (ExpenseSort s : values()) {
      if (s.key.equals(key)) {
        return Optional.of(s);
      }
    }
    return Optional.empty();
  }
}
