plugins {
    kotlin("jvm") version  "2.4.20-RC3" 
    id("com.gradleup.shadow") version "9.6.1"
    id("xyz.jpenilla.run-paper") version "2.3.1"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

    // IntelliJ 라이브러리로만 등록돼 있던 외부 플러그인 jar (Vault, ItemsAdder, BlueMap)
    val home = System.getProperty("user.home")
    compileOnly(files(
        "$home/Desktop/plugin/Vault.jar",
        "$home/Desktop/plugin/ItemsAdder_4.0.17.jar",
        "$home/Downloads/bluemap-5.14-paper.jar"
    ))
}

kotlin {
    jvmToolchain(21)
}

tasks {
    build {
        dependsOn(shadowJar)
    }

    runServer {
        // Configure the Minecraft version for our task.
        // This is the only required configuration besides applying the plugin.
        // Your plugin's jar (or shadowJar if present) will be used automatically.
        minecraftVersion("1.21.1")
        jvmArgs("-Xms2G", "-Xmx2G")
    }

    processResources {
        val props = mapOf("version" to version )
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
}
