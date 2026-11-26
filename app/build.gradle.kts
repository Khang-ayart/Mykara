plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mykara.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mykara.app"
        minSdk = 29
        targetSdk = 34
        // Số phiên bản của bản phát hành: sửa hai dòng này mỗi lần ra bản mới.
        val releaseCode = 1
        val releaseName = "1.0.0"
        // CI (bản dựng thử trên GitHub) đặt GITHUB_RUN_NUMBER: số lớn hơn mọi bản phát hành, nên luôn cài đè được.
        val ci = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
        versionCode = if (ci != null) 1000 + ci else releaseCode
        versionName = if (ci != null) "$releaseName-ci.$ci" else releaseName
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
