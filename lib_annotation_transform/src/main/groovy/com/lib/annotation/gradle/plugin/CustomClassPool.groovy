package com.lib.annotation.gradle.plugin

import com.lib.annotation.Inject
import javassist.ClassPath
import javassist.ClassPool
import javassist.CtClass
import javassist.CtConstructor
import javassist.CtField
import javassist.CtMember
import javassist.CtMethod
import javassist.Modifier
import javassist.NotFoundException
import javassist.bytecode.FieldInfo
import javassist.expr.ExprEditor
import javassist.expr.FieldAccess

class CustomClassPool extends ClassPool {
    //记录类文件路径及类名的Map. key : class absolute path, value : class name
    def map = [:]

    //ClassPool中包含的搜索路径列表
    def pathList = []

    //创建的class列表
    def clsList = []

    //注入目标Map. key : 目标类, value : Lis<InsertInfo> 注入目标类的信息(字段/方法)列表
    def targetMap = [:]

    // 记录有注入信息的类的Map，其中key为路径、jar中entryName或ctClassName
    def mapContainInsertClass = [:]

    CustomClassPool(boolean useDefaultPath) {
        super(useDefaultPath)
        cacheOpenedJarFile = false
    }

    String getCachedClassName(String path) {
        return map[path]
    }

    private static String getInjectTargetClassName(CtMember member) {
        Inject annotation = member.getAnnotation(Inject.class)
        String clsName = annotation.classPath()
        if (clsName == null || clsName.length() == 0) {
            clsName = Util.getAnnotationClassValue(member, Inject.class, "target")
        }
        return clsName
    }

    // jar文件时file为null，entryName与ctClass非null
    // 文件时entryName与ctClass均为null
    void collectUsedClass(File file, String entryName, CtClass ctClass) {
        if (ctClass == null) {
            // 文件检查
            InputStream inputStream = new FileInputStream(file)
            ctClass = makeClass(inputStream)

            if (inputStream != null) {
                inputStream.close()
            }
        }

        //遍历当前class文件中的字段, 找到有注入注解的字段, 添加到注入列表中
        traverseMembers(file, entryName, ctClass.getDeclaredFields(), ctClass.name, true)

        //遍历当前class文件中的方法, 找到有注入注解的方法, 添加到注入列表中
        traverseMembers(file, entryName, ctClass.getMethods(), ctClass.name, false)

        ctClass.detach()
    }

    void traverseMembers(File file, String entryName, CtMember[] ctMembers, String ctClassName, boolean isField) {
        if (ctMembers.length > 0) {
            for (CtMember member : ctMembers) {
                if (member.hasAnnotation(Inject.class)) {
                    String type = isField ? "变量" : "方法"
                    println("\n遍历类 ${ctClassName} $type")
                    String targetClassName = getInjectTargetClassName(member)
                    if (isField && targetClassName.endsWith("\$Companion")) {
                        targetClassName = targetClassName.replace("\$Companion", "")
                    }
                    List<InsertInfo> insertInfoList
                    if (targetMap.containsKey(targetClassName)) {
                        // 之前存在targetClassName的注入列表，则获取列表
                        insertInfoList = targetMap[targetClassName]
                        println "targetMap 已有key = $targetClassName"
                    } else {
                        // 之前不存在targetClassName的注入列表，则创建列表并放入targetMap
                        insertInfoList = new ArrayList<>()
                        targetMap[targetClassName] = insertInfoList
                        println "targetMap 新增key = $targetClassName"
                    }
                    InsertInfo info = new InsertInfo()
                    if (member instanceof CtField) {
                        info.srcField = member
                    } else if (member instanceof CtMethod) {
                        info.srcMtd = member
                        info.param = member.parameterTypes
                    }
                    info.srcClassName = ctClassName
                    info.destClassName = targetClassName
                    insertInfoList.add(info)

                    if (file != null) {
                        println "mapContainInsertClass file key = ${file.absolutePath}"
                        mapContainInsertClass[file.absolutePath] = true
                    } else if (entryName != null) {
                        println "mapContainInsertClass entryName key = $entryName"
                        mapContainInsertClass[entryName] = true
                    }
                    println()
                }
            }
        }
    }

    void appendClassPathWithFile(File file) {
        ClassPath path = appendClassPath(file.absolutePath)
        pathList.add(path)
    }

