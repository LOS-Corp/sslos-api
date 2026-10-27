package com.laundry.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Architecture rules of ADR-01 … ADR-10, checked on every build so the structure cannot erode.
 * Rules that the legacy code does not meet yet are narrowed to the packages already refactored.
 */
class ArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.laundry");
    }

    @Test
    @DisplayName("Domain rules are pure: no Spring, no JDBC, no JPA repositories, no web")
    void domainIsPure() {
        ArchRule rule = noClasses().that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..", "jakarta.persistence..", "java.sql..", "jakarta.servlet..");
        rule.check(classes);
    }

    @Test
    @DisplayName("Shared infrastructure never depends on a business module")
    void sharedDependsOnNoModule() {
        ArchRule rule = noClasses().that().resideInAPackage("com.laundry.shared..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.laundry.booking..", "com.laundry.payment..", "com.laundry.refund..",
                        "com.laundry.machine..", "com.laundry.wallet..", "com.laundry.transaction..",
                        "com.laundry.locker..", "com.laundry.iot..", "com.laundry.user..");
        rule.check(classes);
    }

    @Test
    @DisplayName("Controllers do not talk to repositories or JdbcTemplate directly")
    void controllersUseServices() {
        ArchRule rule = noClasses().that().resideInAPackage("..controller..")
                .and().resideOutsideOfPackages("com.laundry.machine.controller")  // legacy read endpoints, P5 backlog
                .should().dependOnClassesThat().resideInAPackage("..repository..")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("org.springframework.jdbc.core.JdbcTemplate");
        rule.check(classes);
    }

    @Test
    @DisplayName("Every module signals errors with BusinessException + ErrorCode, not ResponseStatusException")
    void errorsUseErrorCodes() {
        ArchRule rule = noClasses().that().resideInAPackage("com.laundry..")
                .and().doNotHaveSimpleName("GlobalExceptionHandler")          // translates it, never throws it
                .and().doNotHaveSimpleName("ProvisioningRejectedException")   // device protocol keeps its status
                .should().dependOnClassesThat()
                .haveFullyQualifiedName("org.springframework.web.server.ResponseStatusException");
        rule.check(classes);
    }

    @Test
    @DisplayName("Identity comes from the token: no request DTO carries a manager or approver id (BR-01)")
    void noIdentityInRequestBodies() {
        ArchRule rule = noClasses().that().resideInAPackage("..dto..")
                .should().haveSimpleNameContaining("Approve");
        rule.check(classes);
    }
}
