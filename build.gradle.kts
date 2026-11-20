plugins {
    java
}

group = "com.powerxhost"
version = "1.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

sourceSets {
    main {
        java.setSrcDirs(listOf("Hosting"))
    }
}

tasks.register<Jar>("shadowJar") {
    group = "build"
    archiveFileName.set("PowerXhost.jar")

    from(sourceSets.main.get().output)

    manifest {
        attributes["Main-Class"] = "com.powerxhost.MakeHosterJar"
    }
}
