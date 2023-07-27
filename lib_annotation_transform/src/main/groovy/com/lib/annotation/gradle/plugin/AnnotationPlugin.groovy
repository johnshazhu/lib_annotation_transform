package com.lib.annotation.gradle.plugin

import com.android.build.api.transform.DirectoryInput
import com.android.build.api.transform.JarInput
import com.android.build.api.transform.QualifiedContent
import com.android.build.api.transform.Transform
import com.android.build.api.transform.TransformException
import com.android.build.api.transform.TransformInput
import com.android.build.api.transform.TransformInvocation
import com.android.build.api.transform.TransformOutputProvider
import com.android.build.gradle.AppExtension
import com.android.build.gradle.internal.pipeline.TransformManager
import javassist.CtClass
import org.apache.commons.codec.digest.DigestUtils
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.apache.commons.io.FileUtils
import org.apache.commons.io.IOUtils

import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry

class AnnotationPlugin extends Transform implements Plugin<Project> {
    AppExtension android
    CustomClassPool clsPool

    @Override
    void apply(Project project) {
        //registerTransform
        android = project.extensions.getByType(AppExtension)
        android.registerTransform(this)
        println("AnnotationPlugin apply")
    }

    //Transform的名称，但是这里并不是真正的名称，真正的名称还需要进行拼接
    @Override
    String getName() {
        return AnnotationPlugin.class.getSimpleName()
    }

    //Transform处理文件的类型
    //CLASSES 表示要处理编译后的字节码，可能是jar包也可能是目录
    //RESOURCES表示处理标准的java资源
    @Override
    Set<QualifiedContent.ContentType> getInputTypes() {
        return TransformManager.CONTENT_CLASS
    }

    //Transform的作用域
    //PROJECT                   ->      只处理当前的文件
    //SUB_PROJECTS              ->      只处理子项目
    //EXTERNAL_LIBRARIES        ->      只处理外部的依赖库
    //TESTED_CODE               ->      测试代码
    //PROVIDED_ONLY             ->      只处理本地或远程以provided形式引入的依赖库
    //PROJECT_LOCAL_DEPS        ->      只处理当前项目的本地依赖，例如jar、aar(Deprecated，使用EXTERNAL_LIBRARIES)
    //SUB_PROJECTS_LOCAL_DEPS   ->      只处理子项目的本地依赖(Deprecated，使用EXTERNAL_LIBRARIES)
    @Override
    Set<? super QualifiedContent.Scope> getScopes() {
        return TransformManager.SCOPE_FULL_PROJECT
    }

    //是否支持增量编译，增量编译就是如果第二次编译相应的task没有改变，那么就直接跳过，节省时间
    @Override
    boolean isIncremental() {
        return false
    }

    //对文件或jar进行处理，进行代码的插入
    //inputs            ->  对输入的class文件转变成目标字节码文件，TransformInput就是这些输入文件的抽象。目前它包含DirectoryInput集合与JarInput集合。
    //directoryInputs   ->  源码方式参与项目编译的所有目录结构及其目录下的源文件。
    //jarInputs         ->  Jar包方式参与项目编译的所有本地jar或远程jar包
    //outputProvider    ->  通过这个类来获取输出路径
    @Override
    void transform(TransformInvocation transformInvocation) throws TransformException, InterruptedException, IOException {
        def startTime = System.currentTimeMillis()

        clsPool = new CustomClassPool(true)
        android.bootClasspath.forEach {
            println "appendClassPath : ${it.name}"
            clsPool.appendClassPathWithFile(it)
        }
        println "增量编译 : ${transformInvocation.incremental}, android bootClassPath : ${android.bootClasspath[0].name}"
        TransformOutputProvider outputProvider = transformInvocation.outputProvider
        //如果非增量，删除之前的输出
        if (!incremental) {
            println "非增量处理，删除之前的输出"
            if (outputProvider != null) {
                outputProvider.deleteAll()
            }
        }

        //遍历inputs, inputs中包括directoryInputs和jarInputs，directoryInputs为文件夹中的class文件，而jarInputs为jar包中的class文件
        Collection<TransformInput> inputs = transformInvocation.inputs

        //遍历文件搜集包含注入代码的类及注入目标类
        println "收集注入信息"
        collectInjectClassesInfo(inputs)

        //注入前准备工作
        println "注入前准备工作"
        injectPrepare(inputs, outputProvider)

        injectClassFile(inputs, outputProvider)

        clsPool.release()
        def cost = (System.currentTimeMillis() - startTime) / 1000
        println "transform 耗时 : $cost 秒"
    }

    void injectPrepare(Collection<TransformInput> inputs, TransformOutputProvider outputProvider) {
        inputs.forEach {
            it.directoryInputs.forEach {
                handleDirectoryInput(it, outputProvider, false)
            }

            it.jarInputs.forEach {
                handleJarInputs(it, outputProvider)
            }
        }
    }

    void injectClassFile(Collection<TransformInput> inputs, TransformOutputProvider outputProvider) {
        //目录下class文件遍历修改
        inputs.forEach {
            it.directoryInputs.forEach {
                handleDirectoryInput(it, outputProvider, true)
            }
        }
    }

