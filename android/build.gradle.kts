// 根工程只声明插件版本，实际配置在 app/build.gradle.kts。
// 版本唯一来源：gradle/libs.versions.toml
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.ksp) apply false
}
