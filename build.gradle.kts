plugins {
    java
}

allprojects {
    group = "app.litemazica"

    // Only supply a default when the build wasn't given one. A plain assignment
    // here would silently clobber `-Pversion=1.2.3`, and the release workflow
    // depends on that override reaching plugin.yml.
    if (version == Project.DEFAULT_VERSION) {
        version = "1.0.0-SNAPSHOT"
    }
}

subprojects {
    apply(plugin = "java")

    repositories {
        mavenCentral()
    }

    dependencies {
        "testImplementation"("org.junit.jupiter:junit-jupiter:5.10.2")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<JavaCompile>().configureEach {
        // Java 17 bytecode: the floor for Minecraft 1.20. Using `release` rather
        // than a toolchain means any modern JDK can build this.
        options.release.set(17)
        options.encoding = "UTF-8"
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
        }
    }
}
