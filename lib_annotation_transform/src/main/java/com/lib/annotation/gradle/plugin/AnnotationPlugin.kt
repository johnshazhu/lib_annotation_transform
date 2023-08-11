package com.lib.annotation.gradle.plugin

import com.android.build.api.transform.*
import com.android.build.gradle.AppExtension
import com.android.build.gradle.internal.pipeline.TransformManager
import javassist.CtClass
import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.io.FileUtils
import org.apache.commons.io.IOUtils
import org.gradle.api.Plugin
import org.gradle.api.Project
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.*
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry

class AnnotationPlugin : Transform(), Plugin<Project> {
    private lateinit var android: AppExtension
    private lateinit var clsPool: CustomClassPool

    override fun getName(): String {
        return "AnnotationPlugin"
    }

    override fun getInputTypes(): MutableSet<QualifiedContent.ContentType> {
        return TransformManager.CONTENT_CLASS
    }

    override fun getScopes(): MutableSet<in QualifiedContent.Scope> {
        return TransformManager.SCOPE_FULL_PROJECT
    }

    override fun isIncremental(): Boolean {
        return false
    }

    override fun apply(target: Project) {
        android = target.extensions.getByType(AppExtension::class.java)
        android.registerTransform(this)

        getProperties(target)
        val props = Properties()
        props.load(this::class.java.classLoader.getResourceAsStream(VERSION_PROPERTIES))
        println("plugin apply, version = ${props.getProperty(VERSION)}")
        CustomLogger.init(target.rootDir.absolutePath)
    }

    override fun transform(transformInvocation: TransformInvocation) {
        val start = System.currentTimeMillis()

        clsPool = CustomClassPool(true)
        android.bootClasspath.forEach {
            log("appendClassPath ${it.name}")
            clsPool.appendClassPathByFile(it)
        }

        val outputProvider = transformInvocation.outputProvider
        if (!transformInvocation.isIncremental) {
            log("isIncremental is false, deleteAll output")
            outputProvider.deleteAll()
        }

        transformInvocation.inputs.run {
            // collect source insert info and their target info
            collectInjectClassesInfo(this)
            // traversal files to check if they are the insert targets
            injectPrepare(this)
            // insert code to target files
            injectClassFile(this, outputProvider)
        }

        clsPool.release()
        val end = System.currentTimeMillis()
        log("transform cost time = ${(end - start) / 1000}s")
    }

    private fun collectInjectClassesInfo(inputs: Collection<TransformInput>) {
        log("collectInjectClassesInfo")
        inputs.forEach { transformInput ->
            transformInput.directoryInputs.forEach { directoryInput ->
                clsPool.appendClassPathByFile(directoryInput.file)
                collectDirectoryInput(directoryInput)
            }

            transformInput.jarInputs.forEach { jarInput ->
                clsPool.appendClassPathByFile(jarInput.file)
                collectJarInput(jarInput)
            }
        }
        clsPool.logCollectResult()
    }

    private fun collectDirectoryInput(directoryInput: DirectoryInput) {
        if (directoryInput.file.isDirectory) {
            directoryInput.file.walkTopDown().forEach {
                if (!isIgnoreFileName(it.name) && isClassFile(it.name)) {
                    clsPool.appendClassPathByFile(it)
                    clsPool.collectUsedClass(it, null, null)
                }
            }
        }
    }

    private fun collectJarInput(jarInput: JarInput) {
        if (jarInput.file.absolutePath.endsWith(".jar")) {
            val jarFile = JarFile(jarInput.file)
            clsPool.appendClassPathByFile(jarInput.file)
            val enumeration = jarFile.entries()
            //用于保存
            while (enumeration.hasMoreElements()) {
                val jarEntry = enumeration.nextElement()
                val entryName = jarEntry.name
                val inputStream = jarFile.getInputStream(jarEntry)
                if (!isIgnoreFileName(entryName)) {
//                    log("collect info in jar file ${jarFile.name}。Current entryName = $entryName")
                    clsPool.collectUsedClass(null, entryName, clsPool.makeClass(inputStream))
                }
                inputStream.close()
            }
            //结束
            jarFile.close()
        }
    }

    private fun injectPrepare(inputs: Collection<TransformInput>) {
        inputs.forEach { transformInput ->
            transformInput.directoryInputs.forEach { directoryInput ->
                prepareInjectTargetInDirectory(directoryInput)
            }

            transformInput.jarInputs.forEach { jarInput ->
                prepareInjectTargetInJar(jarInput)
            }
        }
    }

