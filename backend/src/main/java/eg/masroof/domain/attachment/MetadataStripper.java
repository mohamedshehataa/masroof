package eg.masroof.domain.attachment;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Removes location, camera and free-text metadata from JPEG and PNG files by filtering their
 * segment or chunk structure; pixel data is never decoded or re-encoded (REQ-RCPT-022). PDFs pass
 * through unchanged (ADR-0009).
 */
public final class MetadataStripper {

  /** The segment or chunk structure is truncated or inconsistent. */
  public static final class MalformedImageException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    MalformedImageException(String message) {
      super(message);
    }
  }

  private static final Set<String> PNG_DROPPED = Set.of("eXIf", "tEXt", "zTXt", "iTXt", "tIME");
  private static final int PNG_SIGNATURE = 8;

  private MetadataStripper() {}

  /**
   * Returns the file without metadata.
   *
   * @throws MalformedImageException for a JPEG or PNG whose structure cannot be walked to its end
   */
  public static byte[] strip(AttachmentType type, byte[] in) {
    return switch (type) {
      case JPEG -> stripJpeg(in);
      case PNG -> stripPng(in);
      case PDF -> in;
    };
  }

  private static byte[] stripJpeg(byte[] in) {
    ByteArrayOutputStream out = new ByteArrayOutputStream(in.length);
    if (in.length < 4 || u8(in[0]) != 0xFF || u8(in[1]) != 0xD8) {
      throw new MalformedImageException("no SOI");
    }
    out.write(0xFF);
    out.write(0xD8);
    int p = 2;
    while (true) {
      // marker: FF, optional FF fill bytes, then the marker code
      if (p >= in.length || u8(in[p]) != 0xFF) {
        throw new MalformedImageException("expected marker");
      }
      while (p < in.length && u8(in[p]) == 0xFF) {
        p++;
      }
      if (p >= in.length) {
        throw new MalformedImageException("truncated marker");
      }
      int marker = u8(in[p++]);
      if (marker == 0xD9) { // EOI: anything after it is dropped
        out.write(0xFF);
        out.write(0xD9);
        return out.toByteArray();
      }
      if (marker == 0x00 || marker == 0xD8) {
        throw new MalformedImageException("unexpected marker");
      }
      if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) { // standalone, no length
        out.write(0xFF);
        out.write(marker);
        continue;
      }
      if (p + 2 > in.length) {
        throw new MalformedImageException("truncated length");
      }
      int length = (u8(in[p]) << 8) | u8(in[p + 1]);
      if (length < 2 || p + length > in.length) {
        throw new MalformedImageException("bad segment length");
      }
      if (keepSegment(marker, in, p + 2, length - 2)) {
        out.write(0xFF);
        out.write(marker);
        out.write(in, p, length);
      }
      p += length;
      if (marker == 0xDA) { // SOS: entropy-coded data follows up to the next real marker
        int start = p;
        while (p < in.length) {
          if (u8(in[p]) == 0xFF && p + 1 < in.length) {
            int next = u8(in[p + 1]);
            if (next != 0x00 && !(next >= 0xD0 && next <= 0xD7) && next != 0xFF) {
              break;
            }
            p += next == 0xFF ? 1 : 2;
          } else {
            p++;
          }
        }
        if (p >= in.length) {
          throw new MalformedImageException("scan not terminated");
        }
        out.write(in, start, p - start);
      }
    }
  }

  /** Image structure and the JFIF header are kept; the ICC profile and Adobe colour flag too. */
  private static boolean keepSegment(int marker, byte[] in, int off, int len) {
    if (marker == 0xE0) {
      return hasPrefix(in, off, len, "JFIF\0");
    }
    if (marker == 0xE2) {
      return hasPrefix(in, off, len, "ICC_PROFILE\0");
    }
    if (marker == 0xEE) { // APP14 "Adobe": version, flags and colour transform only
      return hasPrefix(in, off, len, "Adobe") && len <= 12;
    }
    if (marker >= 0xE1 && marker <= 0xEF) { // APP1 Exif/XMP, APP13 IPTC, APP3..APP15
      return false;
    }
    return marker != 0xFE; // COM
  }

  private static boolean hasPrefix(byte[] in, int off, int len, String prefix) {
    byte[] p = prefix.getBytes(StandardCharsets.ISO_8859_1);
    if (len < p.length) {
      return false;
    }
    for (int i = 0; i < p.length; i++) {
      if (in[off + i] != p[i]) {
        return false;
      }
    }
    return true;
  }

  private static byte[] stripPng(byte[] in) {
    ByteArrayOutputStream out = new ByteArrayOutputStream(in.length);
    out.write(in, 0, PNG_SIGNATURE);
    int p = PNG_SIGNATURE;
    boolean first = true;
    while (p + 12 <= in.length) {
      long length =
          ((long) u8(in[p]) << 24) | (u8(in[p + 1]) << 16) | (u8(in[p + 2]) << 8) | u8(in[p + 3]);
      String type = new String(in, p + 4, 4, StandardCharsets.ISO_8859_1);
      long end = p + 12L + length;
      if (length > Integer.MAX_VALUE || end > in.length) {
        throw new MalformedImageException("bad chunk length");
      }
      if (first && !"IHDR".equals(type)) {
        throw new MalformedImageException("IHDR must come first");
      }
      first = false;
      if (!PNG_DROPPED.contains(type)) {
        out.write(in, p, (int) (end - p));
      }
      p = (int) end;
      if ("IEND".equals(type)) { // anything after IEND is dropped
        return out.toByteArray();
      }
    }
    throw new MalformedImageException("no IEND");
  }

  private static int u8(byte b) {
    return b & 0xFF;
  }
}
