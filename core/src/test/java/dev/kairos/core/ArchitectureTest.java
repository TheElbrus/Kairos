package dev.kairos.core;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "dev.kairos", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule coreNeverDependsOnMinecraft = noClasses()
            .that().resideInAPackage("dev.kairos..")
            .should().dependOnClassesThat().resideInAPackage("net.minecraft..")
            .because("core must stay a pure Java library with zero Minecraft imports (PLAN.md 4.1, AGENTS.md)");
}
