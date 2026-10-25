package eg.masroof.adapters.in.rest;

import eg.masroof.adapters.in.rest.generated.api.IngestApi;
import eg.masroof.adapters.in.rest.generated.model.IngestAccepted;
import eg.masroof.adapters.in.rest.generated.model.IngestParsedTransactionRequest;
import eg.masroof.adapters.in.rest.generated.model.IngestSmsRequest;
import eg.masroof.application.sms.ParsedTransactionService;
import eg.masroof.application.sms.SmsIngestService;
import eg.masroof.domain.expense.ExpenseSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /api/ingest/sms (REQ-SMS-001). The other ingest endpoints of the contract keep answering 501
 * through the generated defaults until their stories are built. Authenticated with the member's
 * access token like every other route (device tokens: STORY-054).
 */
@RestController
class IngestController implements IngestApi {

  private final SmsIngestService sms;
  private final ParsedTransactionService parsed;

  IngestController(SmsIngestService sms, ParsedTransactionService parsed) {
    this.sms = sms;
    this.parsed = parsed;
  }

  @Override
  public ResponseEntity<IngestAccepted> ingestParsedTransaction(IngestParsedTransactionRequest r) {
    ParsedTransactionService.Accepted accepted =
        parsed.ingest(
            new ParsedTransactionService.Command(
                r.getDeviceId(),
                r.getLocalId(),
                r.getSource() == IngestParsedTransactionRequest.SourceEnum.NOTIFICATION
                    ? ExpenseSource.PUSH
                    : ExpenseSource.SMS,
                r.getKind().getValue(),
                r.getAmountMinor(),
                r.getCurrency(),
                r.getOccurredAt(),
                r.getInstrumentLast4(),
                r.getSenderId(),
                r.getInstitution(),
                r.getPaymentMethod() == null ? null : r.getPaymentMethod().getValue(),
                r.getMerchant()));
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(
            new IngestAccepted()
                .rawMessageId(accepted.rawMessageId())
                .outcome(IngestAccepted.OutcomeEnum.valueOf(accepted.outcome().name())));
  }

  @Override
  public ResponseEntity<IngestAccepted> ingestSms(IngestSmsRequest request) {
    SmsIngestService.Accepted accepted =
        sms.ingest(
            new SmsIngestService.Command(
                request.getSender(),
                request.getBody(),
                request.getReceivedAt(),
                request.getDeviceId(),
                request.getSmsId()));
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(
            new IngestAccepted()
                .rawMessageId(accepted.rawMessageId())
                .outcome(IngestAccepted.OutcomeEnum.valueOf(accepted.outcome().name())));
  }
}
