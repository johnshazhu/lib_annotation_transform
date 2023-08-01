package com.lib.annotation.gradle.plugin

import com.lib.annotation.Inject
import javassist.*
import javassist.expr.ExprEditor
import javassist.expr.FieldAccess
import java.io.File
import java.io.FileInputStream

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
                    val annotation = member.getAnnotation(Inject::class.java) as Inject
                    val type = if (isField) "variables" else "methods"
                    log("transversal ${ctClassName}'s $type")
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
                        annotation.fieldClzName,
                        annotation.addCatch,
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
    
    private fun anonymousInterfaceCallCheck(file: File, ctClass: CtClass): Boolean {
        var isContainInjectTarget = false
        val keys = targetMap.keys
        for (key in keys) {
            val dest = get(key)
            if (dest.isInterface) {
                dest.run {
                    declaredMethods.forEach {  mtd ->
                        ctClass.declaredMethods.forEach { target ->
                            if (target.name.contains(LAMBDA)) {
                                if (mtd.returnType == target.returnType && mtd.parameterTypes.size == target.parameterTypes.size - 1) {
                                    var match = true
                                    for (i in (mtd.parameterTypes.size - 1) downTo 0) {
                                        if (mtd.parameterTypes[i] != target.parameterTypes[i + 1]) {
                                            match = false
                                            break
                                        }
                                    }
                                    if (match) {
                                        isContainInjectTarget = true
                                        log("${mtd.name} matched with ${target.name}")
                                        map[file.absolutePath] = dest.name
                                        val list = targetMap[key]!!
                                        if (list.size == 1) {
                                            list[0].destClassName = ctClass.name
                                        } else {
                                            val info = list[0]
                                            val element = InsertInfo(
                                                info.srcClassName,
                                                info.srcField,
                                                info.srcMtd,
                                                info.param,
                                                ctClass.name,
                                                info.srcFieldType,
                                                info.catch,
                                                false
                                            )
                                            list.add(element)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return isContainInjectTarget
    }

    fun injectPrepare(file: File) {
        val inputStream = FileInputStream(file)
        val ctClass = makeClass(inputStream)
        inputStream.close()

        var isContainInjectTarget = false
        var isInterfaceImpl = false
        if (!ctClass.isInterface) {
            isContainInjectTarget = anonymousInterfaceCallCheck(file, ctClass)
            kotlin.runCatching {
                run@ {
                    ctClass.interfaces?.forEach {
                        if (targetMap.containsKey(it.name)) {
                            isInterfaceImpl = true
                            map[file.absolutePath] = it.name
                            interfaceProcess(ctClass, it)
                            return@run
                        }
                    }
                }
            }.onFailure {
                it.printStackTrace()
            }
        }

        if (isInterfaceImpl || isContainInjectTarget || targetMap.containsKey(ctClass.name)
            || mapContainInsertClass.containsKey(file.absolutePath)) {
            // 当前文件对应的类为注入目标类，或包含注入信息类，不处理
            if (!isInterfaceImpl && !isContainInjectTarget) {
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
                        info.srcFieldType,
                        info.catch,
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
                val methods = targetCtCls.declaredMethods
                log("item = $item, fields = $fields")
                if (item.srcField != null) {
                    injectField(fields, item, targetCtCls, directoryName)
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

    private fun injectField(fields: Array<CtField>, item: InsertInfo, targetCtCls: CtClass, directoryName: String?) {
        val srcField = item.srcField!!
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
                        //remove old field
                        targetCtCls.removeField(field)

                        val constantValue: Any? = srcField.constantValue
                        val constructor = targetCtCls.classInitializer
                        constructor?.instrument(object : ExprEditor() {
                            override fun edit(f: FieldAccess) {
                                if (srcField.name == f.fieldName) {
                                    f.replace("{}")
                                }
                            }
                        })
                        if (item.srcFieldType.isNullOrEmpty() && constantValue != null) {
                            addFieldByCopy(targetCtCls, field, constantValue)
                        } else {
                            addFieldWithType(targetCtCls, field, item.srcFieldType, constantValue)
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

    private fun addFieldWithType(targetCtCls: CtClass, field: CtField, fieldClassName: String?, value: Any?) {
        val newFiledInfoBuilder = StringBuilder()
        newFiledInfoBuilder.run {
            field.modifiers.let {
                when {
                    Modifier.isPublic(it) -> append("public ")
                    Modifier.isProtected(it) -> append("protected ")
                    Modifier.isPrivate(it) -> append("private ")
                    Modifier.isStatic(it) -> append("static ")
                    Modifier.isFinal(it) -> append("final ")
                    Modifier.isVolatile(it) -> append("volatile ")
                    Modifier.isTransient(it) -> append("transient ")
                    else -> ""
                }
            }
            if (!fieldClassName.isNullOrEmpty()) {
                append(fieldClassName)
            } else {
                append(field.type.name)
            }
            append(" ${field.name} = ")

            if (value is String) {
                append("new String(\"$value\");")
            } else {
                append("$value;")
            }
        }

        val newFieldStr = newFiledInfoBuilder.toString()
        log("addFieldWithType str = $newFieldStr")
        targetCtCls.addField(CtField.make(newFieldStr, targetCtCls))
    }

    private fun addFieldByCopy(targetCtCls: CtClass, field: CtField, constantValue: Any?) {
        // new Field by copy
        val newField = CtField(field, targetCtCls)

        // add newField, and initialize it with constantValue
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
    }

    private fun injectMethod(methods: Array<CtMethod>, item: InsertInfo, targetCtCls: CtClass, directoryName: String?) {
        log("\nstart method inject : ${item.srcMtd}, directoryName = $directoryName, ${targetCtCls.name}")
        val srcMethod: CtMethod = item.srcMtd!!
        val srcClsName = item.srcClassName
        val annotation = srcMethod.getAnnotation(Inject::class.java) as Inject
        val clsName = getInjectTargetClassName(srcMethod)
        val annotationTarget = get(clsName)
        if (annotationTarget.isInterface) {
            log("injectMethod's original target is interface = $clsName")
        }

        if (targetCtCls.isFrozen) {
            targetCtCls.defrost()
        }
        var another: CtMethod? = null
        for (m in methods) {
            var isAnonymousInterfaceCall = false
            if (annotationTarget.isInterface && m.name.contains(LAMBDA)) {
                var isParameterMath = true
                for (interfaceMtd in annotationTarget.declaredMethods) {
                    if (interfaceMtd.returnType == m.returnType && interfaceMtd.parameterTypes.size == m.parameterTypes.size - 1) {
                        for (i in (interfaceMtd.parameterTypes.size - 1) downTo 0) {
                            if (interfaceMtd.parameterTypes[i] != m.parameterTypes[i + 1]) {
                                isParameterMath = false
                                break
                            }
                        }
                    }
                }
                isAnonymousInterfaceCall = isParameterMath
            }
            if (m.name == annotation.name || isAnonymousInterfaceCall) {
                if (!isAnonymousInterfaceCall) {
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
                }

                log("method : ${m.name}, source method class : $srcClsName")
                val mtdCls = get(srcClsName)
                if (mtdCls.isFrozen) {
                    mtdCls.defrost()
                }

                if (item.isInterface) {
                    another = mtdCls.getDeclaredMethod(m.name, item.param)
                    log("another $another")
                }

                val args = getArgsForInsertSource(m, isAnonymousInterfaceCall)
                var code: String = ""
                if (mtdCls.isKotlin) {
                    log("source method's class is kotlin class")
                    // Kotlin中object反编译为java代码时，为一单例类，访问其中方法需要使用单例对象
                    for (field in mtdCls.fields) {
                        if (field.name == "INSTANCE") {
                            code = mtdCls.name + ".INSTANCE." + srcMethod.name + "($args);"
                            if (srcMethod.returnType == CtClass.booleanType) {
                                val tmp = code.substring(0, code.length - 1)
                                code = "if ($tmp) return;"
                            }
                            break
                        }
                    }
                } else if (Modifier.isPublic(srcMethod.modifiers) && Modifier.isStatic(srcMethod.modifiers)) {
                    code = mtdCls.name + "." + srcMethod.name + "(${args});"
                    if (srcMethod.returnType == CtClass.booleanType) {
                        val tmp = code.substring(0, code.length - 1)
                        code = "if ($tmp) return;"
                    }
                }
                if (code.isNotEmpty()) {
                    log("inject code\n$code\nmodifier : ${srcMethod.modifiers}")
                } else {
                    log("inject method use method copy")
                }
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
                if (!item.catch.isNullOrEmpty()) {
                    val throwableType = get(Throwable::class.java.name)
                    m.addCatch(item.catch, throwableType)
                    throwableType.detach()
                    log("add catch : ${item.catch}")
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

    private fun getArgsForInsertSource(mtd: CtMethod, isAnonymousCall: Boolean): String {
        // $$ represent all parameters of the method
        var args = ""
        if (isAnonymousCall) {
            // static method, $0 is not available.
            // lambda of anonymous interface call's first parameter is this reference, we do not need it.
            // so we start from the second parameter.
            for (i in 2..mtd.parameterTypes.size) {
                args += "\$$i,"
            }
            if (args.isNotEmpty()) {
                args = args.substring(0, args.length - 1)
            }
        }
        return args.ifEmpty { "\$\$" }
    }

    fun release() {
        map.clear()
        if (targetMap.isNotEmpty()) {
            println("release targetMap = $targetMap")
        }
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