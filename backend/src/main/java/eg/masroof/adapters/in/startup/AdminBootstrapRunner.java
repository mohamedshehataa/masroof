package eg.masroof.adapters.in.startup;

import eg.masroof.application.auth.AdminBootstrap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Creates the first admin from MASROOF_BOOTSTRAP_ADMIN_EMAIL / _PASSWORD when no login exists. */
@Component
final class AdminBootstrapRunner implements ApplicationRunner {

  private final AdminBootstrap bootstrap;
  private final String email;
  private final String password;

  AdminBootstrapRunner(
      AdminBootstrap bootstrap,
      @Value("${masroof.bootstrap.admin-email:}") String email,
      @Value("${masroof.bootstrap.admin-password:}") String password) {
    this.bootstrap = bootstrap;
    this.email = email;
    this.password = password;
  }

  @Override
  public void run(ApplicationArguments args) {
    bootstrap.run(email, password);
  }
}
