package com.lib.annotation.gradle.plugin

import javassist.CtClass
import javassist.CtField
import javassist.CtMethod

class InsertInfo(
    val srcClassName: String,
    val srcField: CtField?,
    val srcMtd: CtMethod?,
    val param: Array<out CtClass>?,
    var destClassName: String?,
    val srcFieldType: String?,
    val catch: String?,
    val isInterface: Boolean
) {
    override fun toString(): String {
        return "[destClassName] = $destClassName, [srcClassName] = $srcClassName" +
                " [srcField] = $srcField, [srcMtd] = $srcMtd, [srcFieldType] = $srcFieldType," +
                " [catch] = $catch, [isInterface] = $isInterface"
    }
}
