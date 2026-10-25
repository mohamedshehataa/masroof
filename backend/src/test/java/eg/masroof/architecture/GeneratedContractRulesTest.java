package eg.masroof.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * REQ-OPS-050: server interfaces and models are generated from contracts/openapi.yaml into a
 * package containing {@code generated}; hand-written controllers implement them. Runs in archTest
 * (tag arch) over main classes, including the generated ones the build compiles.
 */
@Tag("arch")
@Tag("REQ-OPS-050")
class GeneratedContractRulesTest {

  private static final Path CONTRACT = Path.of("..", "contracts", "openapi.yaml");
  private static final String DOMAIN_MONEY = "eg.masroof.domain.money.Money";
  private static JavaClasses main;

  @BeforeAll
  static void importMain() {
    main =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("eg.masroof");
  }

  private static List<JavaClass> generated() {
    return main.stream().filter(c -> c.getPackageName().contains(".generated")).toList();
  }

  private static List<JavaClass> generatedApis() {
    return generated().stream()
        .filter(c -> c.isInterface() && c.getSimpleName().endsWith("Api"))
        .toList();
  }

  @Test
  @DisplayName("REQ-OPS-050 the build generates interfaces and models from the contract")
  void generatesInterfacesAndModels() {
    assertThat(generatedApis())
        .as("generated *Api interfaces in a package containing 'generated' (none were compiled)")
        .isNotEmpty();
    assertThat(generated().stream().filter(c -> !c.isInterface()).toList())
        .as("generated model classes (none were compiled)")
        .isNotEmpty();
  }

  @Test
  @DisplayName(
      "REQ-OPS-050 generated sources live under build/generated/openapi or src/main/generated")
  void generatedSourcesLocation() {
    Path buildDir = Path.of("build", "generated", "openapi");
    Path srcDir = Path.of("src", "main", "generated");
    boolean found = false;
    for (Path dir : List.of(buildDir, srcDir)) {
      if (Files.isDirectory(dir)) {
        try (Stream<Path> files = Files.walk(dir)) {
          found |= files.anyMatch(p -> p.getFileName().toString().equals("HealthApi.java"));
        } catch (IOException e) {
          throw new IllegalStateException(e);
        }
      }
    }
    assertThat(found)
        .as("HealthApi.java under backend/build/generated/openapi or backend/src/main/generated")
        .isTrue();
  }

  @Test
  @DisplayName("REQ-OPS-050 every operationId in the contract is a method of a generated interface")
  void everyOperationIsGenerated() throws IOException {
    Set<String> operationIds = new TreeSet<>();
    Matcher m =
        Pattern.compile("(?m)^ {6}operationId:\\s*(\\S+)\\s*$").matcher(Files.readString(CONTRACT));
    while (m.find()) {
      operationIds.add(m.group(1));
    }
    assertThat(operationIds).as("operationIds read from contracts/openapi.yaml").isNotEmpty();
    Set<String> generatedMethods = new TreeSet<>();
    generatedApis()
        .forEach(api -> api.getMethods().forEach(method -> generatedMethods.add(method.getName())));
    assertThat(generatedMethods)
        .as("methods of generated *Api interfaces must cover every contract operation")
        .containsAll(operationIds);
  }

  @Test
  @DisplayName(
      "REQ-OPS-050 REST controllers implement a generated interface (HealthController implements HealthApi)")
  void controllersImplementGeneratedInterfaces() {
    JavaClass health =
        main.stream()
            .filter(c -> c.getSimpleName().equals("HealthController"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("HealthController not found in eg.masroof"));
    assertThat(
            health.getAllRawInterfaces().stream()
                .map(i -> i.getSimpleName() + "@" + i.getPackageName())
                .toList())
        .as("interfaces implemented by HealthController (expected generated HealthApi)")
        .anyMatch(n -> n.startsWith("HealthApi@") && n.contains(".generated"));

    List<JavaClass> offenders =
        main.stream()
            .filter(
                c -> c.isAnnotatedWith("org.springframework.web.bind.annotation.RestController"))
            .filter(c -> !c.getPackageName().contains(".generated"))
            .filter(
                c ->
                    c.getAllRawInterfaces().stream()
                        .noneMatch(i -> i.getPackageName().contains(".generated")))
            .toList();
    assertThat(offenders)
        .as("@RestController classes that implement no generated interface")
        .isEmpty();
  }

  @Test
  @DisplayName(
      "REQ-OPS-050 generated models use no float and no legacy date types, date-time is Instant")
  void modelsUseNoFloatAndInstant() {
    Set<String> banned =
        Set.of(
            "double",
            "float",
            "java.lang.Double",
            "java.lang.Float",
            "java.util.Date",
            "java.time.OffsetDateTime",
            "java.time.LocalDateTime",
            "java.time.ZonedDateTime");
    List<JavaClass> models = generated().stream().filter(c -> !c.isInterface()).toList();
    assertThat(models).as("generated model classes").isNotEmpty();
    List<String> bad =
        models.stream()
            .flatMap(c -> c.getAllFields().stream())
            .filter(f -> banned.contains(f.getRawType().getName()))
            .map(
                f ->
                    f.getOwner().getSimpleName()
                        + "."
                        + f.getName()
                        + ":"
                        + f.getRawType().getName())
            .toList();
    assertThat(bad).as("generated fields with banned types").isEmpty();
    assertThat(
            models.stream()
                .flatMap(c -> c.getAllFields().stream())
                .anyMatch(f -> f.getRawType().getName().equals("java.time.Instant")))
        .as("some generated model field maps date-time to java.time.Instant (Health.time)")
        .isTrue();
  }

  @Test
  @DisplayName("REQ-OPS-050 Money in the contract maps to the domain Money type")
  void moneyMapsToDomainMoney() {
    assertThat(generated().stream().map(JavaClass::getSimpleName).toList())
        .as("no generated Money class; the contract Money schema maps to " + DOMAIN_MONEY)
        .doesNotContain("Money");
    List<JavaField> moneyFields =
        generated().stream()
            .flatMap(c -> c.getAllFields().stream())
            .filter(f -> f.getRawType().getName().equals(DOMAIN_MONEY))
            .toList();
    assertThat(moneyFields)
        .as("generated model fields typed as " + DOMAIN_MONEY + " (for example Expense.amount)")
        .isNotEmpty();
  }
}
