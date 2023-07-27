package com.lib.annotation.gradle.plugin

import javassist.CtField
import javassist.CtMember
import javassist.CtMethod
import javassist.bytecode.AnnotationsAttribute
import javassist.bytecode.annotation.Annotation
import javassist.bytecode.annotation.ClassMemberValue
import org.apache.commons.io.IOUtils

import java.util.zip.ZipEntry

class Util {
    static void saveTestClassFile(byte[] bytes, String className) {
        if (className.endsWith("ImageUtil")) {
            try {
                File tmpClsFile = new File("E:\\" + className + ".class")
                if (!tmpClsFile.exists()) {
                    tmpClsFile.createNewFile()
                }
                OutputStream os = new FileOutputStream(tmpClsFile)
                IOUtils.write(bytes, os)
                os.close()
            } catch (Throwable r) {
                r.printStackTrace()
            }
        }
    }

    static void copy(InputStream input, OutputStream output, int bufferSize) throws IOException {
        byte[] buffer = new byte[bufferSize]

        int n
        while(0 <= (n = input.read(buffer))) {
            output.write(buffer, 0, n)
        }
    }

    static ZipEntry createZipEntry(ZipEntry zipEntry) {
        ZipEntry zipEntry2 = new ZipEntry(zipEntry.getName())
        zipEntry2.setComment(zipEntry.getComment())
        zipEntry2.setExtra(zipEntry.getExtra())
        return zipEntry2
    }

    static boolean isFilterPackage(String name) {
        return name.startsWith("org.greenrobot") ||
                name.startsWith("com.networkbench")
    }

    static boolean isFilterClassFile(String name, String from) {
        boolean isFilter = !name.endsWith(".class") ||
                name.startsWith("com/lib/annotation") ||
                name.startsWith("R\$") ||
                name == "R.class" ||
                name.endsWith("BuildConfig.class") ||
                name.startsWith("kotlin") ||
                name.startsWith("androidx") ||
                name.startsWith("android") ||
                name.startsWith("org/intellij") ||
                name.startsWith("org/jetbrains")
        return isFilter
    }

    static String getAnnotationClassValue(CtMember ctMember, Class annotationClass, String member) {
        AnnotationsAttribute attribute
        if (ctMember instanceof CtMethod) {
            attribute = (AnnotationsAttribute) ((CtMethod) ctMember).getMethodInfo().getAttribute(AnnotationsAttribute.visibleTag)
        } else if (ctMember instanceof CtField) {
            attribute = (AnnotationsAttribute) ((CtField) ctMember).getFieldInfo().getAttribute(AnnotationsAttribute.visibleTag)
        }
        Annotation annotation = attribute.getAnnotation(annotationClass.getName())
        return ((ClassMemberValue) annotation.getMemberValue(member)).getValue()
    }
}
