import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.sqldelight)
}

sqldelight {
    databases {
        create("SpeechHelperDatabase") {
            // Avoid package segment "by" — SQLDelight generates broken `import Boolean`.
            packageName.set("tigre.speechhelper.db")
            dialect(libs.sqldelight.dialect)
            // KMP project has only jvmMain — schema lives there, not in commonMain.
            srcDirs("src/jvmMain/sqldelight")
        }
    }
}

kotlin {
    jvm()
    
    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutinesSwing)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.cio)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.mp3spi)
            implementation(libs.jsoup)
            implementation(libs.logback.classic)
            implementation(libs.sqldelight.sqlite.driver)
        }
    }
}


compose.desktop {
    application {
        mainClass = "by.tigre.speechhelper.MainKt"
        jvmArgs += listOf(
            "-Dfile.encoding=UTF-8",
            "-Dsun.stdout.encoding=UTF-8",
            "-Dsun.stderr.encoding=UTF-8",
        )

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "by.tigre.speechhelper"
            packageVersion = "1.2.0"

            macOS {
                signing {
                    sign.set(false)
                }
            }

            val iconsRoot = project.file("src/jvmMain/resources")
            macOS { iconFile.set(iconsRoot.resolve("app_icon.png")) }
            windows { iconFile.set(iconsRoot.resolve("app_icon.png")) }
            linux { iconFile.set(iconsRoot.resolve("app_icon.png")) }
        }
    }
}
