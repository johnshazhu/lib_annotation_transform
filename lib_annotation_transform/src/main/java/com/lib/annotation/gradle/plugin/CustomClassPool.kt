package com.lib.annotation.gradle.plugin

import com.lib.annotation.Inject
import javassist.*
import javassist.expr.ExprEditor
import javassist.expr.FieldAccess
import javassist.expr.NewArray
import java.io.File
import java.io.FileInputStream
import java.util.LinkedList

class CustomClassPool(useDefaultPath: Boolean): ClassPool(useDefaultPath) {
    /**
     * keep mapping info of class file path -> class name
     * */
    private val map = hashMapOf<String, String>()

    /**
     * list keep info of ClassPath appended
     * */
    private val pathList = arrayListOf<ClassPath>()

    /**
     * clsList keep CtClasses will be used
     * */
    private val clsList = arrayListOf<CtClass>()

    /**
     * keep mapping info of target class name -> list info of field/method to be processed
     * */
    private val targetMap = hashMapOf<String, ArrayList<InsertInfo>>()

    /**
     * keep class file info contain @Inject annotation. key may be path of class file, entry
     * name in jar or CtClass name.
     * */
    private val mapContainInsertClass = hashMapOf<String, Boolean>()

    init {
        cacheOpenedJarFile = false
    }

    fun appendClassPathByFile(file: File) {
        pathList.add(appendClassPath(file.absolutePath))
    }

    /**
     * collect class files that contain @Inject annotation in field or method
     * @param file -> class file, may be null when traverse jar entries
     * @param entryName -> entry in jar file, may be null when traverse directory class files
     * @param ctClass -> The ctClass will be used to insert code, may be null when traverse directory class files
     * */
    fun collectUsedClass(file: File?, entryName: String?, ctClass: CtClass?) {
        val shadowCtClass: CtClass
        if (ctClass == null) {
            val inputStream = FileInputStream(file!!)
            shadowCtClass = makeClass(inputStream)
            inputStream.close()
        } else {
            shadowCtClass = ctClass
        }

        //遍历当前class文件中的字段, 找到有注入注解的字段, 添加到注入列表中
        traverseMembers(file, entryName, shadowCtClass.declaredFields, shadowCtClass.name, true)

        //遍历当前class文件中的方法, 找到有注入注解的方法, 添加到注入列表中
        traverseMembers(file, entryName, shadowCtClass.methods, shadowCtClass.name, false)

        shadowCtClass.detach()
    }

    /**
     *  traverse fields or methods in class file, check Inject annotation and save.
     * @param file -> class file in directory, may be null
     * @param entryName -> entry name in jar file, may be null
     * @param ctMembers -> fields or methods in class
     * @param ctClassName
     * @param isField -> ctMembers were fields when it is true, otherwise ctMembers were methods
     * */
    private fun traverseMembers(file: File?, entryName: String?,
                        ctMembers: Array<out CtMember>,
                        ctClassName: String, isField: Boolean) {
        if (ctMembers.isNotEmpty()) {
            for (member in ctMembers) {
                if (member.hasAnnotation(Inject::class.java)) {
                        val type = if (isField) "variables" else "methods"
                        log("\ntransversal ${ctClassName}'s $type")
                        var targetClassName = getInjectTargetClassName(member)
                        if (isField && targetClassName.endsWith(KOTLIN_COMPANION_SUFFIX)) {
                            targetClassName = targetClassName.replace(KOTLIN_COMPANION_SUFFIX, "")
                        }
                        val insertInfoList = if (targetMap.containsKey(targetClassName)) {
                            // 之前存在targetClassName的注入列表，则获取列表
                            log("targetMap contain key = $targetClassName")
                            targetMap[targetClassName]
                        } else {
                            // 之前不存在targetClassName的注入列表，则创建列表并放入targetMap
                            log("targetMap add key = $targetClassName")
                            targetMap[targetClassName] = arrayListOf()
                            targetMap[targetClassName]
                        }

                        val info = InsertInfo(
                            ctClassName,
                            if (member is CtField) member else null,
                            if (member is CtMethod) member else null,
                            if (member is CtMethod) member.parameterTypes else null,
                            targetClassName,
                            false
                        )
                        insertInfoList!!.add(info)

                        if (file != null) {
                            log("mapContainInsertClass file key = ${file.absolutePath}")
                            mapContainInsertClass[file.absolutePath] = true
                        } else if (entryName != null) {
                            log("mapContainInsertClass entryName key = $entryName")
                            mapContainInsertClass[entryName] = true
                        }
                    }
            }
        }
    }

