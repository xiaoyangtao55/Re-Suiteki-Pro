import com.google.protobuf.gradle.id

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.protobuf)
}

android {
    namespace = "com.github.sky130.suiteki.pro"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.github.sky130.suiteki.pro"
        minSdk = 21
        targetSdk = 34
        versionCode = 4
        versionName = "0.1.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }

    buildTypes {
        release {
            // 本仓库没有发布用的 keystore，用 debug 证书签一个可安装的 release 包。
            // 体积比 debug 小很多（资源收缩生效），适合对外发测试版。
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = true
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-DEV"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.12"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    // 不要再把 protobuf.generatedFilesBaseDir 整个目录塞进 debug 的 java srcDir：
    // 它下面同时有 main/java 和 debug/java，而 protobuf 插件已经把当前变体那一个
    // 加进 classpath 了，再加父目录就是同一个 XiaomiProto 出现两次，
    // javac 直接报 duplicate class（增量构建命中缓存时才会躲过去）。

    testOptions {
        unitTests {
            // 协议解析里散落着 android.util.Log，不打开这个开关，JVM 单测会直接抛
            // "Method w in android.util.Log not mocked"
            isReturnDefaultValues = true
        }
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)


    implementation(libs.okhttp)
    implementation(libs.okio)



    implementation(libs.gson)
    implementation(libs.flexbox)
    debugImplementation(libs.ui.tooling)


    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.runtime)

    implementation(libs.materialkolor)


    implementation(libs.material.icon)
    implementation(libs.navigation.compose)
    implementation(libs.navigation.animation)
    implementation(libs.fastble)

    implementation(libs.apache.commons.lang3)

    implementation(libs.flexible.bottomsheet.material3)

    implementation(libs.protobuf.java)

    implementation(libs.compose.destinations.core)
    ksp(libs.compose.destinations.ksp)


    implementation(libs.permission.flow.compose)

    // AppCenter 后端 2025-03 已停服，MainApplication 里唯一引用它的是反射一个
    // 根本不存在的类，每次启动都抛 ClassNotFoundException。依赖一起摘掉，省体积。

    implementation(files("lib/Bouncycastle.jar"))
    implementation(kotlin("reflect"))

    testImplementation(libs.junit)
}



protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.0.0"
    }
    generateProtoTasks {
        all().configureEach {
            builtins {
                id("java") {}
            }
        }
    }
}

tasks.withType<JavaCompile> {
    options.compilerArgs.apply{
        add("-Xlint:deprecation")
        add("-Xlint:none")
    }
    options.isWarnings = false
}

afterEvaluate {
    tasks.named("kspDebugKotlin") {
        dependsOn("generateDebugProto")
    }
    tasks.named("kspReleaseKotlin") {
        dependsOn("generateReleaseProto")
    }
    tasks.named("generateReleaseProto") {
        dependsOn("compileDebugJavaWithJavac")
    }   
    tasks.named("generateDebugLintReportModel") {
        dependsOn("generateReleaseProto")
    }
    tasks.named("lintAnalyzeDebug") {
        dependsOn("generateReleaseProto")
    }
}



