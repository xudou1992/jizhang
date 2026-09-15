plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.jianji.jizhang"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.jianji.jizhang"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 6
        versionName = "1.0.5"
        resourceConfigurations += listOf("zh-rCN", "zh-rTW", "en")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // 设置页要显示 BuildConfig.VERSION_NAME —— 别再手写版本号，
        // 之前那里硬编码着「1.0.0」，而实际已经是 1.0.3。
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // AGP 对 debuggable 变体默认写「未压缩 dex」以加快增量安装，
        // 这会凭空让 APK 大一倍（实测 58MB 变 28MB 就是因为没压缩）。
        // 我们是手动装机，不需要增量安装，直接压缩。
        dex {
            useLegacyPackaging = true
        }
    }

    testOptions {
        unitTests.all {
            // 把 schema 目录告诉单测，让 LedgerSchemaTest 能校验导出的 schema 与实体一致。
            it.systemProperty("jizhang.schemaDir", "$projectDir/schemas")
        }
    }
}

ksp {
    // 导出 Room schema 到 app/schemas/ 并提交进仓库。
    //
    // 此前 exportSchema = false，schema 历史一点没留：将来给实体加一个字段，
    // 既没有「上一版长什么样」的基线可写 Migration，又因为挂着
    // fallbackToDestructiveMigration() 而**静默清空整个库**。
    // 打开导出后，每次构建都会落一份 <版本>.json，加字段时照它写 Migration 即可。
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    // 版本对齐：material3 会传递依赖到 1.6.0，而缓存里只有 1.6.8（见 libs.versions.toml 注释）
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.material.ripple)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.core)

    // 纯 JVM 单测：金额解析、颜色 ↔ Long、备份命名正则、远端时间兜底链。
    // 这些都是纯函数，几行断言就能永久拦住回归，不需要设备。
    testImplementation(libs.junit)
}
