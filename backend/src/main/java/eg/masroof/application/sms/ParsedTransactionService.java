package eg.masroof.application.sms;

import eg.masroof.application.expense.ExpenseException;
import eg.masroof.application.expense.ExpenseRepository;
import eg.masroof.application.household.CurrentHousehold;
import eg.masroof.application.household.CurrentHousehold.Household;
import eg.masroof.domain.expense.Expense;
import eg.masroof.domain.expense.ExpenseSource;
import eg.masroof.domain.expense.ExpenseStatus;
import eg.masroof.domain.expense.ExpenseType;
import eg.masroof.domain.expense.PaymentMethod;
import eg.masroof.domain.money.Money;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * POST /api/ingest/parsed-transactions (REQ-SMS-001, REQ-NTF-001). The phone parsed a bank message
 * on-device and sends only a structured record; no message text exists on the server. Everything is
 * validated strictly because the record is the only thing that crosses the trust boundary: the one
 * free-text field is the merchant label, which must be digit-free and not read like a secret.
 */
@Service
public class ParsedTransactionService {

  static final long MAX_AMOUNT_MINOR = 10_000_000_000_000L;
  static final int MAX_LABEL = 40;
  static final Instant MIN_OCCURRED_AT = Instant.parse("2000-01-01T00:00:00Z");
  static final Duration MAX_FUTURE = Duration.ofDays(1);
  private static final Pattern LOCAL_ID = Pattern.compile("^[0-9a-f]{64}$");
  private static final Pattern LAST4 = Pattern.compile("^[0-9]{4}$");
  private static final Pattern SENDER = Pattern.compile("^[A-Za-z][A-Za-z0-9._&-]{0,19}$");
  private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");
  private static final Pattern INSTITUTION = Pattern.compile("^[\\p{L} .&-]{1,40}$");
  // Letters, spaces and the punctuation of names and handles ("ahmed.ali@instapay", "B.TECH").
  private static final Pattern LABEL = Pattern.compile("^[\\p{L}\\p{M} .&'@_/*(),+-]{1,40}$");
  // A label that reads like a secret message fragment is refused even though it has no digit.
  private static final Pattern SECRET_WORDS =
      Pattern.compile(
          "\\b(?:otp|o\\.t\\.p|pin|code|codes|password|passcode|cvv|cvc|token|secret|verification"
              + "|verify|one[- ]?time|3d ?secure|3ds|activation|authenti[a-z]*)\\b"
              + "|كود|رمز|تحقق|سري|كلمه المرور|كلمة المرور|كلمه السر|كلمة السر|لا تشارك",
          Pattern.CASE_INSENSITIVE);

  /** The record as received (already shaped by the generated model). */
  public record Command(
      UUID deviceId,
      String localId,
      ExpenseSource source,
      String kind,
      long amountMinor,
      String currency,
      Instant occurredAt,
      @Nullable String instrumentLast4,
      String senderId,
      @Nullable String institution,
      @Nullable String paymentMethod,
      @Nullable String merchant) {}

  /** What happened to it. */
  public enum Outcome {
    ACCEPTED,
    DUPLICATE
  }

  /** The evidence row id and the outcome. */
  public record Accepted(UUID rawMessageId, Outcome outcome) {}

  private final RawMessageRepository messages;
  private final SmsAccounts accounts;
  private final ExpenseRepository expenses;
  private final CurrentHousehold currentHousehold;
  private final CallerMember callerMember;
  private final Clock clock;

  ParsedTransactionService(
      RawMessageRepository messages,
      SmsAccounts accounts,
      ExpenseRepository expenses,
      CurrentHousehold currentHousehold,
      CallerMember callerMember,
      Clock clock) {
    this.messages = messages;
    this.accounts = accounts;
    this.expenses = expenses;
    this.currentHousehold = currentHousehold;
    this.callerMember = callerMember;
    this.clock = clock;
  }

  /** Validates and records one parsed transaction, once per (device, local id). */
  @Transactional
  public Accepted ingest(Command cmd) {
    Instant now = clock.instant();
    Household household = currentHousehold.get();
    ExpenseType type = validate(cmd, now, household.baseCurrency());
    UUID member = callerMember.memberId().orElse(null);
    RawMessageRepository.Stored stored =
        messages.insertParsed(
            new RawMessageRepository.NewParsed(
                UUID.randomUUID(),
                household.id(),
                member,
                cmd.source(),
                cmd.deviceId(),
                cmd.localId(),
                cmd.senderId(),
                cmd.occurredAt(),
                now));
    if (!stored.inserted()) {
      return new Accepted(stored.id(), Outcome.DUPLICATE);
    }
    Optional<SmsAccounts.AccountRef> account =
        cmd.instrumentLast4() == null
            ? Optional.empty()
            : accounts.findByLast4(household.id(), cmd.instrumentLast4());
    Expense row =
        new Expense(
            UUID.randomUUID(),
            household.id(),
            type,
            Money.of(cmd.amountMinor(), cmd.currency()),
            cmd.occurredAt(),
            null,
            account.map(SmsAccounts.AccountRef::id).orElse(null),
            account.map(SmsAccounts.AccountRef::memberId).orElse(member),
            paymentMethod(cmd.paymentMethod()),
            normalizedLabel(cmd.merchant()),
            note(cmd),
            List.of(),
            ExpenseStatus.CONFIRMED,
            cmd.source(),
            null,
            now,
            now);
    UUID expenseId = expenses.insert(row).id();
    messages.attachExpense(stored.id(), expenseId);
    return new Accepted(stored.id(), Outcome.ACCEPTED);
  }

