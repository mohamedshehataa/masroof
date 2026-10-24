package eg.masroof.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.codeUnits;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMember;
import com.tngtech.archunit.core.domain.PackageMatchers;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTag;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import com.tngtech.archunit.library.GeneralCodingRules;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * REQ-OPS-024 architecture rules 1-7 over main classes only. Layer rules allow an empty "should" so
 * the skeleton's empty layer packages pass (rule 4 does not: MasroofApplication always exists; rule
 * 5 selects annotated fields and there are none yet); {@link MoneyRuleProbeTest} proves the money
 * rule bites.
 */
@AnalyzeClasses(packages = "eg.masroof", importOptions = ImportOption.DoNotIncludeTests.class)
@ArchTag("arch")
@ArchTag("REQ-OPS-024")
public class ArchitectureRulesTest {

  /** Rule 1: the domain is framework-free. */
  @ArchTest
  public static final ArchRule DOMAIN_IS_FRAMEWORK_FREE =
      noClasses()
          .that()
          .resideInAPackage("eg.masroof.domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.springframework..",
              "jakarta.persistence..",
              "jakarta.servlet..",
              "com.fasterxml.jackson..",
              "tools.jackson..",
              "java.net.http..")
          .allowEmptyShould(true)
          .as("rule 1: domain does not depend on Spring, JPA, servlet, Jackson or java.net.http");

  /** Rule 2a: inbound adapters do not use outbound adapters. */
  @ArchTest
  public static final ArchRule ADAPTERS_IN_NOT_TO_OUT =
      noClasses()
          .that()
          .resideInAPackage("..adapters.in..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..adapters.out..")
          .allowEmptyShould(true)
          .as("rule 2: adapters.in does not depend on adapters.out");

  /** Rule 2b: outbound adapters do not use inbound adapters. */
  @ArchTest
  public static final ArchRule ADAPTERS_OUT_NOT_TO_IN =
      noClasses()
          .that()
          .resideInAPackage("..adapters.out..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..adapters.in..")
          .allowEmptyShould(true)
          .as("rule 2: adapters.out does not depend on adapters.in");

  private static final Set<String> FLOATING_TYPES =
      Set.of("double", "float", "java.lang.Double", "java.lang.Float");

  private static final DescribedPredicate<JavaClass> FLOATING =
      DescribedPredicate.describe(
          "double/float/Double/Float", (JavaClass c) -> FLOATING_TYPES.contains(c.getName()));

  private static final Pattern NAME =
      Pattern.compile("(?i).*(amount|price|total|balance|fee|vat|tip|share).*");

  private static final PackageMatchers MONEY_PACKAGES =
      PackageMatchers.of("..money..", "..parsing..", "..dedup..", "..analytics..");

  private static final PackageMatchers NAMED_MONEY_PACKAGES =
      PackageMatchers.of("..domain..", "..application..");

  private static final DescribedPredicate<JavaMember> MONEY_SCOPE =
      DescribedPredicate.describe(
          "in money scope",
          (JavaMember m) ->
              MONEY_PACKAGES.test(pkg(m))
                  || (NAMED_MONEY_PACKAGES.test(pkg(m)) && NAME.matcher(m.getName()).matches()));

  private static String pkg(JavaMember m) {
    return m.getOwner().getPackageName();
  }

  /** Rule 3: no floating point in money scope (fields, return types, parameters). */
  @ArchTest
  public static final ArchRule MONEY_RULE =
      CompositeArchRule.of(
              fields().that(MONEY_SCOPE).should().notHaveRawType(FLOATING).allowEmptyShould(true))
          .and(
              methods()
                  .that(MONEY_SCOPE)
                  .should()
                  .notHaveRawReturnType(FLOATING)
                  .allowEmptyShould(true))
          .and(
              codeUnits()
                  .that(MONEY_SCOPE)
                  .should()
                  .notHaveRawParameterTypes(DescribedPredicate.anyElementThat(FLOATING))
                  .allowEmptyShould(true))
          .as("rule 3 (money): no double/float/Double/Float in money scope");

  /** Rule 4: no System.out / System.err (also bans printStackTrace). */
  @ArchTest
  public static final ArchRule NO_STANDARD_STREAMS =
      GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

  /** Rule 5: no field injection. */
  @ArchTest
  public static final ArchRule NO_FIELD_INJECTION =
      GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION.allowEmptyShould(true);

  /** Rule 6a: the domain is the innermost layer. */
  @ArchTest
  public static final ArchRule DOMAIN_IS_INNERMOST =
      noClasses()
          .that()
          .resideInAPackage("eg.masroof.domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("eg.masroof.application..", "eg.masroof.adapters..")
          .allowEmptyShould(true)
          .as("rule 6: domain does not depend on application or adapters");

  /** Rule 6b: the application layer does not know adapters. */
  @ArchTest
  public static final ArchRule APPLICATION_NOT_TO_ADAPTERS =
      noClasses()
          .that()
          .resideInAPackage("eg.masroof.application..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("eg.masroof.adapters..")
          .allowEmptyShould(true)
          .as("rule 6: application does not depend on adapters");

  /** Rule 8: JDBC lives only in outbound adapters. */
  @ArchTest
  public static final ArchRule JDBC_ONLY_IN_OUTBOUND_ADAPTERS =
      noClasses()
          .that()
          .resideOutsideOfPackage("eg.masroof.adapters.out..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "java.sql..", "javax.sql..", "org.springframework.jdbc..", "com.zaxxer.hikari..")
          .allowEmptyShould(true)
          .as("rule 8: JDBC only in adapters.out");

  /** Rule 7: top-level adapter slices (in.rest, out.persistence, ...) are independent. */
  @ArchTest
  public static final ArchRule ADAPTER_SLICES_INDEPENDENT =
      slices()
          .matching("eg.masroof.adapters.(*).(*)..")
          .should()
          .notDependOnEachOther()
          .allowEmptyShould(true)
          .as("rule 7: adapter slices adapters.(in|out).(*) do not depend on each other");
}
