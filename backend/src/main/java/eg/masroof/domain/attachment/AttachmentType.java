package eg.masroof.domain.attachment;

import java.util.Optional;

/** The only file types a receipt attachment may have, decided from the leading bytes alone. */
public enum AttachmentType {
  JPEG("image/jpeg"),
  PNG("image/png"),
  PDF("application/pdf");

  private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
  private static final byte[] PNG_MAGIC = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
  private static final byte[] PDF_MAGIC = {0x25, 0x50, 0x44, 0x46, 0x2D};

  private final String contentType;

  AttachmentType(String contentType) {
    this.contentType = contentType;
  }

  /** The MIME type served for this type. */
  public String contentType() {
    return contentType;
  }

  /** The type with this MIME type, if it is one of the three. */
  public static Optional<AttachmentType> ofContentType(String contentType) {
    for (AttachmentType t : values()) {
      if (t.contentType.equals(contentType)) {
        return Optional.of(t);
      }
    }
    return Optional.empty();
  }

  /** Sniffs JPEG, PNG or PDF from the leading bytes; empty for anything else. */
  public static Optional<AttachmentType> sniff(byte[] bytes) {
    if (startsWith(bytes, JPEG_MAGIC)) {
      return Optional.of(JPEG);
    }
    if (startsWith(bytes, PNG_MAGIC)) {
      return Optional.of(PNG);
    }
    if (startsWith(bytes, PDF_MAGIC)) {
      return Optional.of(PDF);
    }
    return Optional.empty();
  }

  private static boolean startsWith(byte[] bytes, byte[] prefix) {
    if (bytes.length < prefix.length) {
      return false;
    }
    for (int i = 0; i < prefix.length; i++) {
      if (bytes[i] != prefix[i]) {
        return false;
      }
    }
    return true;
  }
}