    fun injectPrepare(file: File) {
        val inputStream = FileInputStream(file)
        val ctClass = makeClass(inputStream)
        inputStream.close()

        var isInterfaceImpl = false
        if (!ctClass.isInterface) {
            kotlin.runCatching {
                ctClass.interfaces?.forEach {
                    if (targetMap.containsKey(it.name)) {
                        isInterfaceImpl = true
                        map[file.absolutePath] = it.name
                        interfaceProcess(ctClass, it)
                        return@forEach
                    }
                }
            }.onFailure {
                it.printStackTrace()
            }
        }

        if (isInterfaceImpl || targetMap.containsKey(ctClass.name)
            || mapContainInsertClass.containsKey(file.absolutePath)) {
            // 当前文件对应的类为注入目标类，或包含注入信息类，不处理
            if (!isInterfaceImpl) {
                map[file.absolutePath] = ctClass.name
            }
            clsList.add(ctClass)
        } else {
            // 无用类
            ctClass.detach()
        }
    }

    private fun interfaceProcess(interfaceImplClass: CtClass, iClass: CtClass) {
        for (member in iClass.declaredMethods) {
            var found = false
            for (mtd in interfaceImplClass.methods) {
                if (member.name == mtd.name) {
                found = true
                val list = targetMap[iClass.name]!!
                if (list.size == 1) {
                    list[0].destClassName = interfaceImplClass.name
                } else {
                    val info = list[0]
                    val element = InsertInfo(
                        info.srcClassName,
                        info.srcField,
                        info.srcMtd,
                        info.param,
                        interfaceImplClass.name,
                        true
                    )
                    list.add(element)
                }
                break
            }
            }
            if (found) {
                break
            }
        }
    }

    fun injectInsertInfo(absolutePath: String?, directoryName: String) {
        if (targetMap.isEmpty()) {
            return
        }

        if (!absolutePath.isNullOrEmpty()) {
            if (!map.containsKey(absolutePath)) {
                log("$absolutePath is not target class file")
                return
            }
            val clsName = map[absolutePath]!!
            injectItem(clsName, directoryName)
        }
    }

    fun injectItem(clsName: String, directoryName: String?): Boolean {
        if (targetMap.containsKey(clsName)) {
            val insertInfoList = targetMap[clsName]
            if (insertInfoList.isNullOrEmpty()) {
                return false
            }

            var i = 0
            while (i < insertInfoList.size) {
                val item = insertInfoList[i]
                val targetCtCls = get(item.destClassName)
                log("real inject target class : ${targetCtCls.name}")
                if (targetCtCls.isFrozen) {
                    targetCtCls.defrost()
                }

                val fields = targetCtCls.declaredFields
                val methods = targetCtCls.methods
                log("item = $item, fields = $fields")
                if (item.srcField != null) {
                    injectField(fields, item.srcField, targetCtCls, directoryName)
                    insertInfoList.remove(item)
                    i -= 1
                } else if (item.srcMtd != null) {
                    injectMethod(methods, item, targetCtCls, directoryName)
                    insertInfoList.remove(item)
                    i -= 1
                }
                ++i
            }

            if (insertInfoList.isEmpty()) {
                log("targetMap remove $clsName")
                targetMap.remove(clsName)
            }
            return true
        }

        return false
    }

    private fun injectField(fields: Array<CtField>, srcField: CtField, targetCtCls: CtClass, directoryName: String?) {
        if (fields.isNotEmpty()) {
            log("\nvariable ${srcField.name} start inject, target class = ${targetCtCls.name}")
            if (targetCtCls.isFrozen) {
                targetCtCls.defrost()
            }
            for (field in fields) {
                if (field.name == srcField.name) {
                    log("found variable to be replaced ${field.name}")
                    val fieldInfo = srcField.fieldInfo
                    if (fieldInfo != null) {
                        log("after replace ${field.name}, new value = ${srcField.constantValue}")
                        val constantValue: Any = srcField.constantValue
                        val constructor = targetCtCls.classInitializer
                        constructor?.instrument(object : ExprEditor() {
                            override fun edit(f: FieldAccess) {
                                if (srcField.name == f.fieldName) {
                                    f.replace("{}")
                                }
                            }
                        })
                        //以复制的方式创建一个新的field
                        val newField = CtField(field, targetCtCls)
                        //删除旧的field
                        targetCtCls.removeField(field)
                        //添加之前创建的field, 并用注入的field的值初始化
                        when (constantValue) {
                            is Int -> {
                                targetCtCls.addField(newField, CtField.Initializer.constant(constantValue))
                            }
                            is Double -> {
                                targetCtCls.addField(newField, CtField.Initializer.constant(constantValue))
                            }
                            is String -> {
                                targetCtCls.addField(newField, CtField.Initializer.constant(constantValue))
                            }
                            is Boolean -> {
                                targetCtCls.addField(newField, CtField.Initializer.constant(constantValue))
                            }
                            is Long -> {
                                targetCtCls.addField(newField, CtField.Initializer.constant(constantValue))
                            }
                            is Float -> {
                                targetCtCls.addField(newField, CtField.Initializer.constant(constantValue))
                            }
                        }
                        if (!directoryName.isNullOrEmpty()) {
                            targetCtCls.writeFile(directoryName)
                            log("writeFile directoryName : $directoryName")
                        }
                    }
                    log("variable ${srcField.name} inject finish\n")
                    break
                }
            }
        }
    }

