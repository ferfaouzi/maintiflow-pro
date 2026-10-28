// Serveur MaintiFlow embarqué (mode « téléphone maître ») : Kotlin pur, sans dépendance Android,
// pour être testé sur la JVM. L'application fournit l'accès SQLite et le rendu PDF.
plugins {
    `java-test-fixtures`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.sqlite.jdbc)
    // JdbcSql : accès SQLite sur la JVM, partagé avec les tests de l'application
    testFixturesImplementation(libs.sqlite.jdbc)
}
