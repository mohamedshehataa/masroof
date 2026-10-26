package eg.masroof.application.sms;

import eg.masroof.application.expense.ExpenseRepository;
import eg.masroof.domain.expense.Expense;
import eg.masroof.domain.expense.ExpenseSource;
import eg.masroof.domain.expense.ExpenseStatus;
import eg.masroof.domain.parsing.MessageClassifier;
import eg.masroof.domain.parsing.MessageClassifier.MessageClass;
import eg.masroof.domain.parsing.ParsedSms;
import eg.masroof.domain.parsing.SmsKind;
import eg.masroof.domain.parsing.SmsTemplateEngine;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The worker side of SMS ingestion (STORY-043, STORY-044): claims queued raw messages with SKIP
 * LOCKED and turns them into expenses with deterministic rules only. A message that no template
 * explains is kept as {@code NEEDS_REVIEW}; it never becomes an expense and is never dropped.
 *
 * <p>Claiming and processing are separate transactions: the claim leases the row, the processing
 * transaction re-locks it, so the expense and the status change commit together or not at all.
 */
@Service
public class SmsProcessor {

  static final Duration LEASE = Duration.ofMinutes(2);
  static final int MAX_ATTEMPTS = 5;
  static final long MAX_AMOUNT_MINOR = 10_000_000_000_000L;
  static final Instant MIN_OCCURRED_AT = Instant.parse("2000-01-01T00:00:00Z");
  private static final Duration BASE_BACKOFF = Duration.ofSeconds(10);
  private static final Duration MAX_BACKOFF = Duration.ofMinutes(15);
  private static final Logger LOG = LoggerFactory.getLogger(SmsProcessor.class);

  private record Decision(
      String status,
      @Nullable UUID expenseId,
      @Nullable String templateId,
      @Nullable String errorCode,
      boolean scrubBody) {

    static Decision review(String errorCode, @Nullable String templateId) {
      return new Decision("NEEDS_REVIEW", null, templateId, errorCode, false);
    }

    static Decision ignored(String reason, @Nullable String templateId, boolean scrub) {
      return new Decision("IGNORED", null, templateId, reason, scrub);
    }
  }

  private final RawMessageRepository messages;
  private final SmsTemplateEngine engine;
  private final SmsAccounts accounts;
  private final ExpenseRepository expenses;
  private final Clock clock;
  private final TransactionTemplate tx;

  SmsProcessor(
      RawMessageRepository messages,
      SmsTemplateEngine engine,
      SmsAccounts accounts,
      ExpenseRepository expenses,
      Clock clock,
      PlatformTransactionManager transactions) {
    this.messages = messages;
    this.engine = engine;
    this.accounts = accounts;
    this.expenses = expenses;
    this.clock = clock;
    this.tx = new TransactionTemplate(transactions);
  }

  /** Processes up to {@code max} due messages; returns how many were handled. */
  public int drain(int max) {
    int handled = 0;
    while (handled < max) {
      Optional<RawMessage> claimed = tx.execute(s -> messages.claimNext(clock.instant(), LEASE));
      if (claimed == null || claimed.isEmpty()) {
        break;
      }
      handle(claimed.get());
      handled++;
    }
    return handled;
  }

  private void handle(RawMessage claimed) {
    try {
      tx.executeWithoutResult(s -> process(claimed));
    } catch (RuntimeException e) {
      // Only ids and the exception type are logged: the message text must never reach a log.
      LOG.warn(
          "sms processing failed rawMessageId={} attempt={} error={}",
          claimed.id(),
          claimed.attempts(),
          e.getClass().getSimpleName());
      Instant now = clock.instant();
      boolean dead = claimed.attempts() >= MAX_ATTEMPTS;
      tx.executeWithoutResult(
          s ->
              messages.retryLater(
                  claimed.id(),
                  now.plus(backoff(claimed.attempts())),
                  dead,
                  "processing_error",
                  now));
    }
  }

  static Duration backoff(int attempts) {
    long factor = 1L << Math.min(Math.max(attempts - 1, 0), 10);
    Duration d = BASE_BACKOFF.multipliedBy(factor);
    return d.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : d;
  }

  private void process(RawMessage claimed) {
    Optional<RawMessage> locked = messages.lockQueued(claimed.id());
    if (locked.isEmpty()) {
      return;
    }
    RawMessage raw = locked.get();
    Instant now = clock.instant();
    Decision d = decide(raw, now);
    messages.finish(
        raw.id(), d.status(), d.expenseId(), d.templateId(), d.errorCode(), d.scrubBody(), now);
  }

  private Decision decide(RawMessage raw, Instant now) {
    MessageClass kind = MessageClassifier.classify(raw.body());
    if (kind == MessageClass.OTP) {
      return Decision.ignored("otp", null, true);
    }
    if (kind == MessageClass.PROMO) {
      return Decision.ignored("promo", null, false);
    }
    Optional<ParsedSms> parsed =
        engine.parse(raw.sender() == null ? "" : raw.sender(), raw.body(), raw.receivedAt());
    if (parsed.isEmpty()) {
      return Decision.review("no_template", null);
    }
    ParsedSms p = parsed.get();
    if (p.kind() == SmsKind.INFO) {
      return Decision.ignored("info", p.templateId(), false);
    }
    if (p.kind().expenseType().isEmpty() || p.amount() == null) {
      return Decision.review(
          p.kind() == SmsKind.REVERSAL ? "reversal" : "no_amount", p.templateId());
    }
    if (!p.amount().currency().equals(raw.baseCurrency())) {
      return Decision.review("unsupported_currency", p.templateId());
    }
    long minor = p.amount().amountMinor();
    boolean badDate =
        p.occurredAt().isBefore(MIN_OCCURRED_AT)
            || p.occurredAt().isAfter(now.plus(Duration.ofDays(1)));
    if (minor <= 0 || minor > MAX_AMOUNT_MINOR || badDate) {
      return Decision.review("out_of_range", p.templateId());
    }
    UUID expenseId = createExpense(raw, p, now);
    return new Decision("DONE", expenseId, p.templateId(), null, false);
  }

  private UUID createExpense(RawMessage raw, ParsedSms p, Instant now) {
    Optional<SmsAccounts.AccountRef> account =
        p.last4() == null ? Optional.empty() : accounts.findByLast4(raw.householdId(), p.last4());
    UUID paidBy = account.isPresent() ? account.get().memberId() : raw.memberId();
    Expense row =
        new Expense(
            UUID.randomUUID(),
            raw.householdId(),
            p.kind().expenseType().orElseThrow(),
            Objects.requireNonNull(p.amount()),
            p.occurredAt(),
            null,
            account.isPresent() ? account.get().id() : null,
            paidBy,
            p.paymentMethod(),
            p.merchant(),
            note(p),
            List.of(),
            ExpenseStatus.CONFIRMED,
            ExpenseSource.SMS,
            null,
            now,
            now);
    return expenses.insert(row).id();
  }

  /** "SMS CIB *1234": shown as the title when no merchant was found; last four only. */
  private static String note(ParsedSms p) {
    StringBuilder sb = new StringBuilder("SMS");
    if (p.institution() != null) {
      sb.append(' ').append(p.institution());
    }
    if (p.last4() != null) {
      sb.append(" *").append(p.last4());
    }
    return sb.toString();
  }
}
