plugins {
    kotlin("jvm") version "1.9.22"
    kotlin("plugin.spring") version "1.9.22"
    id("org.springframework.boot") version "3.2.2"
    id("io.spring.dependency-management") version "1.1.4"
    kotlin("plugin.jpa") version "1.9.22"
}

group = "com.joysong"
version = "0.0.1-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
}

// Alibaba credentials pulls the same JAXB core classes under the legacy
// com.sun.xml.bind coordinates. Hibernate already supplies the maintained
// org.glassfish.jaxb artifact, and keeping both makes Spring Boot's executable
// jar contain two BOOT-INF/lib/jaxb-core-4.0.4.jar entries.
configurations.configureEach {
    exclude(group = "com.sun.xml.bind", module = "jaxb-core")
    exclude(group = "commons-logging", module = "commons-logging")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-cache")

    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jsoup:jsoup:1.23.1")

    // MySQL Database
    runtimeOnly("com.mysql:mysql-connector-j")

    // Flyway
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-mysql")

    // JWT
    implementation("io.jsonwebtoken:jjwt-api:0.12.3")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.3")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.3")

    // CORS
    implementation("org.springframework.boot:spring-boot-starter-actuator")
        developmentOnly("org.springframework.boot:spring-boot-devtools")

    // Google ID Token 本地验证
    implementation("com.google.api-client:google-api-client:2.7.0")

    // 阿里云 OSS
    implementation("com.aliyun.oss:aliyun-sdk-oss:3.17.4")

    // 阿里云短信 SMS
    implementation("com.aliyun:dysmsapi20170525:3.0.0")
    implementation("com.aliyun:tea-openapi:0.3.2")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("io.mockk:mockk:1.13.9")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:mysql")
    testRuntimeOnly("com.h2database:h2")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        freeCompilerArgs += "-Xjsr305=strict"
        jvmTarget = "17"
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
}

tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("mysql-integration")
    }
}

tasks.register<Test>("mysqlIntegrationTest") {
    group = "verification"
    description = "Runs integration tests against containerized MySQL"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("mysql-integration")
    }
    shouldRunAfter(tasks.named("test"))
}
