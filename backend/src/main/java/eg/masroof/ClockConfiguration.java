package eg.masroof;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Injected clock (backend/CLAUDE.md). A separate class so web slices can supply their own. */
@Configuration(proxyBeanMethods = false)
class ClockConfiguration {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
