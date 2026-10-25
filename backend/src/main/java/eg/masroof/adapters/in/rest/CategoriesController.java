package eg.masroof.adapters.in.rest;

import eg.masroof.adapters.in.rest.generated.api.CategoriesApi;
import eg.masroof.adapters.in.rest.generated.model.Category;
import eg.masroof.adapters.in.rest.generated.model.CategoryInput;
import eg.masroof.adapters.in.rest.generated.model.ListCategories200Response;
import eg.masroof.application.expense.ReferenceService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** GET /api/categories: the list the Add-expense sheet needs. */
@RestController
class CategoriesController implements CategoriesApi {

  private final ReferenceService service;

  // Declared in the contract, built later: answer 501 as Problem+JSON instead of the bare default.
  @Override
  public ResponseEntity<Category> createCategory(CategoryInput categoryInput) {
    throw ApiProblemException.notImplemented();
  }

  @Override
  public ResponseEntity<Void> deleteCategory(UUID categoryId) {
    throw ApiProblemException.notImplemented();
  }

  @Override
  public ResponseEntity<Category> getCategory(UUID categoryId) {
    throw ApiProblemException.notImplemented();
  }

  @Override
  public ResponseEntity<Category> updateCategory(UUID categoryId, CategoryInput categoryInput) {
    throw ApiProblemException.notImplemented();
  }

  CategoriesController(ReferenceService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<ListCategories200Response> listCategories(Boolean includeArchived) {
    return ResponseEntity.ok(
        new ListCategories200Response()
            .items(
                service.categories(includeArchived).stream()
                    .map(
                        c ->
                            new Category()
                                .id(c.id())
                                .householdId(c.householdId())
                                .nameEn(c.nameEn())
                                .nameAr(c.nameAr())
                                .icon(c.icon())
                                .color(c.color())
                                .parentId(c.parentId())
                                .isEssential(c.essential())
                                .sortOrder(c.sortOrder())
                                .archived(c.archived()))
                    .toList()));
  }
}
