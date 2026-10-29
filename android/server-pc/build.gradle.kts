// Serveur MaintiFlow pour PC Windows / Linux ou Raspberry Pi : le même serveur que le téléphone maître,
// avec SQLite (JDBC) et un rendu PDF PDFBox. Produit un seul fichier maintiflow-server.jar (Java 17+).
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":server"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.sqlite.jdbc)
    implementation(libs.pdfbox)
    testImplementation(libs.junit)
}

application {
    mainClass.set("com.maintiflow.pc.MainKt")
    applicationName = "maintiflow-server"
}

// Fichier unique exécutable : java -jar maintiflow-server.jar
val fatJar by tasks.registering(Jar::class) {
    group = "build"
    description = "Construit maintiflow-server.jar avec toutes ses dépendances"
    archiveFileName.set("maintiflow-server.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes("Main-Class" to "com.maintiflow.pc.MainKt", "Implementation-Version" to project.version) }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/9/module-info.class", "module-info.class")
    }
}
