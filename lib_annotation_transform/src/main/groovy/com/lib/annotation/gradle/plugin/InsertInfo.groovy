package com.lib.annotation.gradle.plugin

import javassist.CtClass
import javassist.CtField
import javassist.CtMethod

class InsertInfo {
    String srcClassName
    CtField srcField
    CtMethod srcMtd
    CtClass[] param
    String destClassName

    boolean isInterface

    @Override
    String toString() {
        return "src = $srcClassName, dest = $destClassName, srcField = $srcField, srcMtd = $srcMtd, isInterface = $isInterface"
    }
}