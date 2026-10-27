package eg.masroof.domain.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Magic-byte sniffing and metadata stripping on crafted files (REQ-RCPT-021, REQ-RCPT-022). */
class MetadataStripperTest {

  private static BufferedImage image() {
    BufferedImage img = new BufferedImage(16, 12, BufferedImage.TYPE_INT_RGB);
    for (int x = 0; x < 16; x++) {
      for (int y = 0; y < 12; y++) {
        img.setRGB(x, y, (x * 16) << 16 | (y * 20) << 8 | 77);
      }
    }
    return img;
  }

  private static byte[] encode(String format) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(image(), format, out);
    return out.toByteArray();
  }

  private static byte[] ascii(String s) {
    return s.getBytes(StandardCharsets.ISO_8859_1);
  }

  private static byte[] concat(byte[]... parts) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] p : parts) {
      out.writeBytes(p);
    }
    return out.toByteArray();
  }

  private static byte[] jpegSegment(int marker, byte[] payload) {
    int len = payload.length + 2;
    return concat(new byte[] {(byte) 0xFF, (byte) marker, (byte) (len >> 8), (byte) len}, payload);
  }

  /** Inserts segments right after SOI (and after JFIF APP0 when present, as cameras do). */
  private static byte[] jpegWith(byte[] jpeg, byte[]... segments) {
    return concat(
        Arrays.copyOfRange(jpeg, 0, 2), concat(segments), Arrays.copyOfRange(jpeg, 2, jpeg.length));
  }

  private static boolean contains(byte[] haystack, byte[] needle) {
    for (int i = 0; i + needle.length <= haystack.length; i++) {
      if (Arrays.equals(Arrays.copyOfRange(haystack, i, i + needle.length), needle)) {
        return true;
      }
    }
    return false;
  }

  private static int[] pixels(byte[] file) throws IOException {
    BufferedImage img = ImageIO.read(new ByteArrayInputStream(file));
    assertThat(img).isNotNull();
    return img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
  }

  @Test
  @Tag("REQ-RCPT-021")
  @DisplayName("REQ-RCPT-021 the type comes from the leading bytes only")
  void sniffs() {
    assertThat(AttachmentType.sniff(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0}))
        .contains(AttachmentType.JPEG);
    assertThat(
            AttachmentType.sniff(
                concat(new byte[] {(byte) 0x89}, ascii("PNG\r\n"), new byte[] {0x1A, 0x0A})))
        .contains(AttachmentType.PNG);
    assertThat(AttachmentType.sniff(ascii("%PDF-1.7\n"))).contains(AttachmentType.PDF);
    for (String other :
        new String[] {
          "GIF89a....", "<html>", "<svg xmlns", "PK\u0003\u0004", "hello.jpg", "", "%PDF"
        }) {
      assertThat(AttachmentType.sniff(ascii(other))).as(other).isEmpty();
    }
    assertThat(AttachmentType.sniff(new byte[] {(byte) 0xFF, (byte) 0xD8})).isEmpty();
  }

  @Test
  @Tag("REQ-RCPT-022")
  @DisplayName("REQ-RCPT-022 JPEG: Exif with GPS, XMP, IPTC, comments and other APPn are removed")
  void jpegMetadataRemoved() throws IOException {
    byte[] plain = encode("jpg");
    byte[] gps = ascii("GPSLatitudeRef-N-30.0444-GPSLongitude-31.2357");
    byte[] dirty =
        jpegWith(
            plain,
            jpegSegment(0xE1, concat(ascii("Exif\0\0"), gps)),
            jpegSegment(
                0xE1, concat(ascii("http://ns.adobe.com/xap/1.0/\0"), ascii("<x:xmpmeta/>"))),
            jpegSegment(0xED, concat(ascii("Photoshop 3.0\0"), ascii("IPTC-caption"))),
            jpegSegment(0xFE, ascii("secret comment")),
            jpegSegment(0xE3, ascii("app3")),
            jpegSegment(0xEF, ascii("app15")),
            jpegSegment(0xE2, concat(ascii("ICC_PROFILE\0"), new byte[] {1, 1, 9, 9})));
    assertThat(contains(dirty, ascii("Exif\0\0"))).isTrue();

    byte[] clean = MetadataStripper.strip(AttachmentType.JPEG, dirty);

    assertThat(contains(clean, ascii("Exif"))).isFalse();
    assertThat(contains(clean, gps)).isFalse();
    assertThat(contains(clean, ascii("ns.adobe.com"))).isFalse();
    assertThat(contains(clean, ascii("IPTC"))).isFalse();
    assertThat(contains(clean, ascii("secret comment"))).isFalse();
    assertThat(contains(clean, ascii("app3"))).isFalse();
    assertThat(contains(clean, ascii("app15"))).isFalse();
    assertThat(contains(clean, ascii("ICC_PROFILE\0"))).isTrue();
    assertThat(clean[0]).isEqualTo((byte) 0xFF);
    assertThat(clean[1]).isEqualTo((byte) 0xD8);
    assertThat(clean[clean.length - 2]).isEqualTo((byte) 0xFF);
    assertThat(clean[clean.length - 1]).isEqualTo((byte) 0xD9);
    assertThat(contains(clean, ascii("JFIF\0"))).isTrue();
    assertThat(pixels(clean)).isEqualTo(pixels(plain));
  }

  @Test
  @Tag("REQ-RCPT-022")
  @DisplayName("REQ-RCPT-022 JPEG: bytes hidden after the JFIF header in APP0 are removed")
  void jpegApp0TailRemoved() throws IOException {
    byte[] plain = encode("jpg");
    byte[] jfifHeader = concat(ascii("JFIF\0"), new byte[] {1, 2, 0, 0, 1, 0, 1, 0, 0});
    byte[] dirty =
        jpegWith(
            plain,
            jpegSegment(0xE0, concat(jfifHeader, ascii("Exif\0\0GPS-30.0444N"))),
            jpegSegment(0xE0, ascii("JFXX\0thumbnail-bytes")));
    assertThat(contains(dirty, ascii("GPS-30.0444N"))).isTrue();

    byte[] clean = MetadataStripper.strip(AttachmentType.JPEG, dirty);

    assertThat(contains(clean, ascii("Exif"))).isFalse();
    assertThat(contains(clean, ascii("GPS-30.0444N"))).isFalse();
    assertThat(contains(clean, ascii("thumbnail-bytes"))).isFalse();
    assertThat(contains(clean, ascii("JFIF\0"))).isTrue();
    assertThat(pixels(clean)).isEqualTo(pixels(plain));
  }

  @Test
  @Tag("REQ-RCPT-022")
  @DisplayName("REQ-RCPT-022 JPEG: bytes after EOI are dropped, a clean file is unchanged")
  void jpegTrailingDataAndCleanFile() throws IOException {
    byte[] plain = MetadataStripper.strip(AttachmentType.JPEG, encode("jpg"));
    assertThat(MetadataStripper.strip(AttachmentType.JPEG, plain)).isEqualTo(plain);
    byte[] withTail = concat(plain, ascii("Exif\0\0hidden trailer"));
    assertThat(MetadataStripper.strip(AttachmentType.JPEG, withTail)).isEqualTo(plain);
  }

  @Test
  @Tag("REQ-RCPT-022")
  @DisplayName("REQ-RCPT-022 JPEG: a truncated or inconsistent structure is rejected")
  void jpegMalformed() throws IOException {
    byte[] plain = encode("jpg");
    byte[] truncated = Arrays.copyOf(plain, plain.length / 2);
    assertThatThrownBy(() -> MetadataStripper.strip(AttachmentType.JPEG, truncated))
        .isInstanceOf(MetadataStripper.MalformedImageException.class);
    byte[] noEoi = Arrays.copyOf(plain, plain.length - 2);
    assertThatThrownBy(() -> MetadataStripper.strip(AttachmentType.JPEG, noEoi))
        .isInstanceOf(MetadataStripper.MalformedImageException.class);
    byte[] badLength = jpegWith(plain, new byte[] {(byte) 0xFF, (byte) 0xE1, 0x7F, (byte) 0xFF, 1});
    assertThatThrownBy(() -> MetadataStripper.strip(AttachmentType.JPEG, badLength))
        .isInstanceOf(MetadataStripper.MalformedImageException.class);
    byte[] garbage = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00, 1, 2, 3};
    assertThatThrownBy(() -> MetadataStripper.strip(AttachmentType.JPEG, garbage))
        .isInstanceOf(MetadataStripper.MalformedImageException.class);
  }

  private static byte[] pngChunk(String type, byte[] data) {
    CRC32 crc = new CRC32();
    crc.update(ascii(type));
    crc.update(data);
    long c = crc.getValue();
    return concat(
        new byte[] {
          (byte) (data.length >> 24),
          (byte) (data.length >> 16),
          (byte) (data.length >> 8),
          (byte) data.length
        },
        ascii(type),
        data,
        new byte[] {(byte) (c >> 24), (byte) (c >> 16), (byte) (c >> 8), (byte) c});
  }

  /** Inserts chunks right after IHDR (8 signature + 25 for the 13-byte IHDR chunk). */
  private static byte[] pngWith(byte[] png, byte[]... chunks) {
    return concat(
        Arrays.copyOfRange(png, 0, 33), concat(chunks), Arrays.copyOfRange(png, 33, png.length));
  }

  @Test
  @Tag("REQ-RCPT-022")
  @DisplayName(
      "REQ-RCPT-022 PNG: eXIf, tEXt, zTXt, iTXt and tIME chunks are removed, the rest stays valid")
  void pngMetadataRemoved() throws IOException {
    byte[] plain = encode("png");
    byte[] dirty =
        pngWith(
            plain,
            pngChunk("eXIf", ascii("Exif-GPS-30.04N")),
            pngChunk("tEXt", ascii("Comment\0secret-text")),
            pngChunk("zTXt", ascii("k\0\0zz")),
            pngChunk("iTXt", ascii("k\0\0\0\0\0xmp-data")),
            pngChunk("tIME", new byte[] {7, (byte) 0xEA, 10, 27, 1, 2, 3}));

    byte[] clean = MetadataStripper.strip(AttachmentType.PNG, dirty);

    assertThat(clean).isEqualTo(plain);
    assertThat(contains(clean, ascii("secret-text"))).isFalse();
    assertThat(contains(clean, ascii("IDAT"))).isTrue();
    assertThat(pixels(clean)).isEqualTo(pixels(plain));
  }

  @Test
  @Tag("REQ-RCPT-022")
  @DisplayName("REQ-RCPT-022 PNG: private and unknown ancillary chunks are removed (allow-list)")
  void pngPrivateChunksRemoved() throws IOException {
    byte[] plain = encode("png");
    byte[] dirty =
        pngWith(
            plain,
            pngChunk("prVt", ascii("Exif-GPS-30.04N")),
            pngChunk("caBX", ascii("c2pa-manifest")),
            pngChunk("orNT", new byte[] {1}));

    byte[] clean = MetadataStripper.strip(AttachmentType.PNG, dirty);

    assertThat(contains(clean, ascii("GPS-30.04N"))).isFalse();
    assertThat(contains(clean, ascii("c2pa-manifest"))).isFalse();
    assertThat(clean).isEqualTo(plain);
    assertThat(pixels(clean)).isEqualTo(pixels(plain));
  }

  @Test
  @Tag("REQ-RCPT-022")
  @DisplayName("REQ-RCPT-022 PNG: a truncated chunk structure is rejected")
  void pngMalformed() throws IOException {
    byte[] plain = encode("png");
    byte[] truncated = Arrays.copyOf(plain, plain.length - 5);
    assertThatThrownBy(() -> MetadataStripper.strip(AttachmentType.PNG, truncated))
        .isInstanceOf(MetadataStripper.MalformedImageException.class);
    byte[] noIhdr =
        concat(
            Arrays.copyOfRange(plain, 0, 8),
            pngChunk("tEXt", ascii("a\0b")),
            pngChunk("IEND", new byte[0]));
    assertThatThrownBy(() -> MetadataStripper.strip(AttachmentType.PNG, noIhdr))
        .isInstanceOf(MetadataStripper.MalformedImageException.class);
    byte[] hugeLength = plain.clone();
    hugeLength[33] = 0x7F;
    assertThatThrownBy(() -> MetadataStripper.strip(AttachmentType.PNG, hugeLength))
        .isInstanceOf(MetadataStripper.MalformedImageException.class);
  }

  @Test
  @Tag("REQ-RCPT-022")
  @DisplayName("REQ-RCPT-022 PDF bytes pass through untouched")
  void pdfPassesThrough() {
    byte[] pdf = ascii("%PDF-1.4\n1 0 obj<<>>endobj\n%%EOF\n");
    assertThat(MetadataStripper.strip(AttachmentType.PDF, pdf)).isEqualTo(pdf);
  }
}
