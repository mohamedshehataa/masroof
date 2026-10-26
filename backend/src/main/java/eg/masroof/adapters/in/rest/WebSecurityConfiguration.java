package eg.masroof.adapters.in.rest;

import eg.masroof.application.auth.AccessTokens;
import eg.masroof.application.auth.AccountStore;
import eg.masroof.application.household.DevHousehold;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/** Filter chain: CORS, then security headers, then authentication (REQ-SEC-010/011/012). */
@Configuration(proxyBeanMethods = false)
class WebSecurityConfiguration {

  @Bean
  FilterRegistrationBean<CorsFilter> corsFilter(
      Environment environment, @Value("${masroof.cors.allowed-origins:}") String origins) {
    List<String> allowed =
        Arrays.stream(origins.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    if (allowed.contains("*") && environment.acceptsProfiles(Profiles.of("prod"))) {
      throw new IllegalStateException(
          "CORS_ALLOWED_ORIGINS must not contain '*' when profile 'prod' is active");
    }
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOrigins(allowed.contains("*") ? List.of() : allowed);
    config.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
    config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "Idempotency-Key"));
    config.setAllowCredentials(false);
    config.setMaxAge(3600L);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);
    FilterRegistrationBean<CorsFilter> bean = new FilterRegistrationBean<>(new CorsFilter(source));
    bean.setOrder(-300);
    return bean;
  }

  @Bean
  FilterRegistrationBean<SecurityHeadersFilter> securityHeadersFilter() {
    FilterRegistrationBean<SecurityHeadersFilter> bean =
        new FilterRegistrationBean<>(new SecurityHeadersFilter());
    bean.setOrder(-250);
    return bean;
  }

  @Bean
  FilterRegistrationBean<PublicRateLimitFilter> publicRateLimitFilter(
      ClientIp clientIp, Clock clock) {
    FilterRegistrationBean<PublicRateLimitFilter> bean =
        new FilterRegistrationBean<>(new PublicRateLimitFilter(clientIp, clock));
    bean.setOrder(-220);
    return bean;
  }

  /**
   * Size and rate guard for the /api/ingest/* endpoints (parsed transactions); runs right after
   * authentication.
   */
  @Bean
  FilterRegistrationBean<IngestGuardFilter> ingestGuardFilter(Clock clock) {
    FilterRegistrationBean<IngestGuardFilter> bean =
        new FilterRegistrationBean<>(new IngestGuardFilter(clock));
    bean.setOrder(-190);
    return bean;
  }

  /** Token-less requests pass only when a DevHousehold bean exists (the one dev-switch reading). */
  @Bean
  FilterRegistrationBean<AuthenticationFilter> authenticationFilter(
      AccessTokens tokens,
      AccountStore accounts,
      Clock clock,
      Optional<DevHousehold> devHousehold) {
    FilterRegistrationBean<AuthenticationFilter> bean =
        new FilterRegistrationBean<>(
            new AuthenticationFilter(tokens, accounts, clock, devHousehold.isPresent()));
    bean.setOrder(-200);
    return bean;
  }
}
