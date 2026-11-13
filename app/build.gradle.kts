plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mykara.prototype"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mykara.prototype"
        minSdk = 29
        targetSdk = 34
        // CI đặt GITHUB_RUN_NUMBER: mỗi bản dựng có versionCode lớn hơn bản trước để cài đè được.
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "0.1.$build"
    }

    // Khóa ký cố định (chỉ dành cho bản thử nghiệm, không phải khóa phát hành): giúp cài đè giữ nguyên dự án đã lưu.
    signingConfigs.getByName("debug") {
        storeFile = file("debug.keystore")
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
    }

    sourceSets.getByName("main").assets.srcDirs("src/main/assets", "../data")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.0")

    testImplementation("junit:junit:4.13.2")
    // Bản org.json thật cho kiểm thử trên JVM (android.jar chỉ có bản rỗng).
    testImplementation("org.json:json:20240303")
}
