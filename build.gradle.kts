import org.gradle.api.attributes.java.TargetJvmVersion

plugins {
    id("com.gradleup.shadow") version "9.4.1" apply false
}

group = "cn.superiormc.ultimateshop"
version = "4.7.5"

subprojects {
    apply(plugin = "java")

    group = rootProject.group
    version = rootProject.version

    configurations.configureEach {
        if (isCanBeResolved) {
            // Paper 26.3 publishes Java 25 metadata. Resolve against JDK 25
            // while JavaCompile below still emits Java 21-compatible bytecode.
            attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25)
        }
    }

    repositories {
        mavenCentral()
        maven("https://repo.nightexpressdev.com/releases")
        maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://repo.rosewooddev.io/repository/public/")
        maven("https://libraries.minecraft.net/")
        maven("https://oss.sonatype.org/content/groups/public/")
        maven("https://jitpack.io")
        maven("https://repo.extendedclip.com/releases/")
        maven("https://mvn.lumine.io/repository/maven-public/")
        maven("https://nexus.phoenixdevt.fr/repository/maven-public/")
        maven("https://repo.codemc.org/repository/maven-snapshots")
        maven("https://repo.codemc.org/repository/maven-public/")
        maven("https://maven.enginehub.org/repo/")
        maven("https://repo.auxilor.io/repository/maven-public/")
        maven("https://repo.oraxen.com/releases")
        maven("https://repo.techscode.com/repository/techscode-apis/")
        maven("https://repo.bsdevelopment.org/releases")
        maven("https://repo.opencollab.dev/main/")
        maven("https://s01.oss.sonatype.org/content/repositories/snapshots/")
        maven("https://nexus.bencodez.com/repository/maven-public/")
        maven("https://repo.william278.net/releases")
        maven("https://repo.glaremasters.me/repository/towny/")
        maven("https://repo.momirealms.net/snapshots")
        maven("https://repo.momirealms.net/releases")
        maven("https://repo.bg-software.com/repository/api/")
        maven("https://repo.nexomc.com/releases")
        maven("https://repo.codemc.io/repository/maven-releases/")
        maven("https://repo.lanink.cn/repository/maven-releases/")
        maven("https://repo.fancyinnovations.com/releases")
        maven("https://repo.gtemc.net/releases/")
    }

    configure<JavaPluginExtension> {
        toolchain {
            // ExcellentEconomy 2.8.0 is compiled for Java 25. Build with the
            // same JDK while keeping UltimateShop's output compatible with 21.
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release.set(21)
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
}
