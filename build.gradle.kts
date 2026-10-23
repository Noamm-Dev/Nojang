repositories {
    mavenCentral()
    google()
}

plugins {
    kotlin("multiplatform") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
}

val ktorVersion = property("ktor.version")

kotlin {
    linuxX64("native").apply {
        binaries {
            executable {
                entryPoint = "$group.main"
            }
        }
    }

    sourceSets {
        getByName("nativeMain") {
            dependencies {
                implementation("io.ktor:ktor-server-core:$ktorVersion")
                implementation("io.ktor:ktor-server-cio:$ktorVersion")
                implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
                implementation("io.ktor:ktor-server-forwarded-header:$ktorVersion")
                implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
                implementation("io.ktor:ktor-client-core:$ktorVersion")
                implementation("io.ktor:ktor-client-curl:$ktorVersion")
                implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
                implementation("io.ktor:ktor-client-encoding:$ktorVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
                implementation("androidx.sqlite:sqlite-bundled:2.7.1")
            }
        }
    }
}
