package eg.masroof.domain.parsing;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Secrecy no longer depends on recognising an OTP: every message is redacted before it is queued,
 * uploaded or stored. After {@link TextNormalizer#normalize} (NFKC, invisible characters and bidi
 * marks removed, every digit script to ASCII, unified dashes, dots and spaces) EVERY digit sequence
 * is replaced by bullets, except what a parser needs: (1) an amount directly next to a currency
 * token, (2) dates and times, (3) the last four digits after a card/account marker or a masked
 * prefix. Digit groups separated by one space or dash count as one sequence ("556 677").
 *
 * <p>The same algorithm runs on the device ({@code SmsRedactor.kt}) and here; both are tested
 * against {@code fixtures/sms/secrets.jsonl}. Redaction is idempotent.
 */
public final class SmsRedactor {

  private static final char BULLET = '•';
  private static final String NUM = "\\d(?:[\\d.,]*\\d)?";
  private static final String CUR =
      "(?:(?<![\\p{L}\\d])(?:egp|le|l\\.e\\.?|e£|usd|eur|sar|sr|aed|dhs|gbp|jpy|yen|tl|kwd|qar"
          + "|ج\\.?\\s?م\\.?|جنيه[اه]?|جنيهات"
          + "|دولار|يورو|ريال|درهم"
          + "|(?-i:CHF|CAD|AUD|NZD|CNY|INR|TRY|RUB|KRW|SEK|NOK|DKK|PLN|CZK|HUF|ZAR|BRL|MXN|SGD|HKD"
          + "|THB|MYR|IDR|PHP|BHD|OMR|JOD|LBP|MAD|TND|DZD|ILS))(?![\\p{L}])|\\$|€|£)";

