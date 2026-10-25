package eg.masroof.adapters.out.parsing;

import static org.assertj.core.api.Assertions.assertThat;

import eg.masroof.domain.expense.PaymentMethod;
import eg.masroof.domain.money.Money;
import eg.masroof.domain.parsing.MessageClassifier;
import eg.masroof.domain.parsing.MessageClassifier.MessageClass;
import eg.masroof.domain.parsing.ParsedSms;
import eg.masroof.domain.parsing.SmsKind;
import eg.masroof.domain.parsing.SmsTemplateEngine;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

/** Regression tests for the verifier findings on the SMS rules engine and OTP rule. */
class SmsEngineHardeningTest {

  private static final Instant RECEIVED = Instant.parse("2026-10-26T10:00:00Z");

  private static SmsTemplateEngine engine() throws IOException {
    try (InputStream in =
        new ClassPathResource(SmsTemplateConfiguration.RESOURCE).getInputStream()) {
      return SmsTemplateConfiguration.load(JsonMapper.builder().build().readTree(in));
    }
  }

  private static ParsedSms parse(String sender, String body) throws IOException {
    return engine().parse(sender, body, RECEIVED).orElseThrow();
  }

  @Test
  @Tag("REQ-SMS-030")
  @DisplayName("REQ-SMS-030 an OTP is recognised by structure, not just by keyword")
  void structuralOtp() {
    for (String body :
        new String[] {
          "Your 3D Secure code for EGP 64.00 at EMU WEBSHOP is 556677",
          "Your activation code: 90817",
          "482915 is your verification number",
          "Your O.T.P. is 334455",
          "Use this login code 771204 to sign in",
          "Approve your payment of EGP 120.00 at NOON with 4455",
          "كود التفعيل 5521 لتسجيل الدخول",
          "رقم التحقق 884213 لعملية شراء بمبلغ 500.00 جنيه"
        }) {
      assertThat(MessageClassifier.classify(body)).as(body).isEqualTo(MessageClass.OTP);
    }
  }

  @Test
  @Tag("REQ-SMS-030")
  @DisplayName("REQ-SMS-030 every Unicode dash and dot variant is normalized before matching")
  void unicodeDashes() {
    for (int dash : new int[] {0x2010, 0x2011, 0x2012, 0x2013, 0x2014, 0x2212}) {
      String body = "Your one" + (char) dash + "time passcode is ready";
      assertThat(MessageClassifier.classify(body)).as("dash " + dash).isEqualTo(MessageClass.OTP);
    }
    assertThat(
            MessageClassifier.classify(
                "Your o" + (char) 0x2024 + "t" + (char) 0x2024 + "p is here"))
        .isEqualTo(MessageClass.OTP);
  }

  @Test
  @Tag("REQ-SMS-030")
  @DisplayName(
      "REQ-SMS-030 a plain purchase with an amount, a date, a time and a card last four is not an OTP")
  void realPurchaseIsNotOtp() {
    assertThat(
            MessageClassifier.classify(
                "Purchase of EGP 5000 at ZARA, card ending 1234, on 12/03/2026 14:05. Ref 99"))
        .isEqualTo(MessageClass.OTHER);
  }

  @Test
  @Tag("REQ-SMS-012")
  @DisplayName(
      "REQ-SMS-012 a debit never becomes income, even when it also says credited or received")
  void debitWinsOverIncome() throws IOException {
    ParsedSms both =
        parse("CIB", "Your a/c XXXX1234 debited with EGP 2,000.00 and credited to a/c XXXX5678");
    assertThat(both.kind()).isEqualTo(SmsKind.TRANSFER);
    assertThat(both.amount()).isEqualTo(Money.of(200_000, "EGP"));
    ParsedSms paid = parse("CIB", "Your payment of EGP 150.00 to WE was received successfully");
    assertThat(paid.kind()).isEqualTo(SmsKind.PURCHASE);
    assertThat(paid.merchant()).isEqualTo("WE");
    assertThat(parse("InstaPay", "You have received EGP 40.00 from OMAR via InstaPay").kind())
        .isEqualTo(SmsKind.INCOME);
  }

  @Test
  @Tag("REQ-SMS-010")
  @DisplayName("REQ-SMS-010 an explicit non-EGP currency is never defaulted to EGP")
  void foreignCurrency() throws IOException {
    assertThat(parse("CIB", "Purchase amount: 1,500 JPY at UNIQLO TOKYO").amount())
        .isEqualTo(Money.of(150_000, "JPY"));
    assertThat(parse("CIB", "Purchase of CHF 20.00 at MIGROS").amount())
        .isEqualTo(Money.of(2_000, "CHF"));
    ParsedSms unknown = parse("CIB", "Purchase amount 1,500 at SHOP. Total in USD");
    assertThat(unknown.amount()).isEqualTo(Money.of(150_000, "XXX"));
    assertThat(parse("CIB", "Purchase amount: 1,500 at SHOP").amount())
        .isEqualTo(Money.of(150_000, "EGP"));
    assertThat(parse("CIB", "Please try 250 times: purchase of EGP 30.00 at SHOP").amount())
        .isEqualTo(Money.of(3_000, "EGP"));
  }

