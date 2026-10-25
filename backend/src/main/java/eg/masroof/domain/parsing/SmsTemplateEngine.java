package eg.masroof.domain.parsing;

import eg.masroof.domain.expense.PaymentMethod;
import eg.masroof.domain.money.Money;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Deterministic rules engine for bank and wallet messages (STORY-044): the first template whose
 * sender and detect pattern match decides the kind; amount, currency, card last four, merchant and
 * time are then read from the text. All matching runs on {@link TextNormalizer#fold folded} text
 * under a step budget, so a hostile body cannot stall a worker (ReDoS).
 */
public final class SmsTemplateEngine {

  /** Business timezone of every message. */
  public static final ZoneId CAIRO = ZoneId.of("Africa/Cairo");

  private static final int STEP_BUDGET = 3_000_000;
  private static final int CONTEXT = 25;
  private static final int MAX_MERCHANT = 60;
  private static final int SHORT_YEAR_BASE = 2000;
  private static final int NOON = 12;
  private static final int RECENT_DAYS = 45;
  private static final String NUM = "(\\d(?:[\\d.,]*\\d)?)";
  private static final String CUR =
      "(EGP|E£|(?<![a-z])L\\.?E\\.?(?![a-z])|USD|US\\$|\\$|EUR|€|SAR|AED|GBP|KWD"
          + "|(?-i:JPY|CHF|CAD|AUD|NZD|CNY|INR|TRY|RUB|KRW|SEK|NOK|DKK|PLN|CZK|HUF|ZAR|BRL|MXN|SGD"
          + "|HKD|THB|MYR|IDR|PHP|QAR|BHD|OMR|JOD|LBP|MAD|TND|DZD|ILS)"
          + "|جنيهات|جنيه[اه]?"
          + "|(?<!\\p{L})ج\\.?م\\.?(?!\\p{L})"
          + "|دولار|يورو|ريال"
          + "|درهم)";

  private static final Pattern CUR_FIRST =
      Pattern.compile("(?<![\\p{L}\\d])" + CUR + "\\s*[:\\-]?\\s*" + NUM, Pattern.CASE_INSENSITIVE);
  private static final Pattern AMOUNT_FIRST =
      Pattern.compile(
          "(?<![\\d:/.,])" + NUM + "\\s*" + CUR + "(?!\\p{L})", Pattern.CASE_INSENSITIVE);
  private static final Pattern KEYWORD_AMOUNT =
      Pattern.compile(
          "(?:مبلغ|بمبلغ|قيمه" + "|بقيمه|amount(?: of)?)\\s*:?\\s*" + NUM,
          Pattern.CASE_INSENSITIVE);
  private static final Pattern ANY_CURRENCY =
      Pattern.compile("(?<![\\p{L}\\d])" + CUR + "(?!\\p{L})", Pattern.CASE_INSENSITIVE);
  private static final Pattern BALANCE_WORDS =
      Pattern.compile(
          "balance|\\bbal\\b|\\bavl\\b|avail|limit|remaining" + "|رصيد|المتاح|متاح",
          Pattern.CASE_INSENSITIVE);

  private static final Pattern LAST4_MASK =
      Pattern.compile("(?:[*xX•]{2,}|\\.{3})\\s*(\\d{4})(?!\\d)");
  private static final Pattern LAST4_EN =
      Pattern.compile(
          "(?:ending(?:\\s+(?:with|in))?|ends\\s+with|card(?:\\s+(?:no|number))?\\.?|a/c|acct"
              + "|account(?:\\s+(?:no|number))?\\.?)\\s*:?\\s*[*xX•.\\-\\s]*?(\\d{4})(?!\\d)",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern LAST4_AR =
      Pattern.compile(
          "(?:المنتهيه|منتهيه"
              + "|رقم|حساب|بطاق[هت]"
              + "[كم]*)\\s*(?:بـ?)?\\s*[*xX•.\\s]*?(\\d{4})(?!\\d)");

  private static final String TERM =
      "(?=\\s+(?:on|in|date|dated|with|using|from|via|avl|avail|available|balance|bal|ref|trx|txn"
          + "|card|by|your|is|was|were|has|had|successful|successfully|completed|approved)\\b"
          + "|\\s+(?:بتاريخ|في|الرصيد"
          + "|رصيد|رصيدك|رقم"
          + "|باستخدام|عبر|من"
          + "|بنجاح)\\s"
          + "|\\s+\\d{1,2}[/\\-]\\d{1,2}|\\s*[.,;](?:\\s|$)|\\s*$)";
  private static final String MERCHANT = "(.{2," + MAX_MERCHANT + "}?)";
  private static final Pattern AT =
      Pattern.compile("(?:\\bat\\b|@|لد[يى]|عند)\\s+" + MERCHANT + TERM, Pattern.CASE_INSENSITIVE);
  private static final Pattern TO =
      Pattern.compile(
          "(?:\\bto\\b|ال[يى]|لصالح|(?<!\\p{L})ل)\\s+" + MERCHANT + TERM, Pattern.CASE_INSENSITIVE);
  private static final Pattern FROM =
      Pattern.compile("(?:\\bfrom\\b|من)\\s+" + MERCHANT + TERM, Pattern.CASE_INSENSITIVE);
  private static final Pattern STATUS_TAIL =
      Pattern.compile(
          "(?:\\s+(?:was|were|has|had|is|successful(?:ly)?|completed|approved|done|received))+$",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern IBAN =
      Pattern.compile("\\b[A-Z]{2}\\d{2}[A-Z0-9]{10,30}\\b", Pattern.CASE_INSENSITIVE);
  private static final Pattern LONG_DIGITS = Pattern.compile("\\d(?:[ -]?\\d){4,}");
  private static final Pattern BAD_MERCHANT =
      Pattern.compile(
          "^(?:(?:your|ur|my|the|an?|account|card|a/c|acct|wallet|ending)\\b|\\*|xx"
              + "|بطاق|حساب|محفظ"
              + "|الحساب|رصيد).*"
              + "|.*\\d[\\d,]*\\.\\d{2}.*|.*\\b(?:egp|usd)\\b.*",
          Pattern.CASE_INSENSITIVE);

  private static final Pattern NOT_A_TRANSACTION_DATE =
      Pattern.compile(
          "(?:valid|until|till|expires?|expiry|due|before|حتي|صالح|استحقاق|ينتهي)[^0-9]{0,20}$",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern DATE_ISO =
      Pattern.compile("(?<![\\d/.,:-])(\\d{4})-(\\d{1,2})-(\\d{1,2})(?!\\d)");
  private static final Pattern DATE_DOTTED =
      Pattern.compile("(?<![\\d/.,:-])(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4}|\\d{2})(?!\\d)");
  private static final Pattern DATE_SLASH =
      Pattern.compile(
          "(?<![\\d/.,:-])(\\d{1,2})[/\\-](\\d{1,2})(?:[/\\-](\\d{4}|\\d{2}))?(?![\\d/])");
  private static final Pattern TIME =
      Pattern.compile(
          "(?<![\\d:])(\\d{1,2}):(\\d{2})(?::(\\d{2}))?"
              + "(?:\\s?(?:([ap])\\.?m\\b\\.?|(ص|م)(?!\\p{L})))?",
          Pattern.CASE_INSENSITIVE);

  private final List<SenderProfile> profiles;
  private final List<SmsTemplate> templates;

  /** Builds an engine; template order is significant (first match wins). */
  public SmsTemplateEngine(List<SenderProfile> profiles, List<SmsTemplate> templates) {
    this.profiles = List.copyOf(profiles);
    this.templates = List.copyOf(templates);
  }

  /** Sender id as compared with profiles: no spaces, dashes, dots or underscores, upper case. */
  public static String senderKey(String sender) {
    String n = TextNormalizer.fold(TextNormalizer.normalize(sender));
    return n.replaceAll("[\\s\\-_.]", "").toUpperCase(Locale.ROOT);
  }

  /** The institution a sender id belongs to, if it is a known one. */
  public Optional<String> institutionOf(String sender) {
    String key = senderKey(sender);
    return profile(key, new Budget()).map(SenderProfile::institution);
  }

  /**
   * Parses one message. Empty when no template matches, no usable amount is found where one is
   * needed, or the matching budget is exhausted.
   */
  public Optional<ParsedSms> parse(String sender, String body, Instant receivedAt) {
    Budget budget = new Budget();
    try {
      return parseWithin(sender, body, receivedAt, budget);
    } catch (BudgetExhausted e) {
      return Optional.empty();
    }
  }

  private Optional<ParsedSms> parseWithin(
      String sender, String body, Instant receivedAt, Budget budget) {
    String display = TextNormalizer.normalize(body);
    String text = TextNormalizer.fold(display);
    String key = senderKey(sender);
    SmsTemplate template = firstTemplate(key, text, budget);
    if (template == null) {
      return Optional.empty();
    }
    SenderProfile profile = profile(key, budget).orElse(null);
    String institution = profile == null ? null : profile.institution();
    if (template.kind().expenseType().isEmpty()) {
      return Optional.of(
          new ParsedSms(
              template.kind(),
              null,
              null,
              null,
              receivedAt,
              PaymentMethod.UNKNOWN,
              template.id(),
              institution));
    }
    Money amount = amount(text, budget);
    if (amount == null) {
      return Optional.empty();
    }
    String last4 = last4(text, budget);
    String merchant = merchant(template.kind(), display, text, budget);
    return Optional.of(
        new ParsedSms(
            template.kind(),
            amount,
            merchant,
            last4,
            occurredAt(text, receivedAt, budget),
            method(profile, template.kind(), last4),
            template.id(),
            institution));
  }

  private @Nullable SmsTemplate firstTemplate(String senderKey, String text, Budget budget) {
    for (SmsTemplate t : templates) {
      if (t.senders() != null && !matcher(t.senders(), senderKey, budget).find()) {
        continue;
      }
      if (matcher(t.detect(), text, budget).find()) {
        return t;
      }
    }
    return null;
  }

  private Optional<SenderProfile> profile(String senderKey, Budget budget) {
    for (SenderProfile p : profiles) {
      if (matcher(p.match(), senderKey, budget).find()) {
        return Optional.of(p);
      }
    }
    return Optional.empty();
  }

  private static PaymentMethod method(
      @Nullable SenderProfile profile, SmsKind kind, @Nullable String last4) {
    if (profile == null) {
      return last4 == null ? PaymentMethod.UNKNOWN : PaymentMethod.CARD;
    }
    PaymentMethod base = profile.method();
    if (base != PaymentMethod.CARD) {
      return base;
    }
    if (kind == SmsKind.TRANSFER) {
      return PaymentMethod.BANK_TRANSFER;
    }
    return last4 == null ? PaymentMethod.UNKNOWN : PaymentMethod.CARD;
  }

  private record Candidate(int start, int end, String number, String currency) {}

  /** First currency amount that is not a balance or limit. */
  private static @Nullable Money amount(String text, Budget budget) {
    TreeMap<Integer, Candidate> byNumber = new TreeMap<>();
    // A bare "amount 1,500" is EGP only when the text names no currency anywhere; otherwise it is
    // the ISO "no currency" code and the caller sends it to review rather than guessing.
    String bare = matcher(ANY_CURRENCY, text, budget).find() ? "XXX" : "EGP";
    Matcher k = matcher(KEYWORD_AMOUNT, text, budget);
    while (k.find()) {
      byNumber.put(k.start(1), new Candidate(k.start(), k.end(), k.group(1), bare));
    }
    Matcher a = matcher(CUR_FIRST, text, budget);
    while (a.find()) {
      String code = TextNormalizer.currencyCode(a.group(1)).orElse("EGP");
      byNumber.put(a.start(2), new Candidate(a.start(), a.end(), a.group(2), code));
    }
    Matcher b = matcher(AMOUNT_FIRST, text, budget);
    while (b.find()) {
      String code = TextNormalizer.currencyCode(b.group(2)).orElse("EGP");
      byNumber.put(b.start(1), new Candidate(b.start(), b.end(), b.group(1), code));
    }
    for (Pattern p : List.of(LAST4_MASK, LAST4_EN, LAST4_AR)) {
      Matcher m = matcher(p, text, budget);
      while (m.find()) {
        byNumber.remove(m.start(1));
      }
    }
    List<Candidate> ordered = new ArrayList<>(byNumber.values());
    ordered.sort((x, y) -> Integer.compare(x.start(), y.start()));
    int previousEnd = 0;
    for (Candidate c : ordered) {
      int from = Math.max(previousEnd, c.start() - CONTEXT);
      boolean balance =
          from < c.start()
              && matcher(BALANCE_WORDS, text.substring(from, c.start()), budget).find();
      previousEnd = Math.max(previousEnd, c.end());
      if (balance) {
        continue;
      }
      OptionalLong minor = TextNormalizer.parseMinorUnits(c.number());
      if (minor.isEmpty() || minor.getAsLong() <= 0) {
        return null;
      }
      return Money.of(minor.getAsLong(), c.currency());
    }
    return null;
  }

  private static @Nullable String last4(String text, Budget budget) {
    for (Pattern p : List.of(LAST4_MASK, LAST4_EN, LAST4_AR)) {
      Matcher m = matcher(p, text, budget);
      if (m.find()) {
        return m.group(1);
      }
    }
    return null;
  }

  private static @Nullable String merchant(
      SmsKind kind, String display, String text, Budget budget) {
    List<Pattern> order =
        switch (kind) {
          case INCOME, REFUND -> List.of(FROM, AT);
          case TRANSFER -> List.of(TO, AT);
          default -> List.of(AT, TO);
        };
    for (Pattern p : order) {
      Matcher m = matcher(p, text, budget);
      while (m.find()) {
        String candidate = cleanMerchant(display.substring(m.start(1), m.end(1)));
        if (candidate.length() >= 2 && !matcher(BAD_MERCHANT, candidate, budget).matches()) {
          return candidate;
        }
      }
    }
    return null;
  }

  private static String cleanMerchant(String raw) {
    int end = raw.length();
    while (end > 0 && ".,;:-*".indexOf(raw.charAt(end - 1)) >= 0) {
      end--;
    }
    String stripped = STATUS_TAIL.matcher(raw.substring(0, end).strip()).replaceAll("");
    String noIban = IBAN.matcher(stripped).replaceAll(m -> mask(m.group()));
    return LONG_DIGITS.matcher(noIban).replaceAll(m -> mask(m.group())).strip();
  }

  /**
   * Keeps only the last four digits or letters: IBANs, account and phone numbers are never stored.
   */
  private static String mask(String value) {
    String compact = value.replaceAll("[ -]", "");
    return "••" + compact.substring(Math.max(0, compact.length() - 4));
  }

  private static Instant occurredAt(String text, Instant receivedAt, Budget budget) {
    LocalDateTime received = LocalDateTime.ofInstant(receivedAt, CAIRO);
    LocalDate date = date(text, received.toLocalDate(), budget);
    if (date == null) {
      return receivedAt;
    }
    LocalTime time = time(text, budget);
    if (time == null) {
      time = date.equals(received.toLocalDate()) ? received.toLocalTime() : LocalTime.of(NOON, 0);
    }
    Instant parsed = date.atTime(time).atZone(CAIRO).toInstant();
    return parsed.isAfter(receivedAt.plus(1, ChronoUnit.DAYS)) ? receivedAt : parsed;
  }

  private record DateHit(int start, LocalDate date) {}

  /**
   * The transaction date: the first date in the text that is within the last 45 days (and not in
   * the future) and is not a validity or due date. Anything else is ignored and the SMS received
   * time is used, so "valid until 31/12" or a stray 2001 never becomes the expense date.
   */
  private static @Nullable LocalDate date(String text, LocalDate received, Budget budget) {
    List<DateHit> hits = new ArrayList<>();
    Matcher iso = matcher(DATE_ISO, text, budget);
    while (iso.find()) {
      add(hits, iso.start(), safeDate(iso.group(1), iso.group(3), iso.group(2), received));
    }
    Matcher dotted = matcher(DATE_DOTTED, text, budget);
    while (dotted.find()) {
      add(
          hits,
          dotted.start(),
          safeDate(dotted.group(3), dotted.group(1), dotted.group(2), received));
    }
    Matcher slash = matcher(DATE_SLASH, text, budget);
    while (slash.find()) {
      add(hits, slash.start(), safeDate(slash.group(3), slash.group(1), slash.group(2), received));
    }
    hits.sort((x, y) -> Integer.compare(x.start(), y.start()));
    for (DateHit hit : hits) {
      boolean recent =
          !hit.date().isBefore(received.minusDays(RECENT_DAYS))
              && !hit.date().isAfter(received.plusDays(1));
      String before = text.substring(Math.max(0, hit.start() - 30), hit.start());
      if (recent && !matcher(NOT_A_TRANSACTION_DATE, before, budget).find()) {
        return hit.date();
      }
    }
    return null;
  }

  private static void add(List<DateHit> hits, int start, @Nullable LocalDate date) {
    if (date != null) {
      hits.add(new DateHit(start, date));
    }
  }

  /**
   * Day-first (Egypt); a missing year is the received year, or the one before if that is future.
   */
  private static @Nullable LocalDate safeDate(
      @Nullable String year, String day, String month, LocalDate received) {
    try {
      int d = Integer.parseInt(day);
      int m = Integer.parseInt(month);
      if (year != null) {
        int y = Integer.parseInt(year);
        return LocalDate.of(year.length() == 2 ? SHORT_YEAR_BASE + y : y, m, d);
      }
      LocalDate candidate = LocalDate.of(received.getYear(), m, d);
      return candidate.isAfter(received.plusDays(1)) ? candidate.minusYears(1) : candidate;
    } catch (DateTimeException | NumberFormatException e) {
      return null;
    }
  }

  private static @Nullable LocalTime time(String text, Budget budget) {
    Matcher m = matcher(TIME, text, budget);
    while (m.find()) {
      LocalTime t = toTime(m);
      if (t != null) {
        return t;
      }
    }
    return null;
  }

  private static @Nullable LocalTime toTime(Matcher m) {
    try {
      int hour = Integer.parseInt(m.group(1));
      int minute = Integer.parseInt(m.group(2));
      int second = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
      String latin = m.group(4);
      String arabic = m.group(5);
      boolean pm = "p".equalsIgnoreCase(latin) || "م".equals(arabic);
      if (latin != null || arabic != null) {
        hour = hour % NOON + (pm ? NOON : 0);
      }
      return LocalTime.of(hour, minute, second);
    } catch (DateTimeException | NumberFormatException e) {
      return null;
    }
  }

  private static Matcher matcher(Pattern pattern, CharSequence input, Budget budget) {
    return pattern.matcher(new Bounded(input, budget));
  }

  /** Steps left for one parse; shared by every matcher it creates. */
  private static final class Budget {
    private int left = STEP_BUDGET;

    void spend() {
      if (--left < 0) {
        throw new BudgetExhausted();
      }
    }
  }

  private static final class BudgetExhausted extends RuntimeException {
    private static final long serialVersionUID = 1L;

    BudgetExhausted() {
      super("regex step budget exhausted", null, false, false);
    }
  }

  /** CharSequence that charges the budget for every character read. */
  private static final class Bounded implements CharSequence {
    private final CharSequence base;
    private final Budget budget;

    Bounded(CharSequence base, Budget budget) {
      this.base = base;
      this.budget = budget;
    }

    @Override
    public int length() {
      return base.length();
    }

    @Override
    public char charAt(int index) {
      budget.spend();
      return base.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
      return base.subSequence(start, end);
    }

    @Override
    public String toString() {
      return base.toString();
    }
  }
}
