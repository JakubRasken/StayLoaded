plugins {
    `java-library`
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

dependencies {
    implementation("com.google.code.gson:gson:2.14.0")
    compileOnly("net.java.dev.jna:jna:5.17.0")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("net.java.dev.jna:jna:5.17.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
