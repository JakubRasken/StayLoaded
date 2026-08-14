plugins {
    java
    id("io.papermc.paperweight.userdev")
    id("com.gradleup.shadow")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
}

dependencies {
    paperweight.paperDevBundle("26.2.build.+")
    implementation(project(":paper-common"))
}

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
    archiveClassifier = "mc26.2"
    mergeServiceFiles()
    manifest.attributes("paperweight-mappings-namespace" to "mojang")
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}
