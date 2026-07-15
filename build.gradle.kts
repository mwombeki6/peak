import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.spring") version "2.3.21"
    kotlin("kapt") version "2.3.21"

    id("org.springframework.boot") version "4.0.6"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.mwombeki"
version = "1.0.0-SNAPSHOT"
description = "Peak backend platform"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

extra["springModulithVersion"] = "2.0.6"
extra["jackson-2-bom.version"] = "2.21.4"
extra["jackson-bom.version"] = "3.1.4"
extra["tomcat.version"] = "11.0.22"
extra["postgresql.version"] = "42.7.11"

dependencies {
    // Operations and observability
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")

    // Database
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-jooq")
    implementation("org.flywaydb:flyway-database-postgresql")

    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    // Web and API
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.2")

    // Security
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation(
        "org.springframework.boot:" +
                "spring-boot-starter-security-oauth2-resource-server"
    )

    // Communications
    implementation("org.springframework.boot:spring-boot-starter-mail")

    // Reporting and S3-compatible private object storage
    implementation("org.apache.pdfbox:pdfbox:3.0.7")
    implementation("io.minio:minio:9.0.3")
    implementation("com.github.librepdf:openpdf-fonts-extra:3.0.5")

    // Kotlin and JSON
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    implementation("tools.jackson.module:jackson-module-kotlin")

    // Modular monolith
    implementation(
        "org.springframework.modulith:spring-modulith-starter-core"
    )

    runtimeOnly(
        "org.springframework.modulith:spring-modulith-starter-insight"
    )

    // Local development
    developmentOnly("org.springframework.boot:spring-boot-devtools")
    developmentOnly("org.springframework.boot:spring-boot-docker-compose")

    // Configuration metadata for Kotlin @ConfigurationProperties
    add(
        "kapt",
        "org.springframework.boot:spring-boot-configuration-processor"
    )

    // Spring Boot tests
    testImplementation(
        "org.springframework.boot:spring-boot-starter-actuator-test"
    )
    testImplementation(
        "org.springframework.boot:spring-boot-starter-flyway-test"
    )
    testImplementation(
        "org.springframework.boot:spring-boot-starter-jdbc-test"
    )
    testImplementation(
        "org.springframework.boot:spring-boot-starter-jooq-test"
    )
    testImplementation(
        "org.springframework.boot:spring-boot-starter-mail-test"
    )
    testImplementation(
        "org.springframework.boot:spring-boot-starter-opentelemetry-test"
    )
    testImplementation(
        "org.springframework.boot:" +
                "spring-boot-starter-security-oauth2-resource-server-test"
    )
    testImplementation(
        "org.springframework.boot:spring-boot-starter-security-test"
    )
    testImplementation(
        "org.springframework.boot:spring-boot-starter-validation-test"
    )
    testImplementation(
        "org.springframework.boot:spring-boot-starter-webmvc-test"
    )
    testImplementation(
        "org.springframework.boot:spring-boot-starter-websocket-test"
    )

    // Kotlin testing
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")

    // Modulith testing and documentation
    testImplementation(
        "org.springframework.modulith:spring-modulith-starter-test"
    )

    // Testcontainers
    testImplementation(
        "org.springframework.boot:spring-boot-testcontainers"
    )
    testImplementation(
        "org.testcontainers:testcontainers-junit-jupiter"
    )
    testImplementation(
        "org.testcontainers:testcontainers-postgresql"
    )
    testImplementation(
        "org.testcontainers:testcontainers-grafana"
    )

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
    imports {
        mavenBom(
            "org.springframework.modulith:" +
                    "spring-modulith-bom:" +
                    property("springModulithVersion")
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
        allWarningsAsErrors.set(true)

        freeCompilerArgs.addAll(
            "-Wextra",
            "-Xjsr305=strict",
            "-Xannotation-default-target=param-property"
        )
    }
}

kapt {
    includeCompileClasspath = false
}

tasks.withType<Test>().configureEach {
    systemProperty(
        "spring.profiles.active",
        System.getProperty("spring.profiles.active") ?: "test",
    )
    System.getProperty("peak.openapi.write-baseline")?.let { value ->
        systemProperty("peak.openapi.write-baseline", value)
    }
    val runtimeDir = System.getenv("XDG_RUNTIME_DIR")
    val podmanSocket = runtimeDir?.let {
        file("$it/podman/podman.sock")
    }

    if (System.getenv("DOCKER_HOST").isNullOrBlank() && podmanSocket?.exists() == true) {
        environment("DOCKER_HOST", "unix://${podmanSocket.absolutePath}")
        environment(
            "TESTCONTAINERS_RYUK_DISABLED",
            System.getenv("TESTCONTAINERS_RYUK_DISABLED") ?: "true",
        )
    }

    useJUnitPlatform()
}

tasks.bootJar {
    archiveFileName.set("peak.jar")
}

tasks.register<Exec>("podmanComposeUp") {
    group = "application"
    description = "Starts Peak local infrastructure with Podman Compose."

    commandLine("podman", "compose", "-f", "compose.yaml", "up", "-d", "postgres")
}

tasks.register<Exec>("podmanComposeObservabilityUp") {
    group = "application"
    description = "Starts Peak local infrastructure and observability with Podman Compose."

    commandLine(
        "podman",
        "compose",
        "-f",
        "compose.yaml",
        "--profile",
        "observability",
        "up",
        "-d",
    )
}

tasks.register<Exec>("podmanComposeDown") {
    group = "application"
    description = "Stops Peak local infrastructure started with Podman Compose."

    commandLine("podman", "compose", "-f", "compose.yaml", "down")
}
