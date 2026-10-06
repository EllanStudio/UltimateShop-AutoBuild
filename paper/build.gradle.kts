val paperVersion = providers.gradleProperty("paperVersion").getOrElse("26.3.build.157-beta")

dependencies {
    compileOnly("net.kyori:adventure-api:5.2.0")
    compileOnly("io.papermc.paper:paper-api:$paperVersion")
    testRuntimeOnly("io.papermc.paper:paper-api:$paperVersion")
    compileOnly(project(":core"))
}

tasks.register<JavaExec>("testSwingAnimationResolver") {
    group = "verification"
    description = "Verify old and split Paper animation component resolution"
    mainClass.set("cn.superiormc.ultimateshop.paper.utils.SwingAnimationResolverTest")
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
}
