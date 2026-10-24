package eg.masroof.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import eg.masroof.archfixture.domain.BadMoney;
import eg.masroof.archfixture.domain.BadReturn;
import eg.masroof.archfixture.domain.GoodMoney;
import eg.masroof.archfixture.money.BadParam;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * S15 step 6 (G4) negative probe for REQ-OPS-024. Not tagged {@code arch}: it runs in the unit
 * {@code test} task (inside {@code checkFast}), so every fast gate proves that the money rule still
 * bites and that {@code archTest} really analyses the main classes.
 */
class MoneyRuleProbeTest {

  @Test
  @Tag("REQ-OPS-024")
  @DisplayName("REQ-OPS-024 money rule rejects a domain field `double amount` and names BadMoney")
  void moneyRuleRejectsBadMoney() {
    JavaClasses classes = new ClassFileImporter().importClasses(BadMoney.class);

    assertThatThrownBy(() -> ArchitectureRulesTest.MONEY_RULE.check(classes))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("BadMoney");
  }

  @Test
  @Tag("REQ-OPS-024")
  @DisplayName(
      "REQ-OPS-024 money rule rejects a `double` parameter in a money package and names BadParam")
  void moneyRuleRejectsDoubleParameter() {
    JavaClasses classes = new ClassFileImporter().importClasses(BadParam.class);

    assertThatThrownBy(() -> ArchitectureRulesTest.MONEY_RULE.check(classes))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("BadParam");
  }

  @Test
  @Tag("REQ-OPS-024")
  @DisplayName(
      "REQ-OPS-024 money rule rejects a money-named domain method returning Double (BadReturn)")
  void moneyRuleRejectsBoxedDoubleReturn() {
    JavaClasses classes = new ClassFileImporter().importClasses(BadReturn.class);

    assertThatThrownBy(() -> ArchitectureRulesTest.MONEY_RULE.check(classes))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("BadReturn");
  }

  @Test
  @Tag("REQ-OPS-024")
  @DisplayName("REQ-OPS-024 money rule accepts minor units in a long (rule is not over-broad)")
  void moneyRuleAcceptsMinorUnits() {
    JavaClasses classes = new ClassFileImporter().importClasses(GoodMoney.class);

    assertThatCode(() -> ArchitectureRulesTest.MONEY_RULE.check(classes))
        .doesNotThrowAnyException();
  }

  @Test
  @Tag("REQ-OPS-024")
  @DisplayName("REQ-OPS-024 archTest import (DoNotIncludeTests) sees main classes, not fixtures")
  void archTestImportSeesMainClassesOnly() {
    JavaClasses mainClasses =
        new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("eg.masroof");

    assertThat(mainClasses.contain("eg.masroof.MasroofApplication"))
        .as("main class eg.masroof.MasroofApplication is visible to the archTest import")
        .isTrue();
    assertThat(mainClasses.contain(BadMoney.class))
        .as("test fixture BadMoney is excluded from the archTest import")
        .isFalse();
  }

  @Test
  @Tag("REQ-OPS-024")
  @DisplayName("REQ-OPS-024 layer packages exist in main sources (target of the G4 selftest plant)")
  void layerPackagesExistInMainSources() {
    Path root = Path.of("src", "main", "java", "eg", "masroof");

    assertThat(List.of("domain", "application", "adapters/in", "adapters/out"))
        .allSatisfy(
            layer ->
                assertThat(Files.isRegularFile(root.resolve(layer).resolve("package-info.java")))
                    .as("%s/package-info.java exists under %s", layer, root)
                    .isTrue());
  }
}
