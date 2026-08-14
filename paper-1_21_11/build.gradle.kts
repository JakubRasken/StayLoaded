import io.papermc.paperweight.userdev.ReobfArtifactConfiguration
import io.papermc.paperweight.tasks.RemapJar

plugins {
    java
    id("io.papermc.paperweight.userdev")
    id("com.gradleup.shadow")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

dependencies {
    paperweight.paperDevBundle("1.21.11-R0.1-SNAPSHOT")
    implementation(project(":paper-common"))
}

paperweight.reobfArtifactConfiguration = ReobfArtifactConfiguration.REOBF_PRODUCTION

tasks.processResources {
    val pluginVersion = project.version.toString()
    inputs.property("version", pluginVersion)
    filesMatching("plugin.yml") {
        expand("version" to pluginVersion)
    }
}

tasks.shadowJar {
    archiveBaseName = "StayLoaded-paper"
    archiveVersion = project.version.toString()
    archiveClassifier = "mc1.21.11-dev"
    mergeServiceFiles()
}

tasks.named<RemapJar>("reobfJar") {
    outputJar = layout.buildDirectory.file(
        "libs/StayLoaded-paper-${project.version}-mc1.21.11.jar"
    )
}

tasks.assemble {
    dependsOn(tasks.reobfJar)
}
