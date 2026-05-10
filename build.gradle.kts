plugins {
    java
}

group = "uk.xynex"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.5-R0.1-SNAPSHOT")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") {
        exclude(group = "org.bukkit", module = "bukkit")
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks {
    register("paperweightUserdevSetup") {
        group = "paperweight"
        description = "Compatibility no-op: this plugin uses the Paper API only and does not need paperweight userdev setup."
    }

    compileJava {
        options.encoding = "UTF-8"
    }
}
