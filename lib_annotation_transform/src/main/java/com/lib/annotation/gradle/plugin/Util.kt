package com.lib.annotation.gradle.plugin

import com.lib.annotation.Inject
import javassist.CtField
import javassist.CtMember
import javassist.CtMethod
import javassist.bytecode.AnnotationsAttribute
import javassist.bytecode.annotation.ClassMemberValue
import org.gradle.api.Project
import java.io.File

const val CONFIG_FILENAME = "transform_config.properties"
const val SKIP_PACKAGE = "skip_package"
const val SKIP_FILENAME_PREFIX = "skip_filename_prefix"
const val SKIP_FILENAME_CONTAIN = "skip_filename_contain"
const val SKIP_JAR_PATH_CONTAIN = "skip_jar_path_contain"
const val KEEP_JAR_PATH_CONTAIN = "keep_jar_path_contain"
const val TARGET_FILENAME_SUFFIX = ".class"
const val LOG_ENABLE = "log_enable"
const val LOG_TAG = "[AnnotationPlugin]"
const val VERSION_PROPERTIES = "version.properties"
const val VERSION = "version"
const val KOTLIN_COMPANION_SUFFIX = "\$Companion"

val propertiesMap = hashMapOf<String, List<String>>()
var logEnable = false

fun getProperties(project: Project) {
    val path = "${project.rootDir.absolutePath}/$CONFIG_FILENAME"
    log("getProperties from $path")
    val file = File(path)
    if (file.exists()) {
        file.readLines().filter {
            it.isNotEmpty()
        }.forEach {
            val keyValues = it.split("=")
            if (keyValues.size == 2) {
                propertiesMap[keyValues[0]] = keyValues[1].split(",")
                log("${keyValues[0]} = ${keyValues[1]}")
            }
        }
        logEnable = propertiesMap[LOG_ENABLE]?.contains("true") ?: false
    } else {
        println("$path does not exist, please check")
    }
}

fun isFilterPackage(name: String): Boolean {
    return propertiesMap[SKIP_PACKAGE]?.contains(name) ?: false
}

fun isIgnoreFileName(name: String): Boolean {
    if (!name.endsWith(TARGET_FILENAME_SUFFIX)) {
        return true
    }
    propertiesMap[SKIP_FILENAME_PREFIX]?.forEach {
        if (name.startsWith(it)) {
            return true
        }
    }

    propertiesMap[SKIP_FILENAME_CONTAIN]?.forEach {
        if (name.contains(it)) {
            return true
        }
    }
    return false
}

fun isIgnoreFile(file: File): Boolean {
    return file.isDirectory || isIgnoreFileName(file.name)
}

fun isIgnoreJar(path: String): Boolean {
    return match(path, SKIP_JAR_PATH_CONTAIN)
}

fun isKeepJar(path: String): Boolean {
    return match(path, KEEP_JAR_PATH_CONTAIN)
}

private fun match(path: String, key: String): Boolean {
    propertiesMap[key]?.forEach {
        if (path.contains(it)) {
            return true
        }
    }
    return false
}

fun getInjectTargetClassName(member: CtMember): String {
    val annotation = member.getAnnotation(Inject::class.java) as Inject
    var clsName = annotation.classPath
    if (clsName.isEmpty()) {
        clsName = getAnnotationClassValue(member, Inject::class.java, "target") ?: ""
    }
    return clsName
}

fun getAnnotationClassValue(
    ctMember: CtMember,
    annotationClass: Class<*>,
    member: String
): String? {
    val attributeName = AnnotationsAttribute.visibleTag
    val attribute = when (ctMember) {
        is CtMethod -> {
            ctMember.methodInfo.getAttribute(attributeName)
        }
        is CtField -> {
            ctMember.fieldInfo.getAttribute(attributeName)
        }
        else -> null
    }

    val annotation = (attribute as? AnnotationsAttribute)?.getAnnotation(annotationClass.name)
    return (annotation?.getMemberValue(member) as? ClassMemberValue)?.value
}

fun log(msg: String?) {
    if (logEnable) {
        println("$LOG_TAG $msg")
    }
}