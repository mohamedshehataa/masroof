package eg.masroof;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Spring Boot entrypoint; lives in the root package, outside every architecture layer. */
@SpringBootApplication
public class MasroofApplication {

  /**
   * Starts the application.
   *
   * @param args command-line arguments passed to Spring Boot
   */
  public static void main(String[] args) {
    SpringApplication.run(MasroofApplication.class, args);
  }
}
