package eg.masroof.application.sms;

import eg.masroof.application.household.CurrentHousehold;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Which raw messages evidence which expenses (the {@code sources} of an expense). */
@Service
public class ExpenseSourceService {

  private final RawMessageRepository messages;
  private final CurrentHousehold currentHousehold;

  ExpenseSourceService(RawMessageRepository messages, CurrentHousehold currentHousehold) {
    this.messages = messages;
    this.currentHousehold = currentHousehold;
  }

  /** Redacted source views of the current household's expenses, keyed by expense id. */
  @Transactional(readOnly = true)
  public Map<UUID, RawMessageRepository.SourceView> forExpenses(Collection<UUID> expenseIds) {
    return messages.sourcesOf(currentHousehold.get().id(), expenseIds);
  }
}
