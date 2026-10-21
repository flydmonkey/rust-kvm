plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// 浏览器客户端和 Windows 服务共用仓库根目录的 web/，宿主机原样打包进 assets
val webAssetsRoot = layout.buildDirectory.dir("generated/webAssets")
val copyWebAssets by tasks.registering(Sync::class) {
    from(rootProject.file("../web"))
    into(webAssetsRoot.map { it.dir("web") })
}

android {
    namespace = "com.rustkvm.host"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets["main"].assets.srcDir(webAssetsRoot)
    testOptions { unitTests.isReturnDefaultValues = true }
}

tasks.named("preBuild") { dependsOn(copyWebAssets) }

dependencies {
    api(project(":core-protocol"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("io.ktor:ktor-server-core:3.0.3")
    implementation("io.ktor:ktor-server-netty:3.0.3")
    implementation("io.ktor:ktor-server-websockets:3.0.3")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.79")
    implementation("com.github.mik3y:usb-serial-for-android:3.8.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.ktor:ktor-server-test-host:3.0.3")
    testImplementation("io.ktor:ktor-client-websockets:3.0.3")
    testImplementation(project(":client"))
}