  private static final Pattern CURRENCY_THEN_NUMBER =
      Pattern.compile("(" + CUR + ")[ :]?(" + NUM + ")", Pattern.CASE_INSENSITIVE);
  private static final Pattern NUMBER_THEN_CURRENCY =
      Pattern.compile("(?<![\\d.,])(" + NUM + ") ?(" + CUR + ")", Pattern.CASE_INSENSITIVE);
  private static final Pattern KEYWORD_AMOUNT =
      Pattern.compile(
          "(?:amount(?: of)?|value|مبلغ|بمبلغ" + "|قيمه|بقيمه)[ :]*(" + NUM + ")",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern CODE_BEFORE =
      Pattern.compile(
          "(?:code|otp|pin|key|token|passcode|password|رمز|كود" + "|الرقم|سري)[^\\p{L}\\d]{0,3}$",
          Pattern.CASE_INSENSITIVE);

  private static final Pattern DATE_ISO =
      Pattern.compile("(?<![\\d/.:-])(\\d{4})-(\\d{1,2})-(\\d{1,2})(?![\\d])");
  private static final Pattern DATE_FULL =
      Pattern.compile("(?<![\\d/.:-])(\\d{1,2})([/.-])(\\d{1,2})\\2(\\d{4}|\\d{2})(?![\\d/:])");
  private static final Pattern DATE_SHORT =
      Pattern.compile("(?<![\\d/.:-])(\\d{1,2})/(\\d{1,2})(?![\\d/:.])");
  private static final Pattern TIME =
      Pattern.compile("(?<![\\d:])(\\d{1,2}):(\\d{2})(?::(\\d{2}))?(?![\\d:])");

  private static final Pattern LAST4_MASKED =
      Pattern.compile("(?:[*x•]{2,}|\\.{3}) ?(\\d{4})(?!\\d)", Pattern.CASE_INSENSITIVE);
  private static final Pattern LAST4_EN =
      Pattern.compile(
          "(?:ending(?: with| in)?|ends with|a/c|acct|account|card(?: no\\.?| number)?)[ :]*"
              + "[*x•.-]* ?(\\d{4})(?!\\d)",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern LAST4_AR =
      Pattern.compile(
          "(?:المنتهيه|منتهيه"
              + "|ينتهي|رقم|حساب"
              + "|بطاق[هت][كم]*) ?(?:ب)? ?[*x•.]* ?(\\d{4})(?!\\d)",
          Pattern.CASE_INSENSITIVE);

  private SmsRedactor() {}

  /** Normalizes and redacts; the result is what may be queued, uploaded and stored. */
  public static String redact(String body) {
    return mask(TextNormalizer.normalize(body));
  }

  /** Whether the (normalized) text has an amount next to a currency token or an amount keyword. */
  public static boolean hasCurrencyAmount(String normalized) {
    String f = TextNormalizer.fold(normalized);
    return CURRENCY_THEN_NUMBER.matcher(f).find()
        || KEYWORD_AMOUNT.matcher(f).find()
        || amountBeforeCurrency(f, new boolean[f.length()]);
  }

  private static String mask(String text) {
    String f = TextNormalizer.fold(text);
    boolean[] keep = new boolean[text.length()];
    protectAmounts(f, keep);
    protectDatesAndTimes(f, keep);
    protectGroup(f, keep, LAST4_MASKED);
    protectGroup(f, keep, LAST4_EN);
    protectGroup(f, keep, LAST4_AR);
    StringBuilder out = new StringBuilder(text.length());
    int i = 0;
    while (i < text.length()) {
      char c = text.charAt(i);
      if (!Character.isDigit(c) || keep[i]) {
        out.append(c);
        i++;
        continue;
      }
      int end = i;
      while (end < text.length()) {
        if (Character.isDigit(text.charAt(end)) && !keep[end]) {
          end++;
        } else if (isGroupSeparator(text, end, keep)) {
          end++;
        } else {
          break;
        }
      }
      for (int k = i; k < end; k++) {
        out.append(Character.isDigit(text.charAt(k)) ? BULLET : text.charAt(k));
      }
      i = end;
    }
    return out.toString();
  }

  /** One space or dash between two unprotected digits joins them into one sequence. */
  private static boolean isGroupSeparator(String text, int at, boolean[] keep) {
    char c = text.charAt(at);
    return (c == ' ' || c == '-')
        && !keep[at]
        && at + 1 < text.length()
        && Character.isDigit(text.charAt(at + 1))
        && !keep[at + 1];
  }

  private static void protectAmounts(String f, boolean[] keep) {
    Matcher a = CURRENCY_THEN_NUMBER.matcher(f);
    while (a.find()) {
      protect(keep, a.start(2), a.end(2));
    }
    Matcher k = KEYWORD_AMOUNT.matcher(f);
    while (k.find()) {
      protect(keep, k.start(1), k.end(1));
    }
    amountBeforeCurrency(f, keep);
  }

  /**
   * "500.00 EGP" / "1500 جنيه": protects the number unless the currency already owns a number after
   * it.
   */
  private static boolean amountBeforeCurrency(String f, boolean[] keep) {
    boolean found = false;
    Matcher b = NUMBER_THEN_CURRENCY.matcher(f);
    while (b.find()) {
      int currencyStart = b.start(2);
      String afterCurrency = f.substring(b.end(2));
      boolean currencyOwnsNextNumber = afterCurrency.matches("(?s)^[ :]?\\d.*");
      String before = f.substring(Math.max(0, b.start(1) - 15), b.start(1));
      if (currencyStart >= 0 && !currencyOwnsNextNumber && !CODE_BEFORE.matcher(before).find()) {
        protect(keep, b.start(1), b.end(1));
        found = true;
      }
    }
    return found;
  }

  private static void protectDatesAndTimes(String f, boolean[] keep) {
    Matcher iso = DATE_ISO.matcher(f);
    while (iso.find()) {
      if (validDate(iso.group(1), iso.group(2), iso.group(3), true)) {
        protect(keep, iso.start(), iso.end());
      }
    }
    Matcher full = DATE_FULL.matcher(f);
    while (full.find()) {
      if (validDate(full.group(4), full.group(3), full.group(1), true)) {
        protect(keep, full.start(), full.end());
      }
    }
    Matcher shortDate = DATE_SHORT.matcher(f);
    while (shortDate.find()) {
      if (validDate("2024", shortDate.group(2), shortDate.group(1), false)) {
        protect(keep, shortDate.start(), shortDate.end());
      }
    }
    Matcher time = TIME.matcher(f);
    while (time.find()) {
      int h = Integer.parseInt(time.group(1));
      int m = Integer.parseInt(time.group(2));
      int s = time.group(3) == null ? 0 : Integer.parseInt(time.group(3));
      if (h < 24 && m < 60 && s < 60) {
        protect(keep, time.start(), time.end());
      }
    }
  }

  private static boolean validDate(String year, String month, String day, boolean withYear) {
    try {
      int y = Integer.parseInt(year);
      LocalDate.of(
          year.length() == 2 ? 2000 + y : y, Integer.parseInt(month), Integer.parseInt(day));
      return !withYear || year.length() == 2 || y >= 1900;
    } catch (DateTimeException | NumberFormatException e) {
      return false;
    }
  }

  private static void protectGroup(String f, boolean[] keep, Pattern pattern) {
    Matcher m = pattern.matcher(f);
    while (m.find()) {
      protect(keep, m.start(1), m.end(1));
    }
  }

  private static void protect(boolean[] keep, int start, int end) {
    for (int i = start; i < end; i++) {
      keep[i] = true;
    }
  }
}
