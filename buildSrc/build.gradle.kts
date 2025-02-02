plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
}

dependencies {
    implementation("net.kyori", "indra-common", "2.0.6")
    implementation("net.minecrell:plugin-yml:0.5.1")
    implementation("gradle.plugin.com.github.johnrengelman:shadow:7.1.1")
}
