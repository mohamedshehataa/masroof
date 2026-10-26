package eg.masroof.domain.parsing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.OptionalLong;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class TextNormalizerTest {

  /** Builds a string from code units; keeps invisible characters out of the source. */
  private static String invisible(int... units) {
    StringBuilder sb = new StringBuilder();
    for (int u : units) {
      sb.append((char) u);
    }
    return sb.toString();
  }

  @Test
  @Tag("REQ-PARSE-010")
  @DisplayName(
      "REQ-PARSE-010 Arabic-Indic and Persian digits become Western, mixed scripts included")
  void digits() {
    assertThat(TextNormalizer.normalize("بنزين ٢٠٠")).isEqualTo("بنزين 200");
    assertThat(TextNormalizer.normalize("۱۲۳ ٢5٠")).isEqualTo("123 250");
    assertThat(TextNormalizer.normalize("１２")).isEqualTo("12");
  }

  @Test
  @Tag("REQ-PARSE-010")
  @DisplayName("REQ-PARSE-010 bidi marks, tatweel, harakat, zero-width and odd spaces are removed")
  void strips() {
    assertThat(
            TextNormalizer.normalize(
                invisible(0x200F)
                    + "جنـيـه"
                    + invisible(0x200E, 0x202A)
                    + " 5"
                    + invisible(0x202C)))
        .isEqualTo("جنيه 5");
    assertThat(TextNormalizer.normalize("مَبْلَغ" + invisible(0x200B))).isEqualTo("مبلغ");
    assertThat(TextNormalizer.normalize("a   b\r\n\tc  ")).isEqualTo("a b c");
    assertThat(TextNormalizer.normalize("   ")).isEmpty();
  }

  @Test
  @Tag("REQ-PARSE-010")
  @DisplayName("REQ-PARSE-010 presentation forms are folded (NFKC)")
  void nfkc() {
    assertThat(TextNormalizer.normalize("ﻻ")).isEqualTo("لا");
  }

  @Test
  @Tag("REQ-PARSE-010")
  @DisplayName("REQ-PARSE-010 input is bounded and never throws on lone surrogates")
  void bounded() {
    String huge = "a".repeat(TextNormalizer.MAX_LENGTH * 3);
    assertThat(TextNormalizer.normalize(huge)).hasSize(TextNormalizer.MAX_LENGTH);
    assertThat(TextNormalizer.normalize("x\uD83D")).startsWith("x");
    String emoji = "😀".repeat(TextNormalizer.MAX_LENGTH + 5);
    assertThat(
            TextNormalizer.normalize(emoji)
                .codePointCount(0, TextNormalizer.normalize(emoji).length()))
        .isEqualTo(TextNormalizer.MAX_LENGTH);
  }

  @Test
  @Tag("REQ-PARSE-010")
  @DisplayName("REQ-PARSE-010 normalization is idempotent on random hostile input")
  void idempotent() {
    String pool =
        "ab 12"
            + invisible(0x0660, 0x0669, 0x06F5, 0x066B, 0x066C, 0x060C)
            + ".,"
            + invisible(0x0640, 0x064B, 0x200F, 0x202A, 0xFEFB, 0xFE71, 0x00A0, 0x00A8, 0x0301)
            + "أةى$€"
            + invisible(0xD83D, 0xDE00, 0xD800);
    Random random = new Random(20261025);
    for (int i = 0; i < 3000; i++) {
      StringBuilder sb = new StringBuilder();
      int length = random.nextInt(40);
      for (int j = 0; j < length; j++) {
        sb.append(pool.charAt(random.nextInt(pool.length())));
      }
      String once = TextNormalizer.normalize(sb.toString());
      assertThat(TextNormalizer.normalize(once)).isEqualTo(once);
    }
  }

  @Test
  @Tag("REQ-PARSE-010")
  @DisplayName("REQ-PARSE-010 Arabic-Indic digits and Western digits give the same amount")
  void arabicIndicEquivalence() {
    Random random = new Random(7);
    for (int i = 0; i < 500; i++) {
      long value = random.nextLong(1, 10_000_000);
      String western = Long.toString(value) + "." + String.format("%02d", random.nextInt(100));
      StringBuilder arabic = new StringBuilder();
      for (int k = 0; k < western.length(); k++) {
        char c = western.charAt(k);
        arabic.append(c == '.' ? (char) 0x066B : (char) (0x0660 + (c - '0')));
      }
      assertThat(TextNormalizer.normalize(arabic.toString())).isEqualTo(western);
      assertThat(TextNormalizer.parseMinorUnits(TextNormalizer.normalize(arabic.toString())))
          .isEqualTo(TextNormalizer.parseMinorUnits(western));
    }
  }

  @Test
  @Tag("REQ-PARSE-011")
  @DisplayName("REQ-PARSE-011 thousands and decimal separators are unified")
  void separators() {
    assertThat(TextNormalizer.parseMinorUnits("1,250")).hasValue(125_000);
    assertThat(TextNormalizer.parseMinorUnits("1,250.50")).hasValue(125_050);
    assertThat(TextNormalizer.parseMinorUnits("1.250,00")).hasValue(125_000);
    assertThat(TextNormalizer.parseMinorUnits("1.250")).hasValue(125_000);
    assertThat(TextNormalizer.parseMinorUnits("1.25")).hasValue(125);
    assertThat(TextNormalizer.parseMinorUnits("12,5")).hasValue(1250);
    assertThat(TextNormalizer.parseMinorUnits("250")).hasValue(25_000);
    assertThat(TextNormalizer.parseMinorUnits("1,234,567.89")).hasValue(123_456_789);
    assertThat(TextNormalizer.parseMinorUnits("1.234.567,89")).hasValue(123_456_789);
    assertThat(TextNormalizer.parseMinorUnits("250.00.")).hasValue(25_000);
    assertThat(TextNormalizer.parseMinorUnits(TextNormalizer.normalize("١٬٢٥٠"))).hasValue(125_000);
  }

  @Test
  @Tag("REQ-PARSE-011")
  @DisplayName("REQ-PARSE-011 malformed or ambiguous numbers are rejected, never guessed")
  void rejects() {
    for (String bad :
        new String[] {
          "",
          ".",
          "abc",
          "1,2,3",
          "1.2.3",
          "12,34,567",
          "0.500",
          "1.2345",
          "1,5.00",
          "-5",
          "1 2",
          "9999999999999999",
          "1,25,0",
          ",500",
          "1..5"
        }) {
      assertThat(TextNormalizer.parseMinorUnits(bad)).as(bad).isEqualTo(OptionalLong.empty());
    }
  }

  @Test
  @Tag("REQ-PARSE-012")
  @DisplayName("REQ-PARSE-012 k and الف multiply by one thousand")
  void multipliers() {
    assertThat(TextNormalizer.applyMultiplier(500, "k")).hasValue(500_000);
    assertThat(TextNormalizer.applyMultiplier(250, "K")).hasValue(250_000);
    assertThat(TextNormalizer.applyMultiplier(250, "ألف")).hasValue(250_000);
    assertThat(TextNormalizer.applyMultiplier(250, "الف")).hasValue(250_000);
    assertThat(TextNormalizer.applyMultiplier(250, "ك")).hasValue(250_000);
    assertThat(TextNormalizer.applyMultiplier(250, "آلاف")).hasValue(250_000);
    assertThat(TextNormalizer.applyMultiplier(250, "كيلو")).isEmpty();
    assertThat(TextNormalizer.applyMultiplier(Long.MAX_VALUE, "k")).isEmpty();
  }

  @Test
  @Tag("REQ-PARSE-013")
  @DisplayName("REQ-PARSE-013 currency words and symbols map to ISO codes")
  void currencies() {
    for (String egp :
        new String[] {"EGP", "egp", "LE", "L.E.", "E£", "جنيه", "جنيها", "ج.م", "جم", "جنية"}) {
      assertThat(TextNormalizer.currencyCode(egp)).as(egp).hasValue("EGP");
    }
    assertThat(TextNormalizer.currencyCode("$")).hasValue("USD");
    assertThat(TextNormalizer.currencyCode("دولار")).hasValue("USD");
    assertThat(TextNormalizer.currencyCode("US$")).hasValue("USD");
    assertThat(TextNormalizer.currencyCode("€")).hasValue("EUR");
    assertThat(TextNormalizer.currencyCode("يورو")).hasValue("EUR");
    assertThat(TextNormalizer.currencyCode("ريال")).hasValue("SAR");
    assertThat(TextNormalizer.currencyCode("درهم")).hasValue("AED");
    assertThat(TextNormalizer.currencyCode("GBP")).hasValue("GBP");
    assertThat(TextNormalizer.currencyCode("KWD")).hasValue("KWD");
    assertThat(TextNormalizer.currencyCode("zloty")).isEmpty();
  }

  @Test
  @Tag("REQ-PARSE-010")
  @DisplayName("REQ-PARSE-010 folding keeps the length so match offsets index the display text")
  void foldKeepsLength() {
    String text = "أإآٱ ى ة ک ABC";
    String folded = TextNormalizer.fold(text);
    assertThat(folded).hasSameSizeAs(text);
    assertThat(folded).isEqualTo("اااا ي ه ك ABC");
    assertThat(TextNormalizer.lower("ABC İ")).isEqualTo("abc i̇");
  }
}
