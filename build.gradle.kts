repositories {
    mavenCentral()
    google()
}
plugins {
    kotlin("multiplatform") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }

kotlin {
    linuxX64("native").apply {
        binaries {
            executable {
                entryPoint = "main"
            }
        }
    }

    sourceSets {
        getByName("nativeMain") {
            dependencies {
                implementation("io.ktor:ktor-server-core:3.6.0")
                implementation("io.ktor:ktor-server-cio:3.6.0")
                implementation("io.ktor:ktor-server-content-negotiation:3.6.0")
                implementation("io.ktor:ktor-server-forwarded-header:3.6.0")
                implementation("io.ktor:ktor-serialization-kotlinx-json:3.6.0")
                implementation("io.ktor:ktor-client-core:3.6.0")
                implementation("io.ktor:ktor-client-curl:3.6.0")
                implementation("io.ktor:ktor-client-content-negotiation:3.6.0")
                implementation("io.ktor:ktor-client-encoding:3.6.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
                implementation("androidx.sqlite:sqlite-bundled:2.7.1")
            }
        }
    }
}

tasks.register<Exec>("dockerBuild") {
    group = "docker"
    description = "Builds the Linux binary and runtime image inside Docker."
    commandLine("docker", "build", "-t", "nojang", ".")
}

tasks.register<Exec>("dockerBuildDebug") {
    group = "docker"
    description = "Builds the Linux debug binary and runtime image inside Docker."
    commandLine("docker", "build", "--build-arg", "BUILD_TYPE=Debug", "-t", "nojang", ".")
}

tasks.register<Exec>("dockerStop") {
    group = "docker"
    description = "Removes the running nojang container, if any."
    commandLine("docker", "rm", "-f", "nojang")
    isIgnoreExitValue = true
}

tasks.register<Exec>("dockerRun") {
    group = "docker"
    description = "Builds the debug image and runs the server on the host network (port 8080)."
    dependsOn("dockerBuildDebug", "dockerStop")
    commandLine("docker", "run", "--rm", "--init", "--name", "nojang", "--network", "host", "nojang")
}
