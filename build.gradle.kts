plugins {
    application
    alias(libs.plugins.openapi.generator)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.jspecify)
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)
    implementation(libs.jackson.databind)
    implementation(libs.jakarta.annotations.api)

    // testing
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(22)
    }
}

application {
    mainClass = "dev.kwirz.crun.App"
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

openApiGenerate {
    generatorName = "java"
    inputSpec = "${rootDir}/src/main/resources/v1.46.yaml"
    outputDir = "${layout.buildDirectory.get()}/generated/sources/openapi"

    packageName = "dev.kwirz.crun.docker.api"
    modelPackage = "dev.kwirz.crun.docker.api"

    configOptions = mapOf(
        "library" to "native",
        "serializationLibrary" to "jackson",
        "annotationLibrary" to "none",
        "openApiNullable" to "false",
        "useBeanValidation" to "false",
        "useJakartaEe" to "true",
        "supportUrlQuery" to "false",
    )

    globalProperties = mapOf(
        "models" to "",
        "apis" to "false",
        "modelDocs" to "false",
        "modelTests" to "false",
        "apiTests" to "false",
        "generateSupportingFiles" to "false",
    )
}

tasks.compileJava {
    dependsOn(tasks.openApiGenerate)
}

sourceSets {
    main {
        java {
            srcDir("${layout.buildDirectory.get()}/generated/sources/openapi/src/main/java")
        }
    }
}