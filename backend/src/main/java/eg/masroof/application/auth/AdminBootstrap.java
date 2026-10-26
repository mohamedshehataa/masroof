package eg.masroof.application.auth;

import eg.masroof.domain.auth.PasswordPolicy;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** First-user bootstrap: creates the admin login when no login exists yet. */
@Service
public class AdminBootstrap {

  private static final Logger LOG = LoggerFactory.getLogger(AdminBootstrap.class);

  private final AccountStore accounts;
  private final PasswordHasher hasher;

  AdminBootstrap(AccountStore accounts, PasswordHasher hasher) {
    this.accounts = accounts;
    this.hasher = hasher;
  }

  /** Does nothing when either value is blank or a login already exists. Never logs the password. */
  public void run(String email, String password) {
    if (email.isBlank() || password.isEmpty()) {
      return;
    }
    String normalized = email.strip().toLowerCase(Locale.ROOT);
    int at = normalized.indexOf('@');
    if (at < 2 || normalized.length() > 254) {
      throw new IllegalStateException("MASROOF_BOOTSTRAP_ADMIN_EMAIL is not a valid e-mail");
    }
    PasswordPolicy.check(password)
        .ifPresent(
            violation -> {
              throw new IllegalStateException(
                  "MASROOF_BOOTSTRAP_ADMIN_PASSWORD rejected: "
                      + PasswordPolicy.message(violation));
            });
    if (accounts.createFirstAdmin(normalized, normalized.substring(0, at), hasher.hash(password))) {
      LOG.info("Bootstrap admin created");
    } else {
      LOG.warn(
          "MASROOF_BOOTSTRAP_ADMIN_* are set but a login already exists; remove them from the"
              + " environment");
    }
  }
}
