plugins {
    `java-library`
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

dependencies {
    api(project(":core"))
    compileOnly("io.papermc.paper:paper-api:26.2.build.111-stable")
}
