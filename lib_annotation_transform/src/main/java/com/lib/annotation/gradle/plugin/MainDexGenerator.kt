package com.lib.annotation.gradle.plugin

import groovy.transform.CompileStatic
import org.gradle.api.Action
import org.gradle.api.Task
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.stream.Collectors

@CompileStatic
class MainDexGenerator(private val excludedList: List<String>): Action<Task> {
    override fun execute(task: Task) {
        for (inputFile in task.inputs.files.files) {
            if (inputFile.absolutePath.endsWith(MAIN_DEX_FILE)) {
                val result = Files.lines(inputFile.toPath())
                    .filter { isNotMatch(it) }
                    .collect(Collectors.toList())
                Files.write(inputFile.toPath(), result, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
                break
            }
        }
    }

    private fun isNotMatch(line: String): Boolean {
        for (item in excludedList) {
            if (line.contains(item)) {
                return false
            }
        }
        return true
    }

    companion object {
        const val MAIN_DEX_FILE = "mainDexList.txt"
    }
}