plugins {
    java
    id("com.gradleup.shadow") version "8.3.0"
}

group = "com.myname.multisyncstats"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") // PaperMC, for Paper and Folia APIs
    maven("https://repo.helpch.at/releases/") // PlaceholderAPI
}

dependencies {
    // Paper API includes Spigot and is recommended for modern plugin development
    compileOnly("io.papermc.paper:paper-api:1.21-R0.1-SNAPSHOT")
    // PlaceholderAPI
    compileOnly("me.clip:placeholderapi:2.11.6")
    // HikariCP for database connection pooling
    implementation("com.zaxxer:HikariCP:5.1.0")
    // MySQL Connector
    implementation("mysql:mysql-connector-java:8.0.33")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks {
    // Set the encoding for Java compilation
    withType<JavaCompile> {
        options.encoding = "UTF-8"
    }

    // Process plugin.yml to replace variables
    processResources {
        filesMatching("plugin.yml") {
            expand(project.properties)
        }
    }
}

// Directly configure the shadowJar task
tasks.shadowJar {
    mergeServiceFiles() // This is crucial for JDBC drivers and other services
    relocate("com.mysql", "com.laomaoboss.multisyncstats.lib.mysql")
    relocate("com.google.protobuf", "com.laomaoboss.multisyncstats.lib.protobuf")

    archiveBaseName.set(rootProject.name)
    archiveClassifier.set("")
    archiveVersion.set(project.version.toString())

    // 解决 Paper 服务端在 1.21+ 版本上因 remap 导致插件静默失败的 bug
    // 通过添加此 manifest 属性，可以阻止 Paper 对 jar 包进行不必要的重映射
    manifest {
        attributes["paperweight-mappings-namespace"] = "mojang+yarn"
    }
}

// The 'build' task automatically depends on 'shadowJar' when the plugin is applied,
// so explicit dependency declaration is often not needed. But if it is:
tasks.assemble {
    dependsOn(tasks.shadowJar)
} 