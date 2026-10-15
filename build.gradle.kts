plugins {
    base
    id("io.papermc.paperweight.userdev") version "2.0.0-SNAPSHOT" apply false
    id("com.gradleup.shadow") version "9.4.1" apply false
}

group = "io.github.jakubrasken.stayloaded"
version = providers.gradleProperty("version").get()

allprojects {
    group = rootProject.group
    version = rootProject.version

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

subprojects {
    plugins.withId("java") {
        extensions.configure<JavaPluginExtension> {
            withSourcesJar()
        }

        tasks.withType<JavaCompile>().configureEach {
            options.encoding = "UTF-8"
            options.release = 21
            options.compilerArgs.add("-Xlint:all")
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }
    }
}

tasks.register("releaseArtifacts") {
    group = "build"
    description = "Builds the distributable jars for every supported Minecraft version."
    dependsOn(":paper-1_21_11:reobfJar", ":paper-26_2:shadowJar")
}
