package eg.masroof.adapters.in.rest;

import eg.masroof.adapters.in.rest.generated.api.MembersApi;
import eg.masroof.adapters.in.rest.generated.model.Member;
import eg.masroof.adapters.in.rest.generated.model.MemberRole;
import eg.masroof.application.auth.Caller;
import eg.masroof.application.auth.InviteService;
import eg.masroof.application.household.UnauthenticatedException;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** GET /api/members: the caller's household only (REQ-SEC-060). */
@RestController
class MembersController implements MembersApi {

  private final InviteService service;

  MembersController(InviteService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<List<Member>> listMembers() {
    Caller caller = CallerHousehold.CallerContext.current();
    if (caller == null) {
      throw new UnauthenticatedException();
    }
    return ResponseEntity.ok(
        service.members(caller).stream()
            .map(
                m ->
                    new Member()
                        .id(m.id())
                        .householdId(m.householdId())
                        .displayName(m.displayName())
                        .role(MemberRole.valueOf(m.role())))
            .toList());
  }
}
