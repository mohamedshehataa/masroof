package eg.masroof.application.sms;

import eg.masroof.application.expense.ExpenseException;
import eg.masroof.application.household.CurrentHousehold;
import eg.masroof.application.household.CurrentHousehold.Household;
import eg.masroof.domain.parsing.MessageClassifier;
import eg.masroof.domain.parsing.MessageClassifier.MessageClass;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * POST /api/ingest/sms (REQ-SMS-001): validates, drops OTPs (second line of defence after the
 * device), stores the raw message once per (device, sms id) and queues it for the worker.
 */
@Service
public class SmsIngestService {

  static final int MAX_BODY = 2000;
  static final int MAX_SENDER = 40;
  static final int MAX_ID = 64;
  static final Instant MIN_RECEIVED_AT = Instant.parse("2000-01-01T00:00:00Z");
  static final Duration MAX_FUTURE = Duration.ofDays(1);
  private static final Pattern PRINTABLE_ID = Pattern.compile("^[\\x21-\\x7E]+$");
  private static final Logger LOG = LoggerFactory.getLogger(SmsIngestService.class);

  /** One uploaded SMS. */
  public record Command(
      String sender, String body, Instant receivedAt, UUID deviceId, String smsId) {

    @Override
    public String toString() {
      return "Command[deviceId=" + deviceId + "]";
    }
  }

  /** What happened to it. */
  public enum Outcome {
    ACCEPTED,
    DUPLICATE,
    REJECTED_OTP
  }

  /** The stored row's id (a throwaway id for a rejected OTP, which is never stored). */
  public record Accepted(UUID rawMessageId, Outcome outcome) {}

  private final RawMessageRepository messages;
  private final CurrentHousehold currentHousehold;
  private final CallerMember callerMember;
  private final Clock clock;

  SmsIngestService(
      RawMessageRepository messages,
      CurrentHousehold currentHousehold,
      CallerMember callerMember,
      Clock clock) {
    this.messages = messages;
    this.currentHousehold = currentHousehold;
    this.callerMember = callerMember;
    this.clock = clock;
  }

  /** Validates and stores one SMS. */
  @Transactional
  public Accepted ingest(Command cmd) {
    Instant now = clock.instant();
    validate(cmd, now);
    Household household = currentHousehold.get();
    if (MessageClassifier.classify(cmd.body()) == MessageClass.OTP) {
      LOG.info("sms rejected as otp household={}", household.id());
      return new Accepted(UUID.randomUUID(), Outcome.REJECTED_OTP);
    }
    RawMessageRepository.Stored stored =
        messages.insertSms(
            new RawMessageRepository.NewSms(
                UUID.randomUUID(),
                household.id(),
                callerMember.memberId().orElse(null),
                cmd.deviceId(),
                cmd.smsId(),
                cmd.sender().strip(),
                cmd.body(),
                cmd.receivedAt(),
                now));
    return new Accepted(stored.id(), stored.inserted() ? Outcome.ACCEPTED : Outcome.DUPLICATE);
  }

  private static void validate(Command cmd, Instant now) {
    String sender = cmd.sender().strip();
    if (sender.isEmpty() || sender.codePointCount(0, sender.length()) > MAX_SENDER) {
      throw ExpenseException.invalid("sender", "must be 1 to " + MAX_SENDER + " characters");
    }
    if (cmd.body().isBlank() || cmd.body().codePointCount(0, cmd.body().length()) > MAX_BODY) {
      throw ExpenseException.invalid("body", "must be 1 to " + MAX_BODY + " characters");
    }
    if (cmd.body().indexOf('\u0000') >= 0 || sender.indexOf('\u0000') >= 0) {
      throw ExpenseException.invalid("body", "must not contain the NUL character");
    }
    if (cmd.smsId().length() > MAX_ID || !PRINTABLE_ID.matcher(cmd.smsId()).matches()) {
      throw ExpenseException.invalid("smsId", "must be 1 to " + MAX_ID + " printable characters");
    }
    if (cmd.receivedAt().isBefore(MIN_RECEIVED_AT)
        || cmd.receivedAt().isAfter(now.plus(MAX_FUTURE))) {
      throw ExpenseException.invalid("receivedAt", "is outside the accepted range");
    }
  }
}
