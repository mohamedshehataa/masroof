package eg.masroof.application.attachment;

import java.util.Optional;

/** Port: object storage for attachment bytes (S3-compatible). Keys are built from ids only. */
public interface AttachmentStore {

  /** Storage is not configured or not reachable; carries no object content. */
  class UnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public UnavailableException(String message, Throwable cause) {
      super(message, cause);
    }

    public UnavailableException(String message) {
      super(message);
    }
  }

  /** Stores the object, replacing any with the same key. */
  void put(String key, String contentType, byte[] content);

  /** The object bytes, or empty when the key does not exist. */
  Optional<byte[]> get(String key);

  /** Deletes the object; a missing key is not an error. */
  void delete(String key);
}
