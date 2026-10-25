package eg.masroof.adapters.out.persistence;

import eg.masroof.application.household.CurrentHousehold.Household;
import eg.masroof.application.household.DevHousehold;
import eg.masroof.application.household.HouseholdNotConfiguredException;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Conditional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Dev implementation (only with masroof.dev.single-household=true; never under profile prod): the
 * oldest household, used only for requests that carry no access token. A valid token always wins.
 */
@Component
@Conditional(DevSwitch.Enabled.class)
final class FirstHouseholdProvider implements DevHousehold {

  private final JdbcTemplate jdbc;

  FirstHouseholdProvider(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Household get() {
    List<Household> found =
        jdbc.query(
            "select id, base_currency from household order by created_at, id limit 1",
            (rs, n) ->
                new Household(rs.getObject("id", UUID.class), rs.getString("base_currency")));
    if (found.isEmpty()) {
      throw new HouseholdNotConfiguredException();
    }
    return found.get(0);
  }
}
