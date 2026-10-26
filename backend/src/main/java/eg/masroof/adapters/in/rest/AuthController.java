package eg.masroof.adapters.in.rest;

import eg.masroof.adapters.in.rest.generated.api.AuthApi;
import eg.masroof.adapters.in.rest.generated.model.Invite;
import eg.masroof.adapters.in.rest.generated.model.InviteAcceptRequest;
import eg.masroof.adapters.in.rest.generated.model.InviteRequest;
import eg.masroof.adapters.in.rest.generated.model.InviteSummary;
import eg.masroof.adapters.in.rest.generated.model.LoginRequest;
import eg.masroof.adapters.in.rest.generated.model.LogoutRequest;
import eg.masroof.adapters.in.rest.generated.model.Member;
import eg.masroof.adapters.in.rest.generated.model.MemberRole;
import eg.masroof.adapters.in.rest.generated.model.RefreshRequest;
import eg.masroof.adapters.in.rest.generated.model.TokenPair;
import eg.masroof.adapters.in.rest.generated.model.TokenPair.TokenTypeEnum;
import eg.masroof.application.auth.AuthService;
import eg.masroof.application.auth.Caller;
import eg.masroof.application.auth.InviteService;
import eg.masroof.application.auth.Session;
import eg.masroof.application.household.UnauthenticatedException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** /auth/login, /auth/refresh, /auth/logout (REQ-SEC-001/005/006/007). */
@RestController
class AuthController implements AuthApi {

  private final AuthService service;
  private final InviteService invites;
  private final HttpServletRequest request;
  private final ClientIp clientIp;

  AuthController(
      AuthService service, InviteService invites, HttpServletRequest request, ClientIp clientIp) {
    this.invites = invites;
    this.clientIp = clientIp;
    this.service = service;
    this.request = request;
  }

  @Override
  public ResponseEntity<TokenPair> login(LoginRequest body) {
    return ResponseEntity.ok(
        toPair(service.login(body.getEmail(), body.getPassword(), clientIp.of(request))));
  }

  @Override
  public ResponseEntity<TokenPair> refreshToken(RefreshRequest body) {
    return ResponseEntity.ok(toPair(service.refresh(body.getRefreshToken())));
  }

  @Override
  public ResponseEntity<Void> logout(@Nullable LogoutRequest body) {
    Caller caller = CallerHousehold.CallerContext.current();
    service.logout(
        caller == null ? null : caller.userId(),
        body == null ? null : body.getRefreshToken(),
        body != null && Boolean.TRUE.equals(body.getAllSessions()));
    return ResponseEntity.noContent().build();
  }

  @Override
  public ResponseEntity<Invite> createInvite(InviteRequest body) {
    InviteService.Created created =
        invites.create(
            requireCaller(),
            body.getDisplayName(),
            body.getEmail(),
            body.getPhoneE164(),
            body.getRole() == null ? null : body.getRole().name(),
            clientIp.of(request));
    return ResponseEntity.status(HttpStatus.CREATED)
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(
            new Invite()
                .id(created.id())
                .code(created.code())
                .displayName(created.displayName())
                .expiresAt(created.expiresAt()));
  }

  @Override
  public ResponseEntity<TokenPair> acceptInvite(InviteAcceptRequest body) {
    Session session =
        invites.accept(
            body.getCode(),
            body.getEmail(),
            body.getPassword(),
            body.getDisplayName(),
            clientIp.of(request));
    return ResponseEntity.status(HttpStatus.CREATED)
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(toPair(session));
  }

  @Override
  public ResponseEntity<List<InviteSummary>> listInvites() {
    return ResponseEntity.ok(
        invites.list(requireCaller()).stream()
            .map(
                v ->
                    new InviteSummary()
                        .id(v.id())
                        .displayName(v.displayName())
                        .email(v.email())
                        .phoneE164(v.phoneE164())
                        .status(InviteSummary.StatusEnum.valueOf(v.status()))
                        .createdAt(v.createdAt())
                        .expiresAt(v.expiresAt()))
            .toList());
  }

  @Override
  public ResponseEntity<Void> revokeInvite(UUID inviteId) {
    invites.revoke(requireCaller(), inviteId, clientIp.of(request));
    return ResponseEntity.noContent().build();
  }

  private static Caller requireCaller() {
    Caller caller = CallerHousehold.CallerContext.current();
    if (caller == null) {
      throw new UnauthenticatedException();
    }
    return caller;
  }

  private static TokenPair toPair(Session s) {
    return new TokenPair()
        .accessToken(s.accessToken())
        .refreshToken(s.refreshToken())
        .expiresIn((int) s.expiresInSeconds())
        .tokenType(TokenTypeEnum.BEARER)
        .member(
            new Member()
                .id(s.account().memberId())
                .householdId(s.account().householdId())
                .displayName(s.account().displayName())
                .role(MemberRole.valueOf(s.account().role().name())));
  }
}
