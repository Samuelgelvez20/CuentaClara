package co.cuentaclara.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(
        packages = "co.cuentaclara",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class HexagonalArchitectureTest {

    @ArchTest
    static final ArchRule domain_must_be_pure_java_without_framework_or_outer_dependencies =
            noClasses()
                    .that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat(resideInAnyPackage(
                            "org.springframework..",
                            "jakarta..",
                            "com.fasterxml..",
                            "io.jsonwebtoken..",
                            "io.swagger..",
                            "org.apache..",
                            "net.sourceforge..",
                            "..application..",
                            "..infrastructure.."
                    ));

    @ArchTest
    static final ArchRule application_must_not_depend_on_spring_jpa_or_infrastructure =
            noClasses()
                    .that().resideInAPackage("..application..")
                    .should().dependOnClassesThat(resideInAnyPackage(
                            "org.springframework..",
                            "jakarta..",
                            "com.fasterxml..",
                            "..infrastructure.."
                    ));

    @ArchTest
    static final ArchRule inbound_adapters_must_not_depend_on_persistence_adapters_or_jpa =
            noClasses()
                    .that().resideInAPackage("..infrastructure.adapter.in..")
                    .should().dependOnClassesThat(resideInAnyPackage(
                            "..infrastructure.adapter.out.persistence..",
                            "jakarta.persistence.."
                    ));

    @ArchTest
    static final ArchRule top_level_ports_must_be_interfaces =
            classes()
                    .that().resideInAnyPackage("..domain.port.in..", "..domain.port.out..")
                    .and().areTopLevelClasses()
                    .should().beInterfaces();
}
