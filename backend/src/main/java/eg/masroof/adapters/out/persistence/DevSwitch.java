package eg.masroof.adapters.out.persistence;

import java.util.Locale;
import java.util.Set;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * The one reading of {@code masroof.dev.single-household}, shared by the prod guard and the bean
 * condition. Anything that is not clearly false counts as on (yes, on, 1, TRUE, typos ...), so a
 * spelling the guard does not know can never switch the dev household on behind its back.
 */
final class DevSwitch {

  static final String PROPERTY = "masroof.dev.single-household";
  private static final Set<String> OFF = Set.of("", "false", "no", "off", "0", "n", "f");

  private DevSwitch() {}

  static boolean enabled(Environment environment) {
    String raw = environment.getProperty(PROPERTY, "");
    return !OFF.contains(raw.strip().toLowerCase(Locale.ROOT));
  }

  /** Matches when {@link #enabled} is true. */
  static final class Enabled implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
      return enabled(context.getEnvironment());
    }
  }
}
