package eg.masroof.adapters.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@Tag("REQ-SEC-012")
class DevSwitchSpellingsTest {

  private ApplicationContextRunner prod(String value) {
    return new ApplicationContextRunner()
        .withUserConfiguration(ProdSingleHouseholdGuard.class)
        .withPropertyValues("spring.profiles.active=prod", "masroof.dev.single-household=" + value);
  }

  @Test
  @DisplayName("REQ-SEC-012 every truthy spelling of the dev switch refuses to start under prod")
  void truthySpellingsFail() {
    for (String v : List.of("true", "TRUE", "True", "yes", "on", "1", "y", " true ", "maybe")) {
      prod(v).run(ctx -> assertThat(ctx).as(v).hasFailed());
    }
  }

  @Test
  @DisplayName("REQ-SEC-012 only clear false spellings (or nothing) start under prod")
  void falseSpellingsStart() {
    for (String v : List.of("false", "FALSE", "no", "off", "0", "")) {
      prod(v).run(ctx -> assertThat(ctx).as(v).hasNotFailed());
    }
  }

  @Test
  @DisplayName("REQ-SEC-012 the bean condition reads the switch the same way")
  void conditionAgrees() {
    for (String v : List.of("true", "yes", "on", "1", "TRUE")) {
      new ApplicationContextRunner()
          .withUserConfiguration(FirstHouseholdProviderProbe.class)
          .withPropertyValues("masroof.dev.single-household=" + v)
          .run(ctx -> assertThat(ctx).as(v).hasBean("probe"));
    }
    for (String v : List.of("false", "no", "off", "0", "")) {
      new ApplicationContextRunner()
          .withUserConfiguration(FirstHouseholdProviderProbe.class)
          .withPropertyValues("masroof.dev.single-household=" + v)
          .run(ctx -> assertThat(ctx).as(v).doesNotHaveBean("probe"));
    }
  }

  @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
  static class FirstHouseholdProviderProbe {
    @org.springframework.context.annotation.Bean
    @org.springframework.context.annotation.Conditional(DevSwitch.Enabled.class)
    String probe() {
      return "on";
    }
  }
}
