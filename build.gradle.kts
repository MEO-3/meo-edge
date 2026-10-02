plugins {
    id("java")
    id("application")
}

group = "org.thingai.app"
version = "1.0-SNAPSHOT"

application {
    mainClass.set("org.thingai.app.meo.Main")
}

repositories {
    mavenCentral()
    flatDir {
        dirs("libs")
    }
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    implementation("org.xerial:sqlite-jdbc:3.43.2.0")
    implementation("org.slf4j:slf4j-api:2.0.9") // Logging interface of jdbc
    implementation("org.slf4j:slf4j-simple:2.0.16")
    implementation("com.google.code.gson:gson:2.13.2")

    implementation("io.javalin:javalin:7.2.3")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.20") // Javalin runtime
    implementation("org.jmdns:jmdns:3.5.12")

    implementation("com.zaxxer:HikariCP:5.1.0")

    // mqtt client
    implementation("org.eclipse.paho:org.eclipse.paho.mqttv5.client:1.2.5")

    implementation(files("libs/applicationbase.jar"))
    implementation(files("libs/edgeplatform.jar"))
    implementation(files("libs/aibase.jar"))
}

tasks.test {
    useJUnitPlatform()
}

// ---------------------------------------------------------------------------
// Packaging
//
// Produces one tarball per target architecture. Each distribution contains
// only the Java service (bin/ launcher + lib/*.jar, from installDist) and the
// Rust BLE provisioning binary for that architecture. Node-RED is NOT bundled;
// it lives in its own fork and the MQTT broker is a system dependency.
//
//   build/dist/meo-edge-linux-x86_64.tar.gz
//   build/dist/meo-edge-linux-arm64.tar.gz
//
// The Rust binaries must be built first (see the Makefile: `make ble-x86`,
// `make ble-arm`). Each task looks up the binary lazily and fails with the
// searched paths if it is missing.
// ---------------------------------------------------------------------------

val bleProjectDir = layout.projectDirectory.dir("rust/meo-helper")
val bleBinName = "meo-helper"
val packageRootDir = "meo-edge"

data class TargetDist(val name: String, val candidates: List<String>)

val targetDists = listOf(
    TargetDist(
        "linux-x86_64",
        listOf(
            "target/x86_64-unknown-linux-gnu/release/$bleBinName",
            "target/release/$bleBinName"
        )
    ),
    TargetDist(
        "linux-arm64",
        listOf(
            "target/aarch64-unknown-linux-gnu/release/$bleBinName"
        )
    )
)

fun resolveBleBinary(dist: TargetDist): java.io.File? =
    dist.candidates
        .map { bleProjectDir.file(it).asFile }
        .firstOrNull { it.isFile }

val packageTasks = targetDists.map { dist ->
    tasks.register<Tar>("package-${dist.name}") {
        group = "distribution"
        description = "Package the ${dist.name} distribution (Java service + BLE binary)."
        dependsOn("installDist")

        archiveFileName.set("$packageRootDir-${dist.name}.tar.gz")
        destinationDirectory.set(layout.buildDirectory.dir("dist"))
        compression = Compression.GZIP

        // Java service distribution (bin/ launcher + lib/*.jar).
        from(layout.buildDirectory.dir("install/meo-edge")) {
            into(packageRootDir)
        }

        // Rust BLE binary for this architecture (resolved at execution time).
        from(provider { resolveBleBinary(dist)?.let { listOf(it) } ?: emptyList() }) {
            into("$packageRootDir/ble")
            filePermissions { unix("0755") }
        }

        doFirst {
            if (resolveBleBinary(dist) == null) {
                val searched = dist.candidates.joinToString("\n  ") {
                    bleProjectDir.file(it).asFile.path
                }
                throw GradleException(
                    "BLE binary for ${dist.name} not found. Looked in:\n  $searched\n" +
                        "Build it first (e.g. 'make ble-x86' or 'make ble-arm')."
                )
            }
        }
    }
}

tasks.register("packageAll") {
    group = "distribution"
    description = "Build all per-architecture distributions."
    dependsOn(packageTasks)
}
