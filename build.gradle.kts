import com.google.protobuf.gradle.*

plugins {
    java
    id("org.springframework.boot") version "3.3.5"
    id("io.spring.dependency-management") version "1.1.6"
    id("com.google.protobuf") version "0.9.4"
}

group = "az"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
}

// ─── Version constants ────────────────────────────────────────────────────────
val jwtVersion         = "0.12.6"
val resilience4jVersion= "2.2.0"
val grpcVersion        = "1.66.0"
val grpcSpringVersion  = "3.1.0.RELEASE"
val protobufVersion    = "3.25.5"
val testcontainersVersion = "1.20.3"

dependencies {
    // ── Spring Boot core ──────────────────────────────────────────────────────
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-graphql")
    implementation("org.springframework.boot:spring-boot-starter-security")

    // ── Database ──────────────────────────────────────────────────────────────
    runtimeOnly("org.postgresql:postgresql")

    // ── Kafka ─────────────────────────────────────────────────────────────────
    implementation("org.springframework.kafka:spring-kafka")

    // ── JWT ───────────────────────────────────────────────────────────────────
    implementation("io.jsonwebtoken:jjwt-api:$jwtVersion")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:$jwtVersion")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:$jwtVersion")

    // ── Resilience4j Circuit Breaker ──────────────────────────────────────────
    implementation("io.github.resilience4j:resilience4j-spring-boot3:$resilience4jVersion")
    implementation("io.github.resilience4j:resilience4j-circuitbreaker:$resilience4jVersion")

    // ── gRPC + Protobuf ───────────────────────────────────────────────────────
    implementation("net.devh:grpc-server-spring-boot-starter:$grpcSpringVersion")
    implementation("io.grpc:grpc-protobuf:$grpcVersion")
    implementation("io.grpc:grpc-stub:$grpcVersion")
    implementation("io.grpc:grpc-netty-shaded:$grpcVersion")
    implementation("com.google.protobuf:protobuf-java:$protobufVersion")
    implementation("jakarta.annotation:jakarta.annotation-api")
    implementation("javax.annotation:javax.annotation-api:1.3.2")

    // ── Swagger / OpenAPI ─────────────────────────────────────────────────────
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0")

    // ── Lombok ────────────────────────────────────────────────────────────────
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // ── Tests ─────────────────────────────────────────────────────────────────
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("org.springframework.graphql:spring-graphql-test")
    testImplementation("org.wiremock:wiremock-standalone:3.9.2")
    testImplementation("org.testcontainers:junit-jupiter:$testcontainersVersion")
    testImplementation("org.testcontainers:postgresql:$testcontainersVersion")
    testImplementation("org.testcontainers:kafka:$testcontainersVersion")
}

// ─── Protobuf / gRPC code generation ─────────────────────────────────────────
protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                create("grpc")
            }
        }
    }
}

// ─── Make sure generated proto sources are on the compile classpath ───────────
sourceSets {
    main {
        java {
            srcDirs(
                "build/generated/source/proto/main/java",
                "build/generated/source/proto/main/grpc"
            )
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// Skip tests by default (use -Ptest to enable)
tasks.named<Test>("test") {
    onlyIf { project.hasProperty("test") }
}
