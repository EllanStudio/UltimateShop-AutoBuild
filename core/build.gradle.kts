dependencies {
    compileOnly(files("lib/LegacyCodesCompatibility.jar"))
    compileOnly("net.kyori:adventure-api:5.2.0")
    compileOnly("io.papermc.paper:paper-api:26.3.build.157-beta")
    implementation("org.bstats:bstats-bukkit:3.1.0")
    compileOnly("org.geysermc.floodgate:api:2.2.2-SNAPSHOT")
    implementation("com.cronutils:cron-utils:9.2.0")
    compileOnly("com.sk89q.worldguard:worldguard-bukkit:7.0.14") {
        isTransitive = false
    }
    compileOnly("com.sk89q.worldguard:worldguard-core:7.0.14") {
        isTransitive = false
    }
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.3.9") {
        isTransitive = false
    }
    compileOnly("com.sk89q.worldedit:worldedit-core:7.3.9") {
        isTransitive = false
    }
    compileOnly(files("lib/MythicLib-dist-1.7.1-20251205.145545-63.jar"))
    compileOnly("net.Indyuce:MMOItems-API:6.10.1-SNAPSHOT")
    compileOnly("com.github.LoneDev6:api-itemsadder:2.3.8")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") {
        exclude("org.bukkit", "bukkit")
    }
    compileOnly("org.black_ixx:playerpoints:3.2.4")
    compileOnly("com.willfp:EcoItems:5.10.4")
    compileOnly(files("lib/AdvancedEnchantmentsAPI.jar"))
    compileOnly("net.luckperms:api:5.4")
    compileOnly("com.github.Emibergo02:RedisEconomy:4.3.19") {
        isTransitive = false
    }
    compileOnly("io.lettuce:lettuce-core:6.4.0.RELEASE")
    compileOnly("me.soknight:peconomy:2.7.3")
    compileOnly("com.willfp:EcoArmor:8.51.0")
    compileOnly("com.willfp:libreforge:4.51.0")
    compileOnly("com.willfp:eco:6.65.4") {
        isTransitive = false
    }
    compileOnly("me.clip:placeholderapi:2.11.7")
    compileOnly("com.github.oraxen:oraxen:1.161.0") {
        isTransitive = false
    }
    compileOnly("com.github.ankhorg:NeigeItems-Kotlin:1.16.8")
    compileOnly("io.lumine:Mythic-Dist:5.9.5")
    compileOnly("su.nightexpress.excellenteconomy:ExcellentEconomy:2.8.0")
    compileOnly("me.TechsCode:UltraEconomyAPI:1.1.2")
    compileOnly("com.willfp:EcoBits:1.8.4")
    compileOnly(files("lib/RoyaleEconomyAPI.jar"))
    implementation("net.momirealms:sparrow-expr:1.0")
    compileOnly("com.bencodez:votingplugin:6.16.3") {
        isTransitive = false
    }
    compileOnly(files("lib/SCore-5.26.5.17.jar"))
    compileOnly(files("lib/ExecutableItems-7.26.5.17.jar"))
    implementation("org.json:json:20210307")
    compileOnly("cn.superiormc.mythicchanger:plugin:3.3.0")
    compileOnly("com.nexomc:nexo:1.0.0") {
        isTransitive = false
    }
    compileOnly(files("lib/Residence5.1.6.2.jar"))
    compileOnly("com.github.GriefPrevention:GriefPrevention:16.18.2")
    compileOnly("com.github.angeschossen:LandsAPI:7.11.10") {
        isTransitive = false
    }
    compileOnly("net.william278.husktowns:husktowns-bukkit:3.1")
    compileOnly("net.william278.huskclaims:huskclaims-bukkit:1.5")
    compileOnly("com.intellectualsites.plotsquared:plotsquared-core:7.4.0")
    compileOnly("com.intellectualsites.plotsquared:plotsquared-bukkit:7.4.0")
    compileOnly("com.palmergames.bukkit.towny:towny:0.101.1.0")
    compileOnly("world.bentobox:bentobox:2.7.0-SNAPSHOT") {
        isTransitive = false
    }
    compileOnly("cn.lunadeer:DominionAPI:4.7.3")
    compileOnly("de.tr7zw:item-nbt-api-plugin:2.12.3")
    compileOnly("net.momirealms:craft-engine-core:26.4-SNAPSHOT")
    compileOnly("net.momirealms:craft-engine-bukkit:26.4-SNAPSHOT")
    compileOnly("com.bgsoftware:SuperiorSkyblockAPI:2025.1")
    implementation("com.zaxxer:HikariCP:5.1.0")
    compileOnly("com.github.retrooper:packetevents-spigot:2.9.3")
    compileOnly("com.github.decentsoftware-eu:decentholograms:2.9.9")
    compileOnly("com.github.Zrips:CMILib:1.5.8.1")
    compileOnly("com.github.Zrips:CMI-API:9.8.6.4")
    compileOnly("de.oliver:FancyHolograms:2.9.1")
    implementation("cn.gtemc:itembridge:1.0.25")
}

tasks.register<JavaExec>("testMath") {
    group = "verification"
    description = "Run native Sparrow expression and SIGMA tests"
    mainClass.set("cn.superiormc.ultimateshop.utils.MathFunctionTest")
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
}

tasks.register<JavaExec>("testDecay") {
    group = "verification"
    description = "Run DecayCalculator + Article model tests"
    mainClass.set("cn.superiormc.ultimateshop.utils.DecayCalculatorTest")
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
}

tasks.register<JavaExec>("testDecayExtended") {
    group = "verification"
    description = "Run DecayCalculator property-based + simulation tests"
    mainClass.set("cn.superiormc.ultimateshop.utils.DecayCalculatorExtendedTest")
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
}

tasks.register<JavaExec>("testPureUtils") {
    group = "verification"
    description = "Run AmountVariableUtil pure function tests"
    mainClass.set("cn.superiormc.ultimateshop.utils.AmountVariableUtilPureTest")
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
}

tasks.register("testAll") {
    group = "verification"
    description = "Run all custom tests"
    dependsOn("testMath", "testDecay", "testDecayExtended", "testPureUtils")
}
