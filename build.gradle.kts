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
    // 1.21.4 API로 빌드 (무기 모션이 1.21.4의 아이템 데이터 컴포넌트를 씀).
    // plugin.yml api-version은 1.21.1 그대로라 1.21.1 서버에서도 켜지고, 그때는 무기 모션만 꺼짐 (addon/Addons.kt)
    // 바뀐 속성 이름은 Attrs.kt에서 서버에 맞게 찾음
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")

    // BetterModel (3인칭 무기 모션). 2.x = Java 21 (3.x는 Java 25가 필요해서 1.21.4 서버에는 2.x)
    compileOnly("io.github.toxicity188:bettermodel-bukkit-api:2.2.0")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

    // 외부 플러그인 jar (Vault, ItemsAdder, BlueMap) — 프로젝트 libs 폴더에 같이 보관
    compileOnly(fileTree("libs") { include("*.jar") })
}

kotlin {
    jvmToolchain(21)
}

tasks {
    build {
        dependsOn(shadowJar)
    }

    // 서버에 올릴 jar: Kotlin·설정 파일(quest.yml, help.yml)까지 다 들어간 jar를 바탕화면 plugin 폴더에 Digriss.jar로 복사
    // (IntelliJ "Build Artifacts"로 만든 jar는 설정 파일이 빠져서 서버에서 켜지지 않음)
    val deployJar by registering(Copy::class) {
        from(shadowJar.flatMap { it.archiveFile })
        into("${System.getProperty("user.home")}/Desktop/plugin")
        rename { "Digriss.jar" }
    }
    shadowJar {
        finalizedBy(deployJar)
    }

    runServer {
        // Configure the Minecraft version for our task.
        // This is the only required configuration besides applying the plugin.
        // Your plugin's jar (or shadowJar if present) will be used automatically.
        minecraftVersion("1.21.4") // 실제 서버 버전 (애니메이션 플러그인이 1.21.4 전용)
        jvmArgs("-Xms2G", "-Xmx2G")
    }

    processResources {
        val props = mapOf("version" to version )
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
}
