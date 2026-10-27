package eg.masroof.adapters.out.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The signer against the examples in the AWS S3 Signature Version 4 documentation. */
class SigV4SignerTest {

  // The public example credentials from the AWS documentation (split so secret scanners stay
  // quiet).
  private static final String ACCESS_KEY = "AKIA" + "IOSFODNN7EXAMPLE";
  private static final String SECRET_KEY = "wJalrXUtnFEMI/K7MDENG/" + "bPxRfiCYEXAMPLEKEY";
  private static final SigV4Signer SIGNER = new SigV4Signer(ACCESS_KEY, SECRET_KEY, "us-east-1");
  private static final Instant NOW = Instant.parse("2013-05-24T00:00:00Z");
  private static final String EMPTY_HASH =
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

  @Test
  @Tag("REQ-RCPT-023")
  @DisplayName("REQ-RCPT-023 AWS example: GET Object with a Range header")
  void getObjectVector() {
    String auth =
        SIGNER.authorization(
            "GET",
            "/test.txt",
            "",
            Map.of(
                "Host", "examplebucket.s3.amazonaws.com",
                "Range", "bytes=0-9",
                "x-amz-content-sha256", EMPTY_HASH,
                "x-amz-date", "20130524T000000Z"),
            EMPTY_HASH,
            NOW);
    assertThat(auth)
        .isEqualTo(
            "AWS4-HMAC-SHA256 Credential="
                + ACCESS_KEY
                + "/20130524/us-east-1/s3/aws4_request,"
                + " SignedHeaders=host;range;x-amz-content-sha256;x-amz-date,"
                + " Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41");
  }

  @Test
  @Tag("REQ-RCPT-023")
  @DisplayName("REQ-RCPT-023 AWS example: PUT Object with an encoded key and a body")
  void putObjectVector() {
    String body = "Welcome to Amazon S3.";
    String hash = SigV4Signer.sha256Hex(body.getBytes(StandardCharsets.UTF_8));
    assertThat(hash).isEqualTo("44ce7dd67c959e0d3524ffac1771dfbba87d2b6b4b4e99e42034a8b803f8b072");
    assertThat(S3AttachmentStore.encodePath("/test$file.text")).isEqualTo("/test%24file.text");
    String auth =
        SIGNER.authorization(
            "PUT",
            "/test%24file.text",
            "",
            Map.of(
                "date", "Fri, 24 May 2013 00:00:00 GMT",
                "host", "examplebucket.s3.amazonaws.com",
                "x-amz-content-sha256", hash,
                "x-amz-date", "20130524T000000Z",
                "x-amz-storage-class", "REDUCED_REDUNDANCY"),
            hash,
            NOW);
    assertThat(auth)
        .endsWith("Signature=98ad721746da40c64f1a55b78f14c238d841ea1380cd77a1b5971af0ece108bd")
        .contains("SignedHeaders=date;host;x-amz-content-sha256;x-amz-date;x-amz-storage-class");
  }

  @Test
  @Tag("REQ-RCPT-023")
  @DisplayName("REQ-RCPT-023 the date header form is yyyyMMdd'T'HHmmss'Z' in UTC")
  void amzDate() {
    assertThat(SigV4Signer.amzDate(NOW)).isEqualTo("20130524T000000Z");
  }
}
