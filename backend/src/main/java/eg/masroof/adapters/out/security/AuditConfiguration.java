package eg.masroof.adapters.out.security;

import eg.masroof.domain.auth.IpHasher;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * Key for the audit-log IP pseudonyms: {@code MASROOF_AUDIT_KEY} when set (at least 32 bytes), else
 * derived from {@code MASROOF_JWT_SECRET} with a labelled HMAC. Under profile {@code prod} one of
 * the two must exist (the JWT secret is already mandatory there); outside prod a random per-boot
 * key is used. Values never appear in messages.
 */
@Configuration(proxyBeanMethods = false)
class AuditConfiguration {

  private static final Logger LOG = LoggerFactory.getLogger(AuditConfiguration.class);
  private static final int MIN_BYTES = 32;
  private static final SecureRandom RANDOM = new SecureRandom();

  @Bean
  IpHasher ipHasher(Environment environment) {
    String auditKey = read(environment, "masroof.audit.key");
    String jwt = read(environment, "masroof.jwt.secret");
    if (!auditKey.isBlank()) {
      if (auditKey.getBytes(StandardCharsets.UTF_8).length < MIN_BYTES) {
        throw new IllegalStateException(
            "masroof.audit.key (MASROOF_AUDIT_KEY) must be at least "
                + MIN_BYTES
                + " bytes (openssl rand -base64 48)");
      }
      return IpHasher.derivedFrom(auditKey.getBytes(StandardCharsets.UTF_8), "key");
    }
    if (!jwt.isBlank()) {
      return IpHasher.derivedFrom(jwt.getBytes(StandardCharsets.UTF_8), "jwt");
    }
    if (environment.acceptsProfiles(Profiles.of("prod"))) {
      throw new IllegalStateException(
          "MASROOF_AUDIT_KEY or MASROOF_JWT_SECRET is required under profile prod");
    }
    LOG.warn("MASROOF_AUDIT_KEY is not set: using a random audit key for this run only");
    byte[] random = new byte[48];
    RANDOM.nextBytes(random);
    return new IpHasher(random);
  }

  private static String read(Environment environment, String name) {
    try {
      return environment.getProperty(name, "");
    } catch (IllegalArgumentException unresolvable) {
      return "${";
    }
  }
}
