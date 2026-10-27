package eg.masroof.adapters.in.rest;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.imageio.ImageIO;

/** Small real images for upload tests. */
final class TestImages {

  static final String GPS = "GPSLatitude-30.0444-GPSLongitude-31.2357";

  private TestImages() {}

  static byte[] ascii(String s) {
    return s.getBytes(StandardCharsets.ISO_8859_1);
  }

  static byte[] png() {
    return encode("png");
  }

  /** A JPEG with an Exif block carrying GPS bytes and a comment right after SOI. */
  static byte[] jpegWithExif() {
    byte[] plain = encode("jpg");
    byte[] exif = segment(0xE1, concat(ascii("Exif\0\0"), ascii(GPS)));
    byte[] comment = segment(0xFE, ascii("owner-comment"));
    return concat(
        Arrays.copyOfRange(plain, 0, 2), exif, comment, Arrays.copyOfRange(plain, 2, plain.length));
  }

  /** A PDF of exactly {@code size} bytes. */
  static byte[] pdf(int size) {
    byte[] head = ascii("%PDF-1.4\n");
    byte[] tail = ascii("\n%%EOF\n");
    byte[] out = new byte[size];
    Arrays.fill(out, (byte) ' ');
    System.arraycopy(head, 0, out, 0, head.length);
    System.arraycopy(tail, 0, out, size - tail.length, tail.length);
    return out;
  }

  static boolean contains(byte[] haystack, String needle) {
    byte[] n = ascii(needle);
    for (int i = 0; i + n.length <= haystack.length; i++) {
      if (Arrays.equals(Arrays.copyOfRange(haystack, i, i + n.length), n)) {
        return true;
      }
    }
    return false;
  }

  private static byte[] segment(int marker, byte[] payload) {
    int len = payload.length + 2;
    return concat(new byte[] {(byte) 0xFF, (byte) marker, (byte) (len >> 8), (byte) len}, payload);
  }

  private static byte[] concat(byte[]... parts) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] p : parts) {
      out.writeBytes(p);
    }
    return out.toByteArray();
  }

  private static byte[] encode(String format) {
    BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
    for (int x = 0; x < 8; x++) {
      for (int y = 0; y < 8; y++) {
        img.setRGB(x, y, ((x * 30) << 16) | ((y * 30) << 8) | 90);
      }
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try {
      ImageIO.write(img, format, out);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out.toByteArray();
  }
}
