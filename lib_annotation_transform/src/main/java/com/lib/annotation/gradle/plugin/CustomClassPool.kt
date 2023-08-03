package com.lib.annotation.gradle.plugin

import com.lib.annotation.Inject
import javassist.*
import javassist.expr.ExprEditor
import javassist.expr.FieldAccess
import org.gradle.api.logging.LogLevel
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

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
     * keep mapping info of target class name -> list info of field/method to be processed
     * */
    private val targetMap = ConcurrentHashMap<String, ArrayList<InsertInfo>>()

    /**
     * keep class file info contain @Inject annotation. key may be path of class file, entry
     * name in jar or CtClass name.
     * */
    private val insertSourceMap = hashMapOf<String, Boolean>()

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
                    var targetClassName = getInjectTargetClassName(member)
                    if (isField && targetClassName.endsWith(KOTLIN_COMPANION_SUFFIX)) {
                        targetClassName = targetClassName.replace(KOTLIN_COMPANION_SUFFIX, "")
                    }
                    val insertInfoList = if (targetMap.containsKey(targetClassName)) {
                        // 之前存在targetClassName的注入列表，则获取列表
                        targetMap[targetClassName]
                    } else {
                        // 之前不存在targetClassName的注入列表，则创建列表并放入targetMap
                        log("targetMap add key = $targetClassName, ${(entryName ?: "").ifEmpty { file?.absolutePath }}")
                        targetMap[targetClassName] = arrayListOf()
                        targetMap[targetClassName]
                    }

                    val info = InsertInfo(
                        ctClassName,
                        if (member is CtField) member else null,
                        if (member is CtMethod) member else null,
                        targetClassName,
                        annotation.fieldClzName,
                        annotation.addCatch,
                        false
                    )
                    insertInfoList!!.add(info)
                    insertSourceMap[ctClassName] = true
                }
            }
        }
    }

    private fun checkIsMatchNormalMethod(src: CtMethod, target: CtMethod): Boolean {
        return !target.hasAnnotation(Inject::class.java) &&
                target.name == src.name &&
                target.parameterTypes.contentEquals(src.parameterTypes)

    }

    private fun checkIsMatchLambdaMethod(mtd: CtMethod, target: CtMethod): Boolean {
        if (target.name.contains(LAMBDA)) {
            // anonymous interface lambda call's first parameter is reference of this object
            if (mtd.returnType == target.returnType &&
                mtd.parameterTypes.size == target.parameterTypes.size - 1) {
                var match = true
                for (i in (mtd.parameterTypes.size - 1) downTo 0) {
                    if (mtd.parameterTypes[i] != target.parameterTypes[i + 1]) {
                        match = false
                        break
                    }
                }
                return match
            }
        }
        return false
    }

    private fun checkInterfaceInject(ctClass: CtClass, assignedKey: String? = null): Boolean {
        var isContainInjectTarget = false
        val keys = targetMap.keys
        for (key in keys) {
            if (assignedKey != null && assignedKey != key) {
                continue
            }
            val dest = get(key)
            // only check interface impl
            if (dest.isInterface) {
                val insertInfoList = targetMap[key]!!
                val info = insertInfoList[0]
                val targetMethodNameList = insertInfoList.filter { insertInfo ->
                    !insertInfo.srcMtd?.name.isNullOrEmpty()
                }.map { insertInfo ->
                    insertInfo.srcMtd!!.name
                }.distinct()
                dest.run {
                    // traversal interface's declared methods
                    for (mtd in declaredMethods) {
                        if (!targetMethodNameList.contains(mtd.name)) {
//                            log("skip interface method ${mtd.name}")
                            continue
                        }

                        var isMatched = false
                        // traversal current class's declared methods
                        for (target in ctClass.declaredMethods) {
                            // normal interface impl and anonymous lambda call
                            if (checkIsMatchNormalMethod(mtd, target) || checkIsMatchLambdaMethod(mtd, target)) {
                                isMatched = true
                                isContainInjectTarget = true
                                val targetInfoList = if (targetMap.containsKey(ctClass.name)) {
                                    targetMap[ctClass.name]!!
                                } else {
                                    arrayListOf<InsertInfo>().apply {
                                        targetMap[ctClass.name] = this
                                    }
                                }
                                val mtdNameList = targetInfoList.filter { info ->
                                    !info.srcMtd?.name.isNullOrEmpty()
                                }.map { info ->
                                    info.srcMtd!!.name
                                }.distinct()
                                if (!mtdNameList.contains(info.srcMtd?.name)) {
                                    targetInfoList.add(InsertInfo(info.srcClassName, info.srcField,
                                        info.srcMtd, ctClass.name,
                                        info.srcFieldType, info.catch, true)
                                    )
                                }
                            }
                            if (isMatched) {
                                break
                            }
                        }
                    }
                }
            }
        }
        return isContainInjectTarget
    }

    fun injectPrepare(ctClass: CtClass, path: String) {
        var isContainInjectTarget = false
        var isInterfaceImpl = false
        if (!ctClass.isInterface) {
            isContainInjectTarget = checkInterfaceInject(ctClass)
            kotlin.runCatching {
                ctClass.interfaces?.let { interfaceClassList ->
                    for (cls in interfaceClassList) {
                        if (targetMap.containsKey(cls.name)) {
                            isInterfaceImpl = true
                            checkInterfaceInject(ctClass, cls.name)
                        }
                    }
                }
            }.onFailure {
                log("injectPrepare ctClass.interfaces exception = ${it.message}", LogLevel.WARN)
            }
        }

        if (isInterfaceImpl || isContainInjectTarget || targetMap.containsKey(ctClass.name)) {
            log("add to target path map = $path")
            map[path] = ctClass.name
        }
        ctClass.detach()
    }

    fun injectInsertInfo(absolutePath: String, directoryName: String) {
        if (targetMap.isEmpty() || !map.containsKey(absolutePath)) {
            return
        }

        val clsName = map[absolutePath]
        val ctClass = get(clsName) ?: makeClass(File(absolutePath).inputStream())
        injectItem(ctClass, ctClass.name, directoryName)
    }

    fun injectInJar(entryName: String, inputStream: InputStream): CtClass {
        val ctClass = get(map[entryName]) ?: makeClass(inputStream)

        injectItem(ctClass, ctClass.name, null)

        return ctClass
    }

    private fun injectItem(ctClass: CtClass, clsName: String, directoryName: String?) {
        if (targetMap.containsKey(clsName)) {
            val insertInfoList = targetMap[clsName]
            if (insertInfoList.isNullOrEmpty()) {
                return
            }

            var i = 0
            log("injectItem ${insertInfoList.size} = $clsName")
            while (i < insertInfoList.size) {
                val item = insertInfoList[i]
                if (clsName != item.destClassName) {
                    ++i
                    continue
                }
                val targetCtCls = get(item.destClassName)
//                log("real inject target class : ${targetCtCls.name}")
                if (targetCtCls.isFrozen) {
                    targetCtCls.defrost()
                }

                val fields = targetCtCls.declaredFields
                val methods = targetCtCls.declaredMethods
//                log("item = $item, fields = $fields")
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
                log("targetMap remove $clsName\n")
                targetMap.remove(clsName)
                ctClass.detach()
            }
        }
    }

    private fun injectField(fields: Array<CtField>, item: InsertInfo, targetCtCls: CtClass, directoryName: String?) {
        val srcField = item.srcField!!
        if (fields.isNotEmpty()) {
//            log("variable ${srcField.name} start inject, target class = ${targetCtCls.name}")
            if (targetCtCls.isFrozen) {
                targetCtCls.defrost()
            }
            for (field in fields) {
                if (field.name == srcField.name) {
//                    log("found variable to be replaced ${field.name}")
                    val fieldInfo = srcField.fieldInfo
                    if (fieldInfo != null) {
//                        log("after replace ${field.name}, new value = ${srcField.constantValue}")
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
                    }
                    log("variable ${srcField.name} inject\n")
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
//        log("addFieldWithType str = $newFieldStr")
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
//        log("start method inject : ${item.srcMtd.name}, directoryName = $directoryName, ${targetCtCls.name}")
        val srcMethod: CtMethod = item.srcMtd!!
        val srcClsName = item.srcClassName
        val annotation = srcMethod.getAnnotation(Inject::class.java) as Inject
        val clsName = getInjectTargetClassName(srcMethod)
        val annotationTarget = get(clsName)

        if (targetCtCls.isFrozen) {
            targetCtCls.defrost()
        }
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

            if (checkIsMatchNormalMethod(srcMethod, m) || isAnonymousInterfaceCall) {
                val mtdCls = get(srcClsName)
                if (mtdCls.isFrozen) {
                    mtdCls.defrost()
                }

                val args = getArgsForInsertSource(m, isAnonymousInterfaceCall)
                var code: String = ""
                if (mtdCls.isKotlin) {
                    // Kotlin中object反编译为java代码时，为一单例类，访问其中方法需要使用单例对象
                    for (field in mtdCls.fields) {
                        if (field.name == "INSTANCE") {
                            code = mtdCls.name + ".INSTANCE." + srcMethod.name + "($args);"
                            if (srcMethod.returnType == CtClass.booleanType) {
                                code = getReturnTypeCheckedCode(annotation, m, code)
                            }
                            break
                        }
                    }
                } else if (Modifier.isPublic(srcMethod.modifiers) && Modifier.isStatic(srcMethod.modifiers)) {
                    code = mtdCls.name + "." + srcMethod.name + "(${args});"
                    if (annotation.replace) {
                        if (srcMethod.returnType != CtClass.voidType) {
                            code = "return $code"
                        }
                    } else if ((srcMethod.returnType == CtClass.booleanType)) {
                        code = getReturnTypeCheckedCode(annotation, m, code)
                    }
                }
                if (code.isNotEmpty()) {
//                    log("inject code\n$code\nmodifier : ${srcMethod.modifiers}")
                } else {
//                    log("inject method use method copy")
                }
//                log("inject class : $clsName, replace = ${annotation.replace}, before = ${annotation.before}")
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
                        kotlin.runCatching {
                            m.setBody(code)
                        }.onFailure {
                            log("CannotCompileException code = $code, mtd = $m, targetCls = ${targetCtCls.name}, src = $srcClsName", LogLevel.ERROR)
                            throw CannotCompileException(it.message)
                        }
                    }
                } else if (annotation.before) {
                    m.insertBefore(code)
                } else {
                    m.insertAfter(code)
                }
                if (!item.catch.isNullOrEmpty()) {
                    val throwableType = get(Throwable::class.java.name)
                    m.addCatch(item.catch, throwableType)
                    throwableType.detach()
                    log("add catch : ${item.catch}")
                }
                log("${targetCtCls.simpleName} method ${m.name} finish")
                break
            }
        }
    }

    private fun getReturnTypeCheckedCode(annotation: Inject, m: CtMethod, codeParam: String): String {
        var code = codeParam
        val tmp = code.substring(0, code.length - 1)
        if (annotation.before) {
            if (m.returnType == CtClass.voidType) {
                code = "if ($tmp) return;"
            } else if (m.returnType == CtClass.booleanType) {
                code = "if ($tmp) return true;"
            }
        }
        return code
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
            log("release targetMap = ${targetMap.size}")
            targetMap.keys.forEach {
                log("$it = ${targetMap[it]}")
            }
        }
        targetMap.clear()
        for (path in pathList) {
            removeClassPath(path)
        }
        pathList.clear()
        classes.clear()
        log("ClassPool release end")
        CustomLogger.closeLogFile()
    }

    fun isTarget(name: String) = map.containsKey(name)

    fun logCollectResult() {
        log("map contain insert class info collect finish...source list :")
        for (key in insertSourceMap.keys) {
            log(key)
        }

        log("map contain inject dest collect finish...target list :")
        for (key in targetMap.keys) {
            log(key)
        }
    }
}