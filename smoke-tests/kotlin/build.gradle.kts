plugins {
    kotlin("jvm") version "2.0.0"
}

repositories { mavenCentral() }

val optixVersion: String by project
val optixNativePath: String by project

dependencies {
    testImplementation("io.github.lene:optix-jni:$optixVersion")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    systemProperty("java.library.path", optixNativePath)
}