  @Test
  @Tag("REQ-SMS-010")
  @DisplayName(
      "REQ-SMS-010 IBANs, account and phone numbers are masked to the last four in the merchant")
  void merchantMasking() throws IOException {
    ParsedSms iban =
        parse("NBE", "Transfer of EGP 500.00 to EG380019000500000000263180002 successful");
    assertThat(iban.merchant()).isEqualTo("••0002");
    ParsedSms digits = parse("NBE", "Transfer of EGP 500.00 to 9876543210 successful");
    assertThat(digits.merchant()).isEqualTo("••3210");
    ParsedSms spaced = parse("NBE", "Transfer of EGP 500.00 to 1234 5678 9012 completed");
    assertThat(spaced.merchant()).isEqualTo("••9012");
    ParsedSms named = parse("CIB", "Purchase of EGP 10.00 at CAFE NERO was successful");
    assertThat(named.merchant()).isEqualTo("CAFE NERO");
    assertThat(named.paymentMethod()).isIn(PaymentMethod.CARD, PaymentMethod.UNKNOWN);
  }

  @Test
  @Tag("REQ-SMS-010")
  @DisplayName("REQ-SMS-010 a purchase that mentions a due date or reminder is still a purchase")
  void purchaseWithDueDate() throws IOException {
    ParsedSms p =
        parse("CIB", "Credit card purchase of EGP 750.00 at ZARA on 25/10. Payment due date 05/11");
    assertThat(p.kind()).isEqualTo(SmsKind.PURCHASE);
    assertThat(p.amount()).isEqualTo(Money.of(75_000, "EGP"));
    assertThat(p.merchant()).isEqualTo("ZARA");
    assertThat(parse("CIB", "Reminder: your payment is due on 05/11").kind())
        .isEqualTo(SmsKind.INFO);
  }

  @Test
  @Tag("REQ-SMS-010")
  @DisplayName(
      "REQ-SMS-010 only a recent transaction date is used; validity and old dates fall back to received time")
  void dateWindow() throws IOException {
    assertThat(parse("CIB", "Purchase of EGP 10.00 at X on 01/01/2001").occurredAt())
        .isEqualTo(RECEIVED);
    assertThat(parse("CIB", "Purchase of EGP 10.00 at X. Offer valid until 31/12").occurredAt())
        .isEqualTo(RECEIVED);
    assertThat(parse("CIB", "Purchase of EGP 10.00 at X on 21/10/2026 09:30").occurredAt())
        .isEqualTo(Instant.parse("2026-10-21T06:30:00Z"));
    assertThat(parse("CIB", "Purchase of EGP 10.00 at X on 01/09/2026 09:30").occurredAt())
        .isEqualTo(RECEIVED);
    assertThat(
            parse(
                    "CIB",
                    "Purchase of EGP 10.00 at X valid until 31/12 card used on 25/10/2026 10:00")
                .occurredAt())
        .isEqualTo(Instant.parse("2026-10-25T07:00:00Z"));
  }

  @Test
  @Tag("REQ-SMS-012")
  @DisplayName(
      "REQ-SMS-012 an outgoing transfer that mentions receipt by the beneficiary is not income")
  void outgoingTransferIsNotIncome() throws IOException {
    for (String body :
        new String[] {
          "You have sent EGP 500.00 to Ahmed. The money was received by the beneficiary",
          "Transfer of EGP 500.00 to Ahmed was received successfully",
          "تم تحويل 500 جنيه من حسابك الى حساب Ahmed وتم استلامها بنجاح",
          "تم ارسال 200 جنيه الى Ahmed وتم الاستلام"
        }) {
      assertThat(parse("InstaPay", body).kind()).as(body).isEqualTo(SmsKind.TRANSFER);
    }
    assertThat(parse("InstaPay", "You have received EGP 40.00 from OMAR via InstaPay").kind())
        .isEqualTo(SmsKind.INCOME);
    assertThat(parse("NBE", "You received a transfer of EGP 90.00 from OMAR").kind())
        .isEqualTo(SmsKind.INCOME);
  }

  @Test
  @Tag("REQ-SMS-010")
  @DisplayName("REQ-SMS-010 amount: N with SR, Yen, TL, Dhs or jpy in any case is never EGP")
  void foreignTokensAfterKeywordAmount() throws IOException {
    for (String token : new String[] {"SR", "sr", "Yen", "TL", "tl", "Dhs", "jpy", "JPY"}) {
      ParsedSms p = parse("CIB", "Purchase amount: 1,500 " + token + " at JARIR RIYADH");
      Money amount = java.util.Objects.requireNonNull(p.amount());
      assertThat(amount.currency()).as(token).isNotEqualTo("EGP");
      assertThat(amount.amountMinor()).as(token).isEqualTo(150_000);
    }
    assertThat(parse("CIB", "Purchase amount: 1,500 SR at JARIR").amount())
        .isEqualTo(Money.of(150_000, "SAR"));
    assertThat(parse("CIB", "Purchase amount: 1,500 EGP at JARIR").amount())
        .isEqualTo(Money.of(150_000, "EGP"));
  }

  @Test
  @Tag("REQ-SMS-012")
  @DisplayName("REQ-SMS-012 a card purchase with a cashback-credited tail stays a purchase")
  void cashbackTail() throws IOException {
    ParsedSms p =
        parse(
            "CIB",
            "Your card ending 1234 was debited EGP 500.00 at AMAZON. Cashback of EGP 5.00 will be credited to your account");
    assertThat(p.kind()).isEqualTo(SmsKind.PURCHASE);
    assertThat(p.amount()).isEqualTo(Money.of(50_000, "EGP"));
  }
}
