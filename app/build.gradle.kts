plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "ru.loftcontur.minicad"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "ru.loftcontur.minicad"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
    }
    androidResources {
        // Указываем компилятору ресурсов AAPT2 полностью игнорировать файлы desktop.ini
        ignoreAssetsPattern += ":!desktop.ini"
    }
    packaging {
        resources {
            // Исключаем файлы desktop.ini из сборки, в каких бы папках они ни появились
            excludes.add("**/desktop.ini")
            excludes.add("desktop.ini")
        }
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.navigation.fragment)
    implementation(libs.navigation.ui)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
tasks.register("deleteDesktopIni") {
    group = "build setup"
    description = "Физически удаляет файлы desktop.ini из папок проекта перед сборкой"

    doLast {
        val srcDir = file("src")
        if (srcDir.exists()) {
            srcDir.walkTopDown().forEach { file ->
                if (file.name.equals("desktop.ini", ignoreCase = true)) {
                    if (file.delete()) {
                        println("Быстрое удаление: ${file.absolutePath}")
                    }
                }
            }
        }
    }
}
// Автоматически запускаем clean перед компиляцией Kotlin и Java кода

tasks.named<Delete>("clean") {
    delete(layout.buildDirectory)
}

// Связываем автоматическую очистку перед началом компиляции кода
val cleanTask = tasks.named("clean")

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    mustRunAfter(cleanTask)
    dependsOn(cleanTask)
}

tasks.withType<JavaCompile>().configureEach {
    mustRunAfter(cleanTask)
    dependsOn(cleanTask)
}
// Заставляем задачу удаления выполняться автоматически перед началом компиляции кода
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn("deleteDesktopIni")
}
tasks.withType<JavaCompile>().configureEach {
    dependsOn("deleteDesktopIni")
}
