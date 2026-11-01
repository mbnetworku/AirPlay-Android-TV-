// Vendored from github.com/serezhka/java-airplay (MIT) — protocol + server modules,
// patched for Android (no java.util.HexFormat, no Stream.toList, hidden MAC fallback).
plugins {
    id("java-library")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

dependencies {
    api("org.slf4j:slf4j-api:2.0.16")
    implementation("com.googlecode.plist:dd-plist:1.28")
    implementation("net.i2p.crypto:eddsa:0.3.0")
    implementation("org.whispersystems:curve25519-java:0.5.0")
    implementation("org.jmdns:jmdns:3.5.12")
    implementation("io.netty:netty-codec-http:4.1.115.Final")
    implementation("io.lindstrom:m3u8-parser:0.30")

    compileOnly("org.projectlombok:lombok:1.18.34")
    annotationProcessor("org.projectlombok:lombok:1.18.34")
}
