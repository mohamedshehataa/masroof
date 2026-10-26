package eg.masroof.application.sms;

import java.util.Optional;
import java.util.UUID;

/** Port: the member behind the current request, if there is an authenticated one. */
public interface CallerMember {

  /** The authenticated member; empty in the dev single-household mode without a token. */
  Optional<UUID> memberId();
}
