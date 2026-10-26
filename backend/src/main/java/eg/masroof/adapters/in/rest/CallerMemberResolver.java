package eg.masroof.adapters.in.rest;

import eg.masroof.application.auth.Caller;
import eg.masroof.application.sms.CallerMember;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** The member the {@link AuthenticationFilter} authenticated for the current request. */
@Component
final class CallerMemberResolver implements CallerMember {

  @Override
  public Optional<UUID> memberId() {
    Caller caller = CallerHousehold.CallerContext.current();
    return caller == null ? Optional.empty() : Optional.of(caller.memberId());
  }
}
