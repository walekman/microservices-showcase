package com.showcase.account;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Enforces the ports-and-adapters layout -- see docs/hexagonal-architecture-account-service.md.
 * Production code only: tests may reach across layers (AccountSecurityIT mocks the application
 * service directly).
 */
@AnalyzeClasses(packages = "com.showcase.account", importOptions = ImportOption.DoNotIncludeTests.class)
class HexagonalArchitectureTest {

    private static final String DOMAIN = "com.showcase.account.domain..";
    private static final String APPLICATION = "com.showcase.account.application..";
    private static final String PORTS = "com.showcase.account.application.port..";
    private static final String ADAPTERS = "com.showcase.account.adapter..";
    private static final String WEB_ADAPTER = "com.showcase.account.adapter.in..";
    private static final String PERSISTENCE_ADAPTER = "com.showcase.account.adapter.out..";

    @ArchTest
    static final ArchRule domainIsPlainJava = classes()
            .that().resideInAPackage(DOMAIN)
            // lombok: only its @Generated marker on the getters it writes at compile time.
            .should().onlyDependOnClassesThat().resideInAnyPackage("java..", "lombok..", DOMAIN)
            .because("the domain model must not know about any framework, port or adapter");

    @ArchTest
    static final ArchRule applicationDependsOnlyOnTheDomain = classes()
            .that().resideInAPackage(APPLICATION)
            .should().onlyDependOnClassesThat().resideInAnyPackage("java..", DOMAIN, APPLICATION)
            .because("the application core reaches storage and transactions only through its out-ports");

    @ArchTest
    static final ArchRule adaptersUseTheCoreOnlyThroughPorts = noClasses()
            .that().resideInAPackage(ADAPTERS)
            .should().dependOnClassesThat().resideInAPackage("com.showcase.account.application")
            .because("an adapter talks to the core through a port, never to its implementation; "
                    + "config.AccountApplicationConfig is the one place that wires them together");

    @ArchTest
    static final ArchRule inboundAdaptersDoNotUseOutboundAdapters = noClasses()
            .that().resideInAPackage(WEB_ADAPTER)
            .should().dependOnClassesThat().resideInAPackage(PERSISTENCE_ADAPTER);

    @ArchTest
    static final ArchRule outboundAdaptersDoNotUseInboundAdapters = noClasses()
            .that().resideInAPackage(PERSISTENCE_ADAPTER)
            .should().dependOnClassesThat().resideInAPackage(WEB_ADAPTER);

    @ArchTest
    static final ArchRule persistenceStaysInItsAdapter = noClasses()
            .that().resideOutsideOfPackage(PERSISTENCE_ADAPTER)
            .should().dependOnClassesThat().resideInAnyPackage(
                    "jakarta.persistence..", "org.hibernate..", "org.springframework.data..",
                    "org.springframework.orm..", "org.springframework.dao..", "org.springframework.transaction..")
            .because("storage technology and its exceptions are translated inside the persistence adapter");

    @ArchTest
    static final ArchRule portsAreInterfaces = classes()
            .that().resideInAPackage(PORTS)
            .should().beInterfaces();
}
