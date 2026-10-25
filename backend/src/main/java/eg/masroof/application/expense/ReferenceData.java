package eg.masroof.application.expense;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Port: read-only reference data (categories, payment accounts, members) of a household. */
public interface ReferenceData {

  /** A category as the API shows it. */
  record CategoryView(
      UUID id,
      UUID householdId,
      String nameEn,
      String nameAr,
      @Nullable String icon,
      @Nullable String color,
      @Nullable UUID parentId,
      boolean essential,
      int sortOrder,
      boolean archived) {}

  /** A payment account as the API shows it. */
  record AccountView(
      UUID id,
      UUID memberId,
      String type,
      @Nullable String institution,
      @Nullable String last4,
      @Nullable String nickname,
      String currency,
      boolean shared) {}

  /** A category of this household, if it exists. */
  Optional<CategoryView> category(UUID householdId, UUID categoryId);

  /** Whether the payment account belongs to the household. */
  boolean accountExists(UUID householdId, UUID accountId);

  /** Whether the member belongs to the household. */
  boolean memberExists(UUID householdId, UUID memberId);

  /** Categories in display order. */
  List<CategoryView> categories(UUID householdId, boolean includeArchived);

  /** Payment accounts in display order. */
  List<AccountView> accounts(UUID householdId);
}