    void interfaceProcess(CtClass interfaceImplClass, CtClass iClass) {
        for (CtMethod member : iClass.declaredMethods) {
            boolean found = false
            for (CtMethod mtd : interfaceImplClass.methods) {
                if (member.name == mtd.name) {
                    found = true
                    List<InsertInfo> list = targetMap[iClass.name]
                    if (list.size() == 1) {
                        list[0].destClassName = interfaceImplClass.name
                    } else {
                        InsertInfo info = list[0]
                        InsertInfo element = new InsertInfo()
                        element.srcMtd = info.srcMtd
                        element.srcField = info.srcField
                        element.destClassName = interfaceImplClass.name
                        element.srcClassName = info.srcClassName
                        insertInfoList.add(element)
                    }
                    break
                }
            }
            if (found) {
                break
            }
        }
    }

    void injectPrepare(File file) {
        InputStream inputStream = new FileInputStream(file)
        CtClass ctClass = makeClass(inputStream)
        if (inputStream != null) {
            inputStream.close()
        }

        boolean isInterfaceImpl = false
        if (!ctClass.isInterface()) {
            try {
                CtClass[] interfaces = ctClass.getInterfaces()
                if (interfaces != null && interfaces.length > 0) {
                    for (CtClass cls : interfaces) {
                        if (targetMap.containsKey(cls.name)) {
                            isInterfaceImpl = true
                            map[file.absolutePath] = cls.name
                            interfaceProcess(ctClass, cls)
                            break
                        }
                    }
                }
            } catch (NotFoundException e) {
                e.printStackTrace()
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

    boolean injectItem(String clsName, String directoryName) {
        if (!targetMap.isEmpty() && targetMap.containsKey(clsName)) {
            List<InsertInfo> insertInfoList = targetMap.get(clsName)
            if (insertInfoList == null || insertInfoList.size() == 0) {
                return false
            }

            int i = 0
            for (; i < insertInfoList.size(); ++i) {
                InsertInfo item = insertInfoList.get(i)
                CtClass targetCtCls = get(item.destClassName)
                println("实际注入目标类 : ${targetCtCls.name}")
                if (targetCtCls.isFrozen()) {
                    targetCtCls.defrost()
                }

                CtField[] fields = targetCtCls.declaredFields
                CtMethod[] methods = targetCtCls.methods
                println("实际注入目标类 : ${item}, ${fields}")
                if (item.srcField != null) {
                    injectField(fields, item.srcField, targetCtCls, directoryName)
                    insertInfoList.remove(item)
                    i -= 1
                } else if (item.srcMtd != null) {
                    injectMethod(methods, item, targetCtCls, directoryName)
                    insertInfoList.remove(item)
                    i -= 1
                }
            }

            if (insertInfoList.size() == 0) {
                println("targetMap remove $clsName")
                targetMap.remove(clsName)
            }
            return true
        }

        return false
    }

    void injectInsertInfo(String absolutePath, String directoryName) {
        if (targetMap.isEmpty()) {
            println("targetMap empty")
            return
        }

        if (absolutePath != null && !absolutePath.isEmpty()) {
            if (!map.containsKey(absolutePath)) {
                println("${absolutePath}对应的类不是注入目标类")
                return
            }
            String clsName = getCachedClassName(absolutePath)
            injectItem(clsName, directoryName)
        }
    }

    void injectField(CtField[] fields, CtField srcField, CtClass targetCtCls, String directoryName) {
        if (fields.length > 0) {
            println("\n变量${srcField.name}注入开始, 目标类为 ${targetCtCls.name}")
            if (targetCtCls.isFrozen()) {
                targetCtCls.defrost()
            }
            for (CtField field : fields) {
                if (field.name == srcField.name) {
                    println "找到注入替换变量 ${field.name}"
                    FieldInfo fieldInfo = srcField.fieldInfo
                    if (fieldInfo != null) {
                        println "${field.name} 替换后值为 ${srcField.constantValue}"
                        Object constantValue = srcField.constantValue
                        CtConstructor constructor = targetCtCls.getClassInitializer()
                        if (constructor != null) {
                            constructor.instrument(new ExprEditor() {
                                @Override
                                void edit(FieldAccess f) {
                                    if (srcField.name == f.getFieldName()) {
                                        f.replace("{}")
                                    }
                                }
                            })
                        }
                        //以复制的方式创建一个新的field
                        CtField newField = new CtField(field, targetCtCls)
                        //删除旧的field
                        targetCtCls.removeField(field)
                        //添加之前创建的field, 并用注入的field的值初始化
                        targetCtCls.addField(newField, CtField.Initializer.constant(constantValue))

                        if (directoryName != null && !directoryName.isEmpty()) {
                            targetCtCls.writeFile(directoryName)
                            println "writeFile directoryName : ${directoryName}"
                        }
                    }
                    println("变量${srcField.name}注入结束\n")
                    break
                }
            }
        }
    }

    void injectMethod(CtMethod[] methods, InsertInfo item, CtClass targetCtCls, String directoryName) {
        println("\n开始注入方法 : ${item.srcMtd}, directoryName = $directoryName")
        CtMethod srcMethod = item.srcMtd
        String srcClsName = item.srcClassName
        Inject annotation = srcMethod.getAnnotation(Inject.class)
        String clsName = getInjectTargetClassName(srcMethod)

        if (targetCtCls.isFrozen()) {
            targetCtCls.defrost()
        }
        CtMethod another = null
        for (CtMethod m : methods) {
            if (m.name == annotation.name()) {
                CtClass[] targetParameters = m.parameterTypes
                CtClass[] srcParameters = srcMethod.parameterTypes
                boolean match = true
                if (targetParameters.size() == srcParameters.size()) {
                    int i = 0
                    while (i < targetParameters.size()) {
                        if (targetParameters[i].class != srcParameters[i].class) {
                            match = false
                            break
                        }
                        ++i
                    }
                } else {
                    match = false
                }

                if (!match) {
                    println "方法名 : ${m.name} 参数不匹配"
                    continue
                }

                println "方法名 : ${m.name}\n注入源方法所在类 : $srcClsName"
                CtClass mtdCls = get(srcClsName)
                if (mtdCls.isFrozen()) {
                    mtdCls.defrost()
                }

                if (item.isInterface) {
                    another = mtdCls.getDeclaredMethod(m.name, item.param)
                    println "another $another"
                }

                String code = null
                if (mtdCls.isKotlin()) {
                    println("源方法所在类为kotlin文件")
                    // Kotlin中object反编译为java代码时，为一单例类，访问其中方法需要使用单例对象
                    for (field in mtdCls.fields) {
                        if (field.name == "INSTANCE") {
                            code = mtdCls.name + ".INSTANCE." + srcMethod.name + "(\$\$);"
                            if (srcMethod.returnType == CtClass.booleanType) {
                                String tmp = code.substring(0, code.length() - 1)
                                code = "if ($tmp) return;"
                            }
                            break
                        }
                    }
                } else if (Modifier.isPublic(srcMethod.getModifiers()) && Modifier.isStatic(srcMethod.getModifiers())) {
                    code = mtdCls.name + "." + srcMethod.name + "(\$\$);"
                    if (srcMethod.returnType == CtClass.booleanType) {
                        String tmp = code.substring(0, code.length() - 1)
                        code = "if ($tmp) return;"
                    }
                    println("源方法为public static")
                }
                println "注入代码\n$code\nmodifyer : ${srcMethod.getModifiers()}"
                println("注入类 : $clsName, 是否替换 = ${annotation.replace()}, 方法前插入 = ${annotation.before()}")
                if (code == null || code.isEmpty()) {
                    if (clsName.endsWith("\$Companion")) {
                        srcMethod.addLocalVariable("this", targetCtCls)
                    }
                    m.setBody(srcMethod, null)
                    println "非静态方法注入"
                } else if (annotation.replace()) {
                    if (code == null || code.isEmpty()) {
                        if (clsName.endsWith("\$Companion")) {
                            srcMethod.addLocalVariable("this", targetCtCls)
                        }
                        m.setBody(srcMethod, null)
                    } else {
                        m.setBody(code)
                    }
                } else if (annotation.before()) {
                    if (another != null) {
                        another.insertBefore(code)
                    } else {
                        m.insertBefore(code)
                    }
                } else {
                    m.insertAfter(code)
                }
                if (directoryName != null && !directoryName.isEmpty()) {
                    targetCtCls.writeFile(directoryName)
                    println "writeFile directoryName : ${directoryName}"
                }
                println("方法 ${m.name} 注入完成\n")
                break
            }
        }
    }

    void release() {
        map.clear()
        targetMap.clear()
        mapContainInsertClass.clear()

        for (ClassPath path : pathList) {
            removeClassPath(path)
        }
        pathList.clear()

        for (CtClass cls : clsList) {
            if (cls != null) {
                try {
                    cls.detach()
                } catch (Throwable r) {
                    r.printStackTrace()
                }
            }
        }
        clsList.clear()

        classes.clear()
        println "ClassPool release end. source = ${source.toString()}"
    }
}
