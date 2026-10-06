val paperVersion = providers.gradleProperty("paperVersion").getOrElse("26.3.build.157-beta")

plugins {
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":spigot"))
    implementation(project(":paper"))
    compileOnly("io.papermc.paper:paper-api:$paperVersion")
}

tasks.shadowJar {
    archiveBaseName.set("UltimateShop")
    archiveVersion.set(project.version.toString())
    archiveClassifier.set("")

    relocate("org.bstats", "cn.superiormc.ultimateshop.bstats")
    relocate("com.zaxxer.hikari", "cn.superiormc.ultimateshop.libs.hikari")
    relocate("com.cronutils", "cn.superiormc.ultimateshop.libs.cronutils")
    relocate("net.momirealms.sparrow", "cn.superiormc.ultimateshop.libs.sparrow")
    relocate("org.json", "cn.superiormc.ultimateshop.libs.json")
    relocate("org.slf4j", "cn.superiormc.ultimateshop.libs.slf4j")
    relocate("cn.gtemc.itembridge", "cn.superiormc.ultimateshop.libs.itembridge")

    mergeServiceFiles()
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.processResources {
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand(project.properties + mapOf("revision" to project.version.toString()))
    }
}
