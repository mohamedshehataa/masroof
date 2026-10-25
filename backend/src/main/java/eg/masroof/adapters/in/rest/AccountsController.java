package eg.masroof.adapters.in.rest;

import eg.masroof.adapters.in.rest.generated.api.AccountsApi;
import eg.masroof.adapters.in.rest.generated.model.AccountType;
import eg.masroof.adapters.in.rest.generated.model.ListAccounts200Response;
import eg.masroof.adapters.in.rest.generated.model.PaymentAccount;
import eg.masroof.application.expense.ReferenceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** GET /api/accounts: the payment accounts the Add-expense sheet needs. */
@RestController
class AccountsController implements AccountsApi {

  private final ReferenceService service;

  AccountsController(ReferenceService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<ListAccounts200Response> listAccounts() {
    return ResponseEntity.ok(
        new ListAccounts200Response()
            .items(
                service.accounts().stream()
                    .map(
                        a ->
                            new PaymentAccount()
                                .id(a.id())
                                .memberId(a.memberId())
                                .type(AccountType.valueOf(a.type()))
                                .institution(a.institution())
                                .last4(a.last4())
                                .nickname(a.nickname())
                                .currency(a.currency())
                                .isShared(a.shared()))
                    .toList()));
  }
}
