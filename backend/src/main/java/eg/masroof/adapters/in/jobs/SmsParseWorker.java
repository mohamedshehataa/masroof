package eg.masroof.adapters.in.jobs;

import eg.masroof.application.sms.SmsProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls the raw message queue (STORY-043). Several instances (or threads) may run at once: the
 * claim uses SKIP LOCKED, so each message is processed by exactly one of them per attempt. Turn it
 * off with {@code masroof.jobs.enabled=false} (tests call {@link SmsProcessor#drain} directly).
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "masroof.jobs.enabled", havingValue = "true", matchIfMissing = true)
class SmsParseWorker {

  private static final int BATCH = 25;
  private static final Logger LOG = LoggerFactory.getLogger(SmsParseWorker.class);

  private final SmsProcessor processor;

  SmsParseWorker(SmsProcessor processor) {
    this.processor = processor;
  }

  @Scheduled(
      fixedDelayString = "${masroof.jobs.sms-parse.interval-ms:2000}",
      initialDelayString = "${masroof.jobs.sms-parse.initial-delay-ms:3000}")
  void run() {
    try {
      processor.drain(BATCH);
    } catch (RuntimeException e) {
      // The database may be down for a moment; the next tick retries. Type only, never a message.
      LOG.warn("sms parse worker pass failed error={}", e.getClass().getSimpleName());
    }
  }
}
