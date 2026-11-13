/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Root build: loads every plugin's classpath once at the root so all
 * subprojects share a single Kotlin/AGP/Compose Gradle-plugin classloader.
 * Without this, KGP 2.1.x's isolated-classpath check fails with
 * "The Kotlin Gradle plugin was loaded multiple times in different
 * subprojects" the moment a KMP+AGP module (:ui) does cross-project
 * metadata transformation.
 */
plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinAndroid) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.protobuf) apply false
}
