package eg.masroof.application.expense;

import eg.masroof.application.expense.ReferenceData.AccountView;
import eg.masroof.application.expense.ReferenceData.CategoryView;
import eg.masroof.application.household.CurrentHousehold;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Use cases: the lists the Add-expense sheet needs. */
@Service
public class ReferenceService {

  private final ReferenceData reference;
  private final CurrentHousehold currentHousehold;

  ReferenceService(ReferenceData reference, CurrentHousehold currentHousehold) {
    this.reference = reference;
    this.currentHousehold = currentHousehold;
  }

  /** Categories of the current household. */
  @Transactional(readOnly = true)
  public List<CategoryView> categories(boolean includeArchived) {
    return reference.categories(currentHousehold.get().id(), includeArchived);
  }

  /** Payment accounts of the current household. */
  @Transactional(readOnly = true)
  public List<AccountView> accounts() {
    return reference.accounts(currentHousehold.get().id());
  }
}
