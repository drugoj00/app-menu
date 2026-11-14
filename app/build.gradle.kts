import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.tvapps"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.tvapps"
        minSdk = 23
        targetSdk = 34
        versionCode = 31
        versionName = "7.0"
        // ABI НЕ ограничиваем (слово владелицы 06.11): программа должна ставиться на всё, куда ставилась раньше, в том числе на x86-приставки
        // системные строки библиотек (material/compose/activity) только на трёх языках программы
        resourceConfigurations += listOf("ru", "uk", "en")
    }

    // ключ подписи лежит в keystore/ (не публикуется, копия в C:\DIMIDIYA\ключі\TVApps); без него release собирается неподписанным
    val ksFile = rootProject.file("keystore/keystore.properties")
    val ks = Properties().apply { if (ksFile.exists()) ksFile.inputStream().use { load(it) } }
    signingConfigs {
        if (ksFile.exists()) create("release") {
            storeFile = rootProject.file("keystore/tvapps-release.jks")
            storePassword = ks.getProperty("storePassword")
            keyAlias = ks.getProperty("keyAlias")
            keyPassword = ks.getProperty("keyPassword")
        }
    }
    buildTypes {
        debug {
            buildConfigField("String", "SIGNER_SHA256", "\"\"")
            buildConfigField("String", "SIGNER_SHA256_R", "\"\"")
            // отладочная сборка подписывается тем же ключом, что и выпуск: ставится поверх установленной на приставках без удаления
            if (ksFile.exists()) signingConfig = signingConfigs.getByName("release")
        }
        release {
            // отпечаток SHA-256 нашего ключа подписи: по нему программа узнаёт, что копия не подделана (проверка только в release)
            buildConfigField("String", "SIGNER_SHA256", "\"50C8E1ECCCDF018406449C65B9C89F75C602D4B052E559822F6A9157E98178C0\"")
            // тот же отпечаток задом наперёд: для независимых проверок подписи в других местах кода (одной правкой константы их не обойти)
            buildConfigField("String", "SIGNER_SHA256_R", "\"" + "50C8E1ECCCDF018406449C65B9C89F75C602D4B052E559822F6A9157E98178C0".reversed() + "\"")
            // сжатие и оптимизация кода заметно ускоряют запуск на слабых приставках
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // подпись собственным ключом выпуска; если ключа нет, тестовая подпись
            signingConfig = if (ksFile.exists()) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }
    lint { checkReleaseBuilds = false; abortOnError = false }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging {
        resources.excludes += setOf("META-INF/*",
            // мусор из библиотек: данные PQC-шифров BouncyCastle (1,2 МБ, классов в программе нет), таблицы codec, метаданные
            "org/bouncycastle/**", "org/apache/commons/codec/**", "kotlin-tooling-metadata.json", "DebugProbesKt.bin")
        // .so сжаты внутри APK (APK меньше), при установке распаковываются в папку программы
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")
    implementation("com.github.junrar:junrar:7.5.5")
    implementation("eu.agno3.jcifs:jcifs-ng:2.1.10") { exclude(group = "org.bouncycastle") }
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
}
