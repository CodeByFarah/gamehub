package com.gamehub.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The architecture, enforced as tests rather than as documentation.
 *
 * <h2>Why this instead of Gradle subprojects</h2>
 * Subprojects would enforce these boundaries at compile time, which is
 * stronger. The cost is a build graph every reviewer has to learn, and a
 * module split that is painful to change once code exists.
 *
 * <p>ArchUnit gives the same enforcement as a failing test whose message
 * names the offending class and the rule it broke. It runs in seconds, needs
 * no infrastructure, and the rules read as a statement of the architecture.
 * When a boundary genuinely needs to move, the rule is edited deliberately
 * rather than worked around.
 *
 * <p>Reasoning recorded in docs/adr/ADR-006-modular-monolith.md.
 */
@AnalyzeClasses(
        packages = "com.gamehub",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /**
     * The central rule. Domain is the innermost layer and depends on nothing
     * but the JDK.
     *
     * <p>This is what keeps the matchmaking engine, the leaderboard codec and
     * the recommendation scorer testable with no container, no mocks and no
     * database. The moment one of them imports a repository, that property is
     * gone and those tests become integration tests.
     */
    @ArchTest
    static final ArchRule domainDependsOnNothing =
            noClasses().that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(
                            "..api..", "..application..", "..infrastructure..", "..config..")
                    .because("the domain is the innermost layer and must stay independently "
                            + "testable without Spring, a database or a network");

    /**
     * No framework in the domain either.
     *
     * <p>Spring, JPA and Hibernate annotations on a domain type quietly turn
     * it into a persistence model, and its shape then starts being driven by
     * what the framework needs rather than by the problem.
     *
     * <p>Jackson is a deliberate exception: the event records in
     * {@code domain.event} carry polymorphic type information, because the
     * alternative is a parallel hierarchy of near-identical DTOs whose only
     * purpose is to be serialised.
     */
    @ArchTest
    static final ArchRule domainIsFrameworkFree =
            noClasses().that().resideInAPackage("..domain..")
                    .and().resideOutsideOfPackage("..domain.event..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(
                            "org.springframework..", "jakarta.persistence..",
                            "com.fasterxml.jackson..", "org.hibernate..")
                    .because("domain types model the problem, not the frameworks that "
                            + "happen to carry them");

    /**
     * Controllers must not reach past the application layer.
     *
     * <p>A controller holding a repository ends up holding business logic,
     * because the logic has to live somewhere and the controller is where the
     * data arrived. That logic is then reachable only through HTTP, so it can
     * only be tested through HTTP.
     */
    @ArchTest
    static final ArchRule controllersDoNotTouchPersistence =
            noClasses().that().resideInAPackage("..api.controller..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("..infrastructure.persistence..")
                    .because("controllers delegate to services; a controller holding a "
                            + "repository is a controller holding business logic");

    /**
     * The application layer depends on ports, not concrete adapters.
     *
     * <p>{@code AiService} talks to the {@code AiClient} interface and has no
     * idea Gemini exists. That is what lets the deterministic fallback be a
     * real implementation rather than a branch, and what lets every test run
     * with no API key.
     */
    @ArchTest
    static final ArchRule applicationUsesPortsForAi =
            noClasses().that().resideInAPackage("..application.service..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("..infrastructure.ai..")
                    .because("the application depends on the AiClient port so the provider "
                            + "can be swapped or absent entirely");

    /**
     * No console output.
     *
     * <p>It bypasses the logging configuration, so it carries no level, no
     * timestamp and, critically, no trace id. A print statement in production
     * is a line nobody can correlate with anything.
     */
    @ArchTest
    static final ArchRule noConsoleOutput =
            noClasses().should().accessField(System.class, "out")
                    .orShould().accessField(System.class, "err")
                    .because("logging goes through SLF4J so every line carries a trace id");

    /**
     * Field injection is banned.
     *
     * <p>A field-injected class cannot be constructed in a plain unit test
     * without reflection, and it hides how many collaborators it really has.
     * A constructor with nine parameters is uncomfortable to read, which is
     * exactly the feedback a class with nine dependencies should be giving.
     */
    @ArchTest
    static final ArchRule noFieldInjection =
            noClasses().should().beAnnotatedWith(
                            "org.springframework.beans.factory.annotation.Autowired")
                    .because("constructor injection keeps classes testable without a container "
                            + "and makes an overgrown dependency list visible");

    /**
     * Every Spring Data repository lives in one package.
     *
     * <p>Keeps the set of database entry points enumerable. A repository
     * declared next to the service that uses it is invisible in review.
     */
    @ArchTest
    static final ArchRule repositoriesAreGroupedTogether =
            classes().that().haveSimpleNameEndingWith("Repository")
                    .should().resideInAPackage("..infrastructure.persistence.repository..")
                    .because("the full set of database entry points should be readable in "
                            + "one directory listing");
}
