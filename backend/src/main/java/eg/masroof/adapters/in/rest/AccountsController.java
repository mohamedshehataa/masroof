package eg.masroof.adapters.in.rest;

import eg.masroof.adapters.in.rest.generated.api.AccountsApi;
import eg.masroof.adapters.in.rest.generated.model.AccountType;
import eg.masroof.adapters.in.rest.generated.model.ListAccounts200Response;
import eg.masroof.adapters.in.rest.generated.model.PaymentAccount;
import eg.masroof.adapters.in.rest.generated.model.PaymentAccountInput;
import eg.masroof.application.expense.ReferenceService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** GET /api/accounts: the payment accounts the Add-expense sheet needs. */
@RestController
class AccountsController implements AccountsApi {

  private final ReferenceService service;

  // Declared in the contract, built later: answer 501 as Problem+JSON instead of the bare default.
  @Override
  public ResponseEntity<PaymentAccount> createAccount(PaymentAccountInput paymentAccountInput) {
    throw ApiProblemException.notImplemented();
  }

  @Override
  public ResponseEntity<Void> deleteAccount(UUID accountId) {
    throw ApiProblemException.notImplemented();
  }

  @Override
  public ResponseEntity<PaymentAccount> getAccount(UUID accountId) {
    throw ApiProblemException.notImplemented();
  }

  @Override
  public ResponseEntity<PaymentAccount> updateAccount(
      UUID accountId, PaymentAccountInput paymentAccountInput) {
    throw ApiProblemException.notImplemented();
  }

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
