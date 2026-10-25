package eg.masroof.domain.parsing;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Deterministic, pure text normalization for untrusted message bodies (STORY-041).
 *
 * <p>{@link #normalize} is idempotent, never throws on malformed Unicode and is bounded to {@link
 * #MAX_LENGTH} code points. The caller keeps the original text next to the normalized one.
 */
public final class TextNormalizer {

  /** Longest text that is normalized; the rest is cut off. */
  public static final int MAX_LENGTH = 4000;

  private static final int MAX_PASSES = 6;
  private static final int MAX_AMOUNT_DIGITS = 15;
  private static final int GROUP = 3;
  private static final int CENTS = 2;

  private static final Set<String> EXTRA_CODES =
      Set.of(
          "JPY", "CHF", "CAD", "AUD", "NZD", "CNY", "INR", "TRY", "RUB", "KRW", "SEK", "NOK", "DKK",
          "PLN", "CZK", "HUF", "ZAR", "BRL", "MXN", "SGD", "HKD", "THB", "MYR", "IDR", "PHP", "QAR",
          "BHD", "OMR", "JOD", "LBP", "MAD", "TND", "DZD", "ILS");

  private TextNormalizer() {}

  /**
   * NFKC, Arabic-Indic and Persian digits to Western, Arabic separators to ASCII, removal of
   * tatweel, harakat, zero-width and bidi control characters, whitespace collapsed.
   */
  public static String normalize(String input) {
    String current = input;
    for (int pass = 0; pass < MAX_PASSES; pass++) {
      String next = pass(current);
      if (next.equals(current)) {
        return next;
      }
      current = next;
    }
    return current;
  }

  private static String pass(String text) {
    String folded = Normalizer.normalize(truncate(text), Normalizer.Form.NFKC);
    StringBuilder out = new StringBuilder(folded.length());
    boolean lastSpace = true;
    for (int i = 0; i < folded.length(); i++) {
      char mapped = map(folded.charAt(i));
      if (mapped == 0) {
        continue;
      }
      if (Character.isWhitespace(mapped) || Character.isSpaceChar(mapped)) {
        if (!lastSpace) {
          out.append(' ');
        }
        lastSpace = true;
      } else {
        out.append(mapped);
        lastSpace = false;
      }
    }
    int end = out.length();
    if (end > 0 && out.charAt(end - 1) == ' ') {
      out.setLength(end - 1);
    }
    return truncate(Normalizer.normalize(out, Normalizer.Form.NFKC));
  }

  private static String truncate(String text) {
    if (text.codePointCount(0, text.length()) <= MAX_LENGTH) {
      return text;
    }
    return text.substring(0, text.offsetByCodePoints(0, MAX_LENGTH));
  }

  /**
   * Maps one char; 0 means "drop it". Code points are numeric on purpose: no invisible literals.
   */
  private static char map(char c) {
    if (c >= 0x0660 && c <= 0x0669) {
      return (char) ('0' + (c - 0x0660));
    }
    if (c >= 0x06F0 && c <= 0x06F9) {
      return (char) ('0' + (c - 0x06F0));
    }
    return switch (c) {
      case 0x066B -> '.';
      case 0x066C, 0x060C -> ',';
      case 0x066A -> '%';
      // tatweel, BOM, zero-width space/joiners, word joiner, LRM, RLM, Arabic letter mark
      case 0x0640, 0xFEFF, 0x200B, 0x200C, 0x200D, 0x2060, 0x200E, 0x200F, 0x061C -> 0;
      default -> mapOther(c);
    };
  }

  private static char mapOther(char c) {
    boolean harakat = (c >= 0x064B && c <= 0x065F) || c == 0x0670 || (c >= 0x06D6 && c <= 0x06ED);
    boolean bidi = (c >= 0x202A && c <= 0x202E) || (c >= 0x2066 && c <= 0x2069);
    if (harakat || bidi) {
      return 0;
    }
    if ((c >= 0x2010 && c <= 0x2015) || c == 0x2212 || c == 0xFE58 || c == 0xFE63) {
      return '-'; // every Unicode dash and the minus sign: a typographic hyphen equals ASCII
    }
    if (c == 0x2024 || c == 0x2027) {
      return '.'; // one-dot leader and hyphenation point
    }
    return c == 0x00A0 || c == 0x202F ? ' ' : c;
  }

  /**
   * Letter folding for matching only, same length as the input so match offsets still index the
   * display text: alef variants to alef, final yaa to yaa, taa marbuta to haa, Persian kaf to
   * Arabic kaf.
   */
  public static String fold(String normalized) {
    char[] chars = normalized.toCharArray();
    for (int i = 0; i < chars.length; i++) {
      chars[i] =
          switch (chars[i]) {
            case 'أ', 'إ', 'آ', 'ٱ' -> 'ا';
            case 'ى', 'ی' -> 'ي';
            case 'ة' -> 'ه';
            case 'ک' -> 'ك';
            default -> chars[i];
          };
    }
    return new String(chars);
  }

  /** Lower-cases with the root locale (never the default locale). */
  public static String lower(String text) {
    return text.toLowerCase(Locale.ROOT);
  }

  /**
   * Parses a number as printed in a message ({@code 1,250}, {@code 1.250,00}, {@code 1,250.50},
   * {@code 12,5}, {@code 1.250}) into minor units (two decimals). Digits must already be Western
   * (see {@link #normalize}). Empty when the text is not a clean number or has more than two
   * decimals; ambiguous single-separator forms follow the Egyptian habit: three digits after one
   * separator means thousands.
   */
  public static OptionalLong parseMinorUnits(String raw) {
    String s = trimSeparators(raw.strip());
    if (s.isEmpty() || s.length() > MAX_AMOUNT_DIGITS + 4) {
      return OptionalLong.empty();
    }
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if ((c < '0' || c > '9') && c != '.' && c != ',') {
        return OptionalLong.empty();
      }
    }
    int lastDot = s.lastIndexOf('.');
    int lastComma = s.lastIndexOf(',');
    int decimalAt;
    if (lastDot >= 0 && lastComma >= 0) {
      decimalAt = Math.max(lastDot, lastComma);
    } else if (lastDot >= 0 || lastComma >= 0) {
      decimalAt = singleSeparatorDecimal(s, Math.max(lastDot, lastComma));
    } else {
      decimalAt = -1;
    }
    if (decimalAt == -2) {
      return OptionalLong.empty();
    }
    String integerPart = decimalAt < 0 ? s : s.substring(0, decimalAt);
    String decimalPart = decimalAt < 0 ? "" : s.substring(decimalAt + 1);
    String digits = integerPart.replace(".", "").replace(",", "");
    if (decimalPart.length() > CENTS
        || !validGrouping(integerPart)
        || digits.isEmpty()
        || digits.length() > MAX_AMOUNT_DIGITS
        || !allDigits(decimalPart)) {
      return OptionalLong.empty();
    }
    String cents = (decimalPart + "00").substring(0, CENTS);
    return OptionalLong.of(Long.parseLong(digits) * 100 + Long.parseLong(cents));
  }

  /** Index of the decimal separator for one kind of separator; -1 none (thousands); -2 invalid. */
  private static int singleSeparatorDecimal(String s, int last) {
    char sep = s.charAt(last);
    int count = 0;
    for (int i = 0; i < s.length(); i++) {
      if (s.charAt(i) == sep) {
        count++;
      }
    }
    int after = s.length() - last - 1;
    if (count > 1) {
      return -1;
    }
    if (after == GROUP && last >= 1 && last <= GROUP && s.charAt(0) != '0') {
      return -1;
    }
    return after >= 1 && after <= CENTS ? last : -2;
  }

  private static boolean validGrouping(String integerPart) {
    if (integerPart.isEmpty()) {
      return false;
    }
    String[] groups = integerPart.split("[.,]", -1);
    if (groups.length == 1) {
      return allDigits(groups[0]);
    }
    if (groups[0].isEmpty() || groups[0].length() > GROUP || !allDigits(groups[0])) {
      return false;
    }
    for (int i = 1; i < groups.length; i++) {
      if (groups[i].length() != GROUP || !allDigits(groups[i])) {
        return false;
      }
    }
    return true;
  }

  private static boolean allDigits(String s) {
    for (int i = 0; i < s.length(); i++) {
      if (s.charAt(i) < '0' || s.charAt(i) > '9') {
        return false;
      }
    }
    return true;
  }

  private static String trimSeparators(String s) {
    int end = s.length();
    while (end > 0 && (s.charAt(end - 1) == '.' || s.charAt(end - 1) == ',')) {
      end--;
    }
    return s.substring(0, end);
  }

  /**
   * Applies a magnitude word to an amount: {@code k}/{@code K}/{@code ك} and {@code الف}/{@code
   * ألف}/{@code آلاف}/{@code الاف} multiply by 1000. Empty when the word is not a multiplier or the
   * result overflows.
   */
  public static OptionalLong applyMultiplier(long minor, String word) {
    String w = fold(lower(word.strip()));
    boolean thousand =
        w.equals("k") || w.equals("ك") || w.equals("الف") || w.equals("الاف") || w.equals("االاف");
    if (!thousand) {
      return OptionalLong.empty();
    }
    try {
      return OptionalLong.of(Math.multiplyExact(minor, 1000L));
    } catch (ArithmeticException e) {
      return OptionalLong.empty();
    }
  }

  /** ISO-4217 code for a currency word or symbol. */
  public static Optional<String> currencyCode(String token) {
    String t = fold(lower(token.strip())).replace(".", "").replace(" ", "");
    return Optional.ofNullable(
        switch (t) {
          case "egp", "le", "e£", "جنيه", "جنيها", "جنيهات", "جم", "جنيهه" -> "EGP";
          case "usd", "us$", "$", "دولار" -> "USD";
          case "eur", "€", "يورو" -> "EUR";
          case "sar", "ريال" -> "SAR";
          case "aed", "درهم" -> "AED";
          case "gbp" -> "GBP";
          case "kwd" -> "KWD";
          default ->
              EXTRA_CODES.contains(t.toUpperCase(Locale.ROOT)) ? t.toUpperCase(Locale.ROOT) : null;
        });
  }
}
