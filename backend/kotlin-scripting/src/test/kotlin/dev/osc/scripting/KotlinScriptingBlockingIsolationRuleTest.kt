package dev.osc.scripting

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test

class KotlinScriptingBlockingIsolationRuleTest {

    @Test
    fun `enforce that blocking calls exist only inside allowed scripting classes`() {
        val classes = ClassFileImporter().importPackages("dev.osc.scripting")

        val rule = noClasses()
            .that().resideInAPackage("dev.osc.scripting..")
            .and().haveSimpleNameNotEndingWith("ScriptService")
            .and().haveSimpleNameNotEndingWith("RecordOperationsImpl")
            .should().callMethodWhere(
                com.tngtech.archunit.core.domain.JavaCall.Predicates.target(
                    com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameMatching(
                        "block|blockFirst|blockLast"
                    )
                )
            )
            .because("Blocking calls are strictly restricted to classes executed on Schedulers.boundedElastic() (ADR-005)")

        rule.check(classes)
    }
}
