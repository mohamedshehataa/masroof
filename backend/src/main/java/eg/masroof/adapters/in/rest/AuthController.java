package eg.masroof.adapters.in.rest;

import eg.masroof.adapters.in.rest.generated.api.AuthApi;
import eg.masroof.adapters.in.rest.generated.model.Invite;
import eg.masroof.adapters.in.rest.generated.model.InviteAcceptRequest;
import eg.masroof.adapters.in.rest.generated.model.InviteRequest;
import eg.masroof.adapters.in.rest.generated.model.LoginRequest;
import eg.masroof.adapters.in.rest.generated.model.LogoutRequest;
import eg.masroof.adapters.in.rest.generated.model.Member;
import eg.masroof.adapters.in.rest.generated.model.MemberRole;
import eg.masroof.adapters.in.rest.generated.model.RefreshRequest;
import eg.masroof.adapters.in.rest.generated.model.TokenPair;
import eg.masroof.adapters.in.rest.generated.model.TokenPair.TokenTypeEnum;
import eg.masroof.application.auth.AuthService;
import eg.masroof.application.auth.Caller;
import eg.masroof.application.auth.Session;
import eg.masroof.application.household.UnauthenticatedException;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** /auth/login, /auth/refresh, /auth/logout (REQ-SEC-001/005/006/007). */
@RestController
class AuthController implements AuthApi {

  private final AuthService service;
  private final HttpServletRequest request;

  AuthController(AuthService service, HttpServletRequest request) {
    this.service = service;
    this.request = request;
  }

  @Override
  public ResponseEntity<TokenPair> login(LoginRequest body) {
    return ResponseEntity.ok(
        toPair(service.login(body.getEmail(), body.getPassword(), ClientIp.of(request))));
  }

  @Override
  public ResponseEntity<TokenPair> refreshToken(RefreshRequest body) {
    return ResponseEntity.ok(toPair(service.refresh(body.getRefreshToken())));
  }

  @Override
  public ResponseEntity<Void> logout(@Nullable LogoutRequest body) {
    Caller caller = CallerHousehold.CallerContext.current();
    if (caller == null) {
      throw new UnauthenticatedException();
    }
    service.logout(
        caller.userId(),
        body == null ? null : body.getRefreshToken(),
        body != null && Boolean.TRUE.equals(body.getAllSessions()));
    return ResponseEntity.noContent().build();
  }

  // Declared in the contract, built later.
  @Override
  public ResponseEntity<Invite> createInvite(InviteRequest inviteRequest) {
    throw ApiProblemException.notImplemented();
  }

  @Override
  public ResponseEntity<TokenPair> acceptInvite(InviteAcceptRequest inviteAcceptRequest) {
    throw ApiProblemException.notImplemented();
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
