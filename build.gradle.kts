plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.spring") version "2.3.21"
    id("org.springframework.boot") version "4.1.2"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
}

group = "io.github.gihoon1123"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
    maven { url = uri("https://hyperledger.jfrog.io/artifactory/besu-maven/") }
}

dependencies {
    implementation("org.hyperledger.besu:besu-datatypes:26.2.0")
    implementation("io.consensys.tuweni:tuweni-bytes:2.7.2")
    implementation("io.consensys.tuweni:tuweni-units:2.7.2")
    implementation("org.hyperledger.besu:besu-evm:26.2.0")
    implementation("org.iq80.leveldb:leveldb:0.12")
    implementation("org.bouncycastle:bcprov-jdk18on:1.83")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.2.0")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    testImplementation("io.mockk:mockk:1.14.12")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

configurations.configureEach {
    exclude(group = "org.apache.logging.log4j", module = "log4j-slf4j2-impl")
}

springBoot {
    mainClass.set("io.github.gihoon1123.chain.ChainApplicationKt")
}

tasks.register<JavaExec>("generateGenesis") {
    group = "application"
    description = "Generate isolated local-development accounts without printing private keys"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.gihoon1123.chain.GenesisTool")
    args(providers.gradleProperty("output").getOrElse("local-genesis"), providers.gradleProperty("accounts").getOrElse("256"))
}