    private fun prepareInjectTargetInDirectory(directoryInput: DirectoryInput) {
        if (directoryInput.file.isDirectory) {
            directoryInput.file.walkTopDown().filter {
                !isIgnoreFileName(it.name)
            }.forEach {
                val inputStream = FileInputStream(it)
                val ctClass = clsPool.makeClass(inputStream)
                inputStream.close()
                clsPool.injectPrepare(ctClass, it.absolutePath)
            }
        }
    }

    private fun prepareInjectTargetInJar(jarInput: JarInput) {
        if (!isFilterPackage(jarInput.name)) {
            val jarFile = JarFile(jarInput.file)
            val enumeration = jarFile.entries()
            while (enumeration.hasMoreElements()) {
                val jarEntry = enumeration.nextElement()
                if (!isIgnoreFileName(jarEntry.name)) {
                    val inputStream = jarFile.getInputStream(jarEntry)
                    val ctClass = clsPool.makeClass(inputStream)
                    clsPool.injectPrepare(ctClass, jarEntry.name)
                    inputStream.close()
                }
            }
            jarFile.close()
        }
    }

    private fun handleDirectoryInput(directoryInput: DirectoryInput, outputProvider: TransformOutputProvider) {
        if (directoryInput.file.isDirectory) {
            directoryInput.file.walkTopDown().filter {
                !isIgnoreFileName(it.name)
            }.forEach {
                clsPool.injectInsertInfo(it.absolutePath, directoryInput.file.absolutePath)
            }
        }

        //处理完输入文件之后，要把输出给下一个任务
        val dest = outputProvider.getContentLocation(directoryInput.name,
            directoryInput.contentTypes, directoryInput.scopes, Format.DIRECTORY)

        FileUtils.copyDirectory(directoryInput.file, dest)
    }

    private fun handleJarInputs(jarInput: JarInput, outputProvider: TransformOutputProvider) {
//        log("jarInput.file.absolutePath = ${jarInput.file.absolutePath}")
        if (jarInput.file.absolutePath.endsWith(".jar") && !isFilterPackage(jarInput.name)) {
            //重名名输出文件,因为同名会覆盖
            var jarName = jarInput.name
            val md5Name = DigestUtils.md5Hex(jarInput.file.absolutePath)
            if (jarName.endsWith(".jar")) {
                jarName = jarName.substring(0, jarName.length - ".jar".length)
            }
            val jarFile = JarFile(jarInput.file)
            val enumeration = jarFile.entries()
            val tmpFile = File("${jarInput.file.parent}${File.separator}classes_temp.jar")
            //避免上次的缓存被重复插入
            if (tmpFile.exists()) {
                tmpFile.delete()
            }
            val jarOutputStream = JarOutputStream(FileOutputStream(tmpFile))
            //用于保存
            while (enumeration.hasMoreElements()) {
                val jarEntry = enumeration.nextElement()
                val entryName = jarEntry.name
                val zipEntry = ZipEntry(entryName)
                val inputStream = jarFile.getInputStream(jarEntry)
                var ctClass: CtClass? = null
                if (!isIgnoreFileName(entryName) && clsPool.isTarget(entryName)) {
                    ctClass = clsPool.injectInJar(entryName, inputStream)
                }
                jarOutputStream.putNextEntry(zipEntry)
                if (ctClass != null) {
                    jarOutputStream.write(ctClass.toBytecode())
                    if (ctClass.isFrozen) {
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
            val dest = outputProvider.getContentLocation(jarName + md5Name, jarInput.contentTypes, jarInput.scopes, Format.JAR)
            FileUtils.copyFile(tmpFile, dest)
            tmpFile.delete()
        } else {
            log("skip jar file : ${jarInput.file.name}")
        }
    }

    private fun injectClassFile(inputs: Collection<TransformInput>, outputProvider: TransformOutputProvider) {
        //目录下class文件遍历修改
        inputs.forEach { transformInput ->
            transformInput.directoryInputs.forEach { directoryInput ->
                handleDirectoryInput(directoryInput, outputProvider)
            }

            transformInput.jarInputs.forEach { jarInput ->
                handleJarInputs(jarInput, outputProvider)
            }
        }
    }
}