    private fun injectMethod(methods: Array<CtMethod>, item: InsertInfo, targetCtCls: CtClass, directoryName: String?) {
        log("\nstart method inject : ${item.srcMtd}, directoryName = $directoryName")
        val srcMethod: CtMethod = item.srcMtd!!
        val srcClsName = item.srcClassName
        val annotation = srcMethod.getAnnotation(Inject::class.java) as Inject
        val clsName = getInjectTargetClassName(srcMethod)

        if (targetCtCls.isFrozen) {
            targetCtCls.defrost()
        }
        var another: CtMethod? = null
        for (m in methods) {
            if (m.name == annotation.name) {
                val targetParameters = m.parameterTypes
                val srcParameters = srcMethod.parameterTypes
                var match = true
                if (targetParameters.size == srcParameters.size) {
                    var i = 0
                    while (i < targetParameters.size) {
                        if (targetParameters[i].name != srcParameters[i].name) {
                            match = false
                            break
                        }
                        ++i
                    }
                } else {
                    match = false
                }

                if (!match) {
                    log("method : ${m.name} parameter does not match")
                    continue
                }

                log("method : ${m.name}\n source method class : $srcClsName")
                val mtdCls = get(srcClsName)
                if (mtdCls.isFrozen) {
                    mtdCls.defrost()
                }

                if (item.isInterface) {
                    another = mtdCls.getDeclaredMethod(m.name, item.param)
                    log("another $another")
                }

                var code: String = ""
                if (mtdCls.isKotlin) {
                    log("source method's class is kotlin class")
                    // Kotlin中object反编译为java代码时，为一单例类，访问其中方法需要使用单例对象
                    for (field in mtdCls.fields) {
                        if (field.name == "INSTANCE") {
                            code = mtdCls.name + ".INSTANCE." + srcMethod.name + "(\$\$);"
                            if (srcMethod.returnType == CtClass.booleanType) {
                                val tmp = code.substring(0, code.length - 1)
                                code = "if ($tmp) return;"
                            }
                            break
                        }
                    }
                } else if (Modifier.isPublic(srcMethod.modifiers) && Modifier.isStatic(srcMethod.modifiers)) {
                    code = mtdCls.name + "." + srcMethod.name + "(\$\$);"
                    if (srcMethod.returnType == CtClass.booleanType) {
                        val tmp = code.substring(0, code.length - 1)
                        code = "if ($tmp) return;"
                    }
                }
                log("inject code\n$code\nmodifier : ${srcMethod.modifiers}")
                log("inject class : $clsName, replace = ${annotation.replace}, before = ${annotation.before}")
                if (code.isEmpty()) {
                    if (clsName.endsWith(KOTLIN_COMPANION_SUFFIX)) {
                        srcMethod.addLocalVariable("this", targetCtCls)
                    }
                    m.setBody(srcMethod, null)
                } else if (annotation.replace) {
                    if (code.isEmpty()) {
                        if (clsName.endsWith(KOTLIN_COMPANION_SUFFIX)) {
                            srcMethod.addLocalVariable("this", targetCtCls)
                        }
                        m.setBody(srcMethod, null)
                    } else {
                        m.setBody(code)
                    }
                } else if (annotation.before) {
                    if (another != null) {
                        another.insertBefore(code)
                    } else {
                        m.insertBefore(code)
                    }
                } else {
                    m.insertAfter(code)
                }
                if (!directoryName.isNullOrEmpty()) {
                    targetCtCls.writeFile(directoryName)
                    log("writeFile directoryName : $directoryName")
                }
                log("method ${m.name} inject finish\n")
                break
            }
        }
    }

    fun release() {
        map.clear()
        targetMap.clear()
        mapContainInsertClass.clear()

        for (path in pathList) {
            removeClassPath(path)
        }
        pathList.clear()

        for (cls in clsList) {
            cls.detach()
        }
        clsList.clear()

        classes.clear()
        log("ClassPool release end")
    }

    fun isTarget(className: String) = targetMap.containsKey(className)

    fun logCollectResult() {
        log("map contain insert class info collect finish")
        for (key in mapContainInsertClass.keys) {
            log(key)
        }

        log("map contain inject dest collect finish")
        for (key in targetMap.keys) {
            log("$key : ${targetMap[key]}\n")
        }
    }
}