  private static ExpenseType validate(Command cmd, Instant now, String baseCurrency) {
    if (cmd.source() != ExpenseSource.SMS && cmd.source() != ExpenseSource.PUSH) {
      throw ExpenseException.invalid("source", "must be SMS or NOTIFICATION");
    }
    if (!LOCAL_ID.matcher(cmd.localId()).matches()) {
      throw ExpenseException.invalid("localId", "must be 64 lower-case hex characters");
    }
    ExpenseType type = typeOf(cmd.kind());
    if (cmd.amountMinor() < 1 || cmd.amountMinor() > MAX_AMOUNT_MINOR) {
      throw ExpenseException.invalid("amountMinor", "is outside the accepted range");
    }
    if (!CURRENCY.matcher(cmd.currency()).matches()) {
      throw ExpenseException.invalid("currency", "must be an ISO 4217 code");
    }
    if (!cmd.currency().equals(baseCurrency)) {
      throw ExpenseException.invalid("currency", "must be the household currency");
    }
    if (cmd.occurredAt().isBefore(MIN_OCCURRED_AT)
        || cmd.occurredAt().isAfter(now.plus(MAX_FUTURE))) {
      throw ExpenseException.invalid("occurredAt", "is outside the accepted range");
    }
    if (cmd.instrumentLast4() != null && !LAST4.matcher(cmd.instrumentLast4()).matches()) {
      throw ExpenseException.invalid("instrumentLast4", "must be exactly four digits");
    }
    if (!SENDER.matcher(cmd.senderId()).matches()) {
      throw ExpenseException.invalid("senderId", "is not a sender id");
    }
    if (cmd.institution() != null && !INSTITUTION.matcher(cmd.institution()).matches()) {
      throw ExpenseException.invalid("institution", "must be a name without digits");
    }
    if (cmd.paymentMethod() != null) {
      paymentMethod(cmd.paymentMethod());
    }
    String label = cmd.merchant();
    if (label != null && !label.isBlank()) {
      if (label.codePoints().anyMatch(Character::isDigit)) {
        throw ExpenseException.invalid("merchant", "must not contain digits");
      }
      String stripped = label.strip();
      if (!LABEL.matcher(stripped).matches()
          || SECRET_WORDS.matcher(stripped.toLowerCase(Locale.ROOT)).find()) {
        throw ExpenseException.invalid("merchant", "is not an acceptable label");
      }
    }
    return type;
  }

  private static @Nullable String normalizedLabel(@Nullable String merchant) {
    return merchant == null || merchant.isBlank() ? null : merchant.strip();
  }

  private static ExpenseType typeOf(String kind) {
    return switch (kind) {
      case "PURCHASE", "FEE" -> ExpenseType.EXPENSE;
      case "TRANSFER_OUT", "TRANSFER_IN" -> ExpenseType.TRANSFER;
      case "INCOME" -> ExpenseType.INCOME;
      case "REFUND" -> ExpenseType.REFUND;
      case "CASH_WITHDRAWAL" -> ExpenseType.CASH_WITHDRAWAL;
      default -> throw ExpenseException.invalid("kind", "is not a supported kind");
    };
  }

  private static PaymentMethod paymentMethod(@Nullable String value) {
    if (value == null) {
      return PaymentMethod.UNKNOWN;
    }
    return switch (value) {
      case "CARD" -> PaymentMethod.CARD;
      case "WALLET" -> PaymentMethod.WALLET;
      case "INSTAPAY" -> PaymentMethod.INSTAPAY;
      case "BANK_TRANSFER" -> PaymentMethod.BANK_TRANSFER;
      case "UNKNOWN" -> PaymentMethod.UNKNOWN;
      default -> throw ExpenseException.invalid("paymentMethod", "is not a supported method");
    };
  }

  /** "SMS CIB *1234": the title when no merchant was found; built from validated fields only. */
  private static String note(Command cmd) {
    StringBuilder sb =
        new StringBuilder(cmd.source() == ExpenseSource.PUSH ? "Notification" : "SMS");
    if (cmd.institution() != null && !cmd.institution().isBlank()) {
      sb.append(' ').append(cmd.institution().strip());
    }
    if (cmd.instrumentLast4() != null) {
      sb.append(" *").append(cmd.instrumentLast4());
    }
    return sb.toString();
  }
}
