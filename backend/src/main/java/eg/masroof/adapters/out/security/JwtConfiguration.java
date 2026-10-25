package eg.masroof.adapters.out.security;

import eg.masroof.application.auth.AccessTokens;
import eg.masroof.application.auth.PasswordHasher;
import eg.masroof.domain.auth.JwtSecretPolicy;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * Builds the access-token signer from {@code masroof.jwt.secret} (env {@code MASROOF_JWT_SECRET}).
 * A missing, short or placeholder secret fails the start under profile {@code prod}, like
 * ProdDatabasePasswordGuard does for the database password. Outside prod a missing secret yields a
 * random per-boot key (tokens die on restart); a secret that is set must still pass the policy. The
 * value never appears in a message.
 */
@Configuration(proxyBeanMethods = false)
class JwtConfiguration {

  private static final Logger LOG = LoggerFactory.getLogger(JwtConfiguration.class);
  private static final SecureRandom RANDOM = new SecureRandom();

  @Bean
  AccessTokens accessTokens(Environment environment) {
    String secret;
    try {
      secret = environment.getProperty("masroof.jwt.secret", "");
    } catch (IllegalArgumentException unresolvable) {
      secret = "${";
    }
    boolean prod = environment.acceptsProfiles(Profiles.of("prod"));
    if (secret.isBlank() && !prod) {
      LOG.warn("MASROOF_JWT_SECRET is not set: using a random key for this run only");
      byte[] random = new byte[48];
      RANDOM.nextBytes(random);
      return new HmacJwtAccessTokens(random);
    }
    Optional<String> problem = JwtSecretPolicy.problem(secret);
    if (problem.isPresent()) {
      throw new IllegalStateException(
          "masroof.jwt.secret (MASROOF_JWT_SECRET) "
              + problem.get()
              + "; it must be at least "
              + JwtSecretPolicy.MIN_BYTES
              + " random bytes (openssl rand -base64 48)");
    }
    return new HmacJwtAccessTokens(secret.getBytes(StandardCharsets.UTF_8));
  }

  @Bean
  PasswordHasher passwordHasher() {
    return new BcryptPasswordHasher();
  }
}
