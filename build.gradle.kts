import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.net.URI
import java.io.InputStream
import java.io.FileOutputStream

plugins {
    id("java")
    id("com.gradleup.shadow") version "9.0.0-rc2"
    id("xyz.jpenilla.run-paper") version "3.0.2"
}

group = "dev.branzx"
version = "1.0.0"
val pluginVersion = version.toString()

repositories {
    mavenCentral()
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
    maven {
        name = "citizens"
        url = uri("https://maven.citizensnpcs.co/repo")
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.+")
    compileOnly("net.citizensnpcs:citizens-main:2.0.43-SNAPSHOT") {
        exclude(group = "*", module = "*")
    }

    implementation("com.zaxxer:HikariCP:6.2.1")
    implementation("com.mysql:mysql-connector-j:9.1.0")
    // Not relocated: the native-library loader resolves resources by the
    // original org.sqlite package path.
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")

    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.mockito:mockito-core:5.17.0")
    testImplementation("io.papermc.paper:paper-api:26.2.build.+")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-Dnet.bytebuddy.experimental=true", "--enable-native-access=ALL-UNNAMED")
}

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    relocate("com.zaxxer.hikari", "dev.branzx.idlefarm.libs.hikari")
    relocate("com.mysql", "dev.branzx.idlefarm.libs.mysql")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to pluginVersion)
    }
}

val downloadCitizens = tasks.register("downloadCitizens") {
    val url = URI("https://ci.citizensnpcs.co/job/Citizens2/4220/artifact/dist/target/Citizens-2.0.43-b4220.jar").toURL()
    val destination = layout.projectDirectory.file("run/plugins/Citizens.jar").asFile
    outputs.file(destination)
    doLast {
        // Clean up old main jar if present to avoid conflicts
        val pluginsDir = layout.projectDirectory.dir("run/plugins").asFile
        pluginsDir.listFiles()?.forEach { file ->
            if (file.name.startsWith("citizens-main-") && file.name.endsWith(".jar")) {
                file.delete()
            }
        }
        if (!destination.exists()) {
            destination.parentFile.mkdirs()
            println("Downloading Citizens from $url...")
            url.openStream().use { input ->
                FileOutputStream(destination).use { output ->
                    input.copyTo(output)
                }
            }
            println("Downloaded Citizens to ${destination.absolutePath}")
        }
    }
}

tasks.runServer {
    minecraftVersion("26.2")
    jvmArgs("-Dcom.mojang.eula.agree=true", "--enable-native-access=ALL-UNNAMED")
    dependsOn(downloadCitizens)
}