    // inject为false时为代码注入前准备
    // 为true时为注入
    void handleDirectoryInput(DirectoryInput directoryInput, TransformOutputProvider outputProvider, boolean inject) {
        boolean find = false
        if (directoryInput.file.isDirectory()) {
            directoryInput.file.eachFileRecurse {
                def name = it.name
                if (!Util.isFilterClassFile(name, "handleDirectoryInput")) {
                    if (!inject) {
                        clsPool.injectPrepare(it)
                    } else {
                        clsPool.injectInsertInfo(it.absolutePath, directoryInput.file.absolutePath)
                    }
                }
            }
        }

        if (inject) {
            //处理完输入文件之后，要把输出给下一个任务
            def dest = outputProvider.getContentLocation(directoryInput.name,
                    directoryInput.contentTypes, directoryInput.scopes,
                    Format.DIRECTORY)

            if (find) {
                println "directoryInput.file : ${directoryInput.file}, dest : $dest"
            }

            FileUtils.copyDirectory(directoryInput.file, dest)
        }
    }

    void handleJarInputs(JarInput jarInput, TransformOutputProvider outputProvider) {
        if (jarInput.file.absolutePath.endsWith(".jar") && !Util.isFilterPackage(jarInput.name)) {
            //重名名输出文件,因为同名会覆盖
            def jarName = jarInput.name
            def md5Name = DigestUtils.md5Hex(jarInput.file.getAbsolutePath())
            if (jarName.endsWith(".jar")) {
                jarName = jarName.substring(0, jarName.length() - ".jar".length())
            }
            JarFile jarFile = new JarFile(jarInput.file)
            Enumeration enumeration = jarFile.entries()
            File tmpFile = new File(jarInput.file.getParent() + File.separator + "classes_temp.jar")
            //避免上次的缓存被重复插入
            if (tmpFile.exists()) {
                tmpFile.delete()
            }
            JarOutputStream jarOutputStream = new JarOutputStream(new FileOutputStream(tmpFile))
            //用于保存
            while (enumeration.hasMoreElements()) {
                JarEntry jarEntry = (JarEntry) enumeration.nextElement()
                String entryName = jarEntry.getName()
                ZipEntry zipEntry = new ZipEntry(entryName)
                InputStream inputStream = jarFile.getInputStream(jarEntry)
                CtClass ctClass = null
                if (!Util.isFilterClassFile(entryName, "handleJarInputs")) {
                    ctClass = clsPool.makeClass(inputStream)
                    if (clsPool.targetMap.containsKey(ctClass.name)) {
                        println("jar文件 ${jarName} 中 ${entryName} 处理")
                        clsPool.injectItem(ctClass.name, null)
                    }
                }
                jarOutputStream.putNextEntry(zipEntry)
                if (ctClass != null) {
                    jarOutputStream.write(ctClass.toBytecode())
                    if (ctClass.isFrozen()) {
                        ctClass.defrost()
                    }
                    ctClass.detach()
                } else {
                    jarOutputStream.write(IOUtils.toByteArray(inputStream))
                }
                jarOutputStream.closeEntry()
                inputStream.close()
            }
            //结束
            jarOutputStream.close()
            jarFile.close()
            def dest = outputProvider.getContentLocation(jarName + md5Name,
                    jarInput.contentTypes, jarInput.scopes, Format.JAR)
            FileUtils.copyFile(tmpFile, dest)
            tmpFile.delete()
        } else {
            println "跳过jar文件 : ${jarInput.file.name}"
        }
    }

    void collectInjectClassesInfo(Collection<TransformInput> inputs) {
        inputs.forEach {
            it.directoryInputs.forEach {
                collectDirectoryInput(it)
            }

            it.jarInputs.forEach {
                collectJarInput(it)
            }
        }

        println("包含注入注解的类收集完毕")
        for (key in clsPool.mapContainInsertClass.keySet()) {
            println "$key"
        }

        println("注入目标类收集完毕")
        for (key in clsPool.targetMap.keySet()) {
            println "$key : ${clsPool.targetMap[key]}\n"
        }
    }

    void collectDirectoryInput(DirectoryInput directoryInput) {
        if (directoryInput.file.isDirectory()) {
            directoryInput.file.eachFileRecurse {
                def name = it.name
                if (!Util.isFilterClassFile(name, "collectDirectoryInput")) {
                    clsPool.appendClassPathWithFile(it)
                    println("收集目录下文件信息，当前文件=${it.absolutePath}")
                    clsPool.collectUsedClass(it, null, null)
                }
            }
        }
    }

    void collectJarInput(JarInput jarInput) {
        if (jarInput.file.absolutePath.contains(".gradle/caches") &&
                !jarInput.file.absolutePath.contains("lib_annotation")) {
            return
        }
        if (jarInput.file.absolutePath.endsWith(".jar")) {
            JarFile jarFile = new JarFile(jarInput.file)
            clsPool.appendClassPathWithFile(jarInput.file)
            Enumeration enumeration = jarFile.entries()
            //用于保存
            while (enumeration.hasMoreElements()) {
                JarEntry jarEntry = (JarEntry) enumeration.nextElement()
                String entryName = jarEntry.getName()
                InputStream inputStream = jarFile.getInputStream(jarEntry)
                if (!Util.isFilterClassFile(entryName, "collectJarInput")) {
                    println("收集jar文件 ${jarFile.name} 中信息。当前 entryName = $entryName")
                    clsPool.collectUsedClass(null, entryName, clsPool.makeClass(inputStream))
                }
                inputStream.close()
            }
            //结束
            jarFile.close()
        }
    }
}