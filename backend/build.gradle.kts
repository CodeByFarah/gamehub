plugins {
    id("java")
    id("org.springframework.boot") version "3.4.2"
    id("io.spring.dependency-management") version "1.1.7"
    id("jacoco")
}

group = "com.gamehub"
version = "0.1.0"
description = "GameHub backend - gaming platform API, matchmaking, leaderboards and event pipeline"

java {
    toolchain {
        // Toolchain rather than sourceCompatibility: Gradle will locate or
        // provision a JDK 21 regardless of what the developer has on PATH,
        // so CI and laptops compile against the same bytecode level.
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

extra["testcontainersVersion"] = "1.20.4"
extra["resilience4jVersion"] = "2.2.0"
extra["springdocVersion"] = "2.7.0"
extra["jjwtVersion"] = "0.12.6"
extra["archunitVersion"] = "1.3.0"
extra["mapstructVersion"] = "1.6.3"

dependencies {

    // --- Web / API -------------------------------------------------------
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:${property("springdocVersion")}")

    // --- Persistence -----------------------------------------------------
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // --- Redis -----------------------------------------------------------
    // Lettuce (the Spring Boot default) rather than Jedis: it is netty-based
    // and its connections are thread-safe, so a single shared connection
    // serves every request thread instead of a pool per instance.
    implementation("org.springframework.boot:spring-boot-starter-data-redis")

    // --- Kafka -----------------------------------------------------------
    implementation("org.springframework.kafka:spring-kafka")

    // --- Security --------------------------------------------------------
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("io.jsonwebtoken:jjwt-api:${property("jjwtVersion")}")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:${property("jjwtVersion")}")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:${property("jjwtVersion")}")

    // --- Resilience ------------------------------------------------------
    // Circuit breaker, retry and timeout around the Gemini call. See
    // docs/ai-architecture.md for why the AI path must never take down a
    // request thread.
    implementation("io.github.resilience4j:resilience4j-spring-boot3:${property("resilience4jVersion")}")
    implementation("org.springframework.boot:spring-boot-starter-aop")

    // --- Observability ---------------------------------------------------
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")

    // --- Mapping ---------------------------------------------------------
    // Compile-time generated mappers. No reflection at runtime, and a field
    // renamed on an entity breaks the build instead of silently mapping null.
    implementation("org.mapstruct:mapstruct:${property("mapstructVersion")}")
    annotationProcessor("org.mapstruct:mapstruct-processor:${property("mapstructVersion")}")

    // --- Build-time only -------------------------------------------------
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
    developmentOnly("org.springframework.boot:spring-boot-devtools")

    // --- Test ------------------------------------------------------------
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:kafka")
    testImplementation("com.redis:testcontainers-redis:2.2.2")
    testImplementation("org.awaitility:awaitility")
    testImplementation("com.tngtech.archunit:archunit-junit5:${property("archunitVersion")}")
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")
}

dependencyManagement {
    imports {
        mavenBom("org.testcontainers:testcontainers-bom:${property("testcontainersVersion")}")
    }
}

// ---------------------------------------------------------------------------
// Test task split.
//
// `test` runs fast, hermetic tests only. `integrationTest` runs everything
// that needs Docker. Keeping them apart means a developer without Docker can
// still get a meaningful signal from ./gradlew test, and CI can cache the two
// differently. Selection is by JUnit tag, not by filename convention, so a
// misnamed class cannot quietly skip CI.
// ---------------------------------------------------------------------------
tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("integration", "concurrency")
    }
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    finalizedBy(tasks.named("jacocoTestReport"))
}

val integrationTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Runs tests tagged 'integration' or 'concurrency'. Requires Docker."
    useJUnitPlatform {
        includeTags("integration", "concurrency")
    }
    shouldRunAfter(tasks.named("test"))
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    // Testcontainers reuse cuts repeated container startup out of the inner
    // loop. Opt-in per developer via ~/.testcontainers.properties.
    systemProperty("testcontainers.reuse.enable", providers.gradleProperty("tcReuse").getOrElse("false"))
}

tasks.named("check") {
    dependsOn(integrationTest)
}

tasks.named<JacocoReport>("jacocoTestReport") {
    reports {
        xml.required = true
        html.required = true
    }
    classDirectories.setFrom(
        files(classDirectories.files.map {
            fileTree(it) {
                // Generated and framework-glue classes. Covering a MapStruct
                // implementation tells you nothing, and inflating the number
                // by including it would make the metric worthless.
                exclude(
                    "**/GameHubApplication.class",
                    "**/config/**",
                    "**/*MapperImpl.class",
                    "**/dto/**"
                )
            }
        })
    )
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(
        listOf(
            "-parameters",
            "-Xlint:all,-processing,-serial",
            "-Werror"
        )
    )
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName = "gamehub-backend.jar"
}
