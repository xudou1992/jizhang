// 记账软件 · 原生重写版（单模块）
// 刻意不做的事：
//   1) 不开 enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS") —— 只有 :app 一个模块，没有 projects.xxx 可引用
//   2) 不建版本目录之外的模块 —— 60 个构建脚本 正是上一版"改不动"的根因
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "jizhang"

include(":app")
