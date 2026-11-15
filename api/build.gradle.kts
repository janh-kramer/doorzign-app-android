import org.openapitools.generator.gradle.plugin.tasks.GenerateTask

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.openapi.generator)
}

kotlin { jvmToolchain(17) }

// Vertrag: Bundle aus dem Koordinations-Repo (scripts/build-contract.sh); im Container unter /contract eingehängt.
val contractFile: String = System.getenv("DOORZIGN_CONTRACT")
    ?: rootProject.file("../doorzign-app-coordination/contract/dist/mobile-v1.yaml").absolutePath
val generatedDir = layout.buildDirectory.dir("generated/openapi")

val generateApi = tasks.register<GenerateTask>("generateApi") {
    generatorName.set("kotlin")
    library.set("jvm-okhttp4")
    inputSpec.set(contractFile)
    outputDir.set(generatedDir.map { it.asFile.absolutePath })
    packageName.set("de.doorzign.app.api")
    apiPackage.set("de.doorzign.app.api.endpoints")
    modelPackage.set("de.doorzign.app.api.model")
    cleanupOutput.set(true)
    validateSpec.set(false) // OpenAPI 3.1 wird vom Validator noch nicht vollständig unterstützt
    configOptions.set(
        mapOf(
            "serializationLibrary" to "kotlinx_serialization",
            "dateLibrary" to "string",
            "enumPropertyNaming" to "UPPERCASE",
            "sourceFolder" to "src/main/kotlin",
            "omitGradleWrapper" to "true",
            "useSettingsGradle" to "false",
        )
    )
    // Optionale Felder ohne Wert weglassen statt `null` zu senden (Vertrag: z. B. `provider` ist string, nicht nullable).
    doLast {
        val f = generatedDir.get().file("src/main/kotlin/de/doorzign/app/api/infrastructure/Serializer.kt").asFile
        val patched = f.readText().replace("isLenient = true", "isLenient = true\n            explicitNulls = false")
        check(patched.contains("explicitNulls = false")) { "Serializer.kt konnte nicht angepasst werden" }
        f.writeText(patched)
    }
    // `System` kollidiert mit java.lang.System.
    modelNameMappings.set(mapOf("System" to "SystemInfo"))
    // SlotContent (oneOf mit Diskriminator) wird von Hand als sealed class implementiert.
    schemaMappings.set(
        mapOf(
            "SlotContent" to "de.doorzign.app.core.SlotContent",
            "TextContent" to "de.doorzign.app.core.TextContent",
            "ImageContent" to "de.doorzign.app.core.SlotContent.Image",
            "CalendarContent" to "de.doorzign.app.core.SlotContent.Calendar",
            "DeactivatedContent" to "de.doorzign.app.core.SlotContent.Deactivated",
        )
    )
}

sourceSets.main {
    kotlin.srcDir(generatedDir.map { it.dir("src/main/kotlin") })
}
tasks.named("compileKotlin") { dependsOn(generateApi) }

dependencies {
    api(libs.okhttp)
    api(libs.serialization.json)
    api(libs.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    testImplementation(libs.coroutines.test)
    testImplementation(kotlin("test-junit"))
}

tasks.test {
    // Smoke-Test gegen den Prism-Mock nur, wenn PRISM_URL gesetzt ist (z. B. http://host.docker.internal:4010)
    environment("PRISM_URL", System.getenv("PRISM_URL") ?: "")
    environment("E2E_EDGE_URL", System.getenv("E2E_EDGE_URL") ?: "")
    environment("E2E_EDGE_FP", System.getenv("E2E_EDGE_FP") ?: "")
    testLogging { events("passed", "skipped", "failed"); showStandardStreams = true }
    outputs.upToDateWhen { false }
}
