package eg.masroof.adapters.in.rest;

import eg.masroof.adapters.in.rest.generated.api.IngestApi;
import eg.masroof.adapters.in.rest.generated.model.IngestAccepted;
import eg.masroof.adapters.in.rest.generated.model.IngestSmsRequest;
import eg.masroof.application.sms.SmsIngestService;
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

  IngestController(SmsIngestService sms) {
    this.sms = sms;
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
