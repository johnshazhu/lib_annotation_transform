package com.lib.annotation.gradle.plugin

import javassist.CtField
import javassist.CtMethod

class InsertInfo(
    val srcClassName: String,
    val srcField: CtField?,
    val srcMtd: CtMethod?,
    var destClassName: String?,
    val srcFieldType: String?,
    val catch: String?,
    var isInterface: Boolean
) {
    override fun toString(): String {
        return "[destClassName] = $destClassName, [srcClassName] = $srcClassName" +
                " [srcField] = ${srcField?.name}, [srcMtd] = ${srcMtd?.name}, [srcFieldType] = $srcFieldType," +
                " [catch] = $catch, [isInterface] = $isInterface"
    }
}
