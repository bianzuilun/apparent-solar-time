// 真太阳时核心算法模块：纯 Kotlin，零平台依赖。
// 未来复用：把插件换成 kotlin("multiplatform") 加 iosArm64/macosX64/mingwX64 目标即可原样编译。
plugins {
    kotlin("jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    testImplementation(kotlin("test"))
}
