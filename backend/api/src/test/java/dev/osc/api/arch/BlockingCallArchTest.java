package dev.osc.api.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * ArchUnit test suite that enforces the reactive-only constraint across all
 * production modules under dev.osc.**.
 *
 * These tests are self-verifying: ArchUnit fails the test if a violation is found.
 */
@AnalyzeClasses(
        packages = "dev.osc",
        importOptions = {ImportOption.DoNotIncludeTests.class}
)
class BlockingCallArchTest {

    @ArchTest
    static final ArchRule no_block_calls =
            noClasses()
                    .that().resideInAPackage("dev.osc..")
                    .and().resideOutsideOfPackage("dev.osc.scripting..")
                    .should().callMethodWhere(
                            com.tngtech.archunit.core.domain.JavaCall.Predicates.target(
                                    com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameMatching(
                                            "block|blockFirst|blockLast"
                                    )
                            )
                    )
                    .because("Production code must never call .block(), .blockFirst() or .blockLast() " +
                             "— use reactive operators instead.");

    @ArchTest
    static final ArchRule no_thread_sleep =
            noClasses()
                    .that().resideInAPackage("dev.osc..")
                    .should().callMethod(Thread.class, "sleep", long.class)
                    .because("Thread.sleep() blocks the event loop thread; use Mono.delay() instead.");

    @ArchTest
    static final ArchRule no_blocking_jdbc_imports =
            noClasses()
                    .that().resideInAPackage("dev.osc..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("java.sql..")
                    .because("Use R2DBC (reactive) drivers, not blocking JDBC (java.sql.*).");
}
