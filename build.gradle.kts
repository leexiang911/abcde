// Kotlin 从 2.0.21 升到 2.2.21。
//
// 不是为了新特性，是被迫的：litertlm 是 2026 年用 Kotlin 2.2 编译的，
// 它带进来的 kotlin-stdlib 2.2.x 元数据，2.0.21 的编译器根本读不了 ——
// 报出来是 MainActivity 某一行 "source must not be null"，
// 看着像代码问题，其实是编译器读坏 stdlib 之后的连锁崩溃。
//
// compose 插件的版本必须跟 kotlin 严格一致，它俩是同一套发布。
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
}
