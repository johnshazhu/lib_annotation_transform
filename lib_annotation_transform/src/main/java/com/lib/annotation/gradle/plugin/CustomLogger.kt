package com.lib.annotation.gradle.plugin

import org.apache.commons.io.FileUtils
import org.gradle.api.logging.LogLevel
import org.gradle.api.logging.Logger
import org.slf4j.LoggerFactory
import java.io.File

object CustomLogger {
    private const val MAX_SEGMENT_SIZE = 8 * 1024
    private const val ENCODING = "UTF-8"
    private const val LOG_FILENAME = "AnnotationPlugin_build_log.txt"
    private val logger = LoggerFactory.getLogger(Logger::class.java)
    private var saveLogToFile = false
    private var buffer: StringBuffer = StringBuffer()
    private var logFile: File? = null
    private lateinit var logFilePath: String

    fun init(path: String) {
        if (isLogEnable()) {
            saveLogToFile = isSaveLogToFile()
            logFilePath = path
        }
    }

    fun log(msg: String, level: LogLevel = LogLevel.INFO) {
        when (level) {
            LogLevel.WARN -> {
                logger.warn(msg)
            }

            LogLevel.ERROR -> {
                logger.error(msg)
            }

            else -> {
                logger.info(msg)
            }
        }
        if (saveLogToFile) {
            saveLogToFile(msg, level == LogLevel.ERROR)
        }
    }

    private fun saveLogToFile(msg: String, realTimeSave: Boolean = false) {
        if (logFile == null) {
            logFile = File("${logFilePath}/$LOG_FILENAME").apply {
                if (exists()) {
                    delete()
                }
                createNewFile()
            }
        }
        logFile!!.run {
            if (buffer.length > MAX_SEGMENT_SIZE) {
                save(this)
            }
            buffer.append(msg).append("\n")
            if (realTimeSave) {
                save(this)
            }
        }
    }

    private fun save(file: File) {
        FileUtils.writeStringToFile(file, buffer.toString(), ENCODING, true)
        buffer.delete(0, buffer.length)
    }

    fun closeLogFile() {
        logFile?.run {
            if (buffer.isNotEmpty()) {
                FileUtils.writeStringToFile(this, buffer.toString(), ENCODING, true)
                buffer.delete(0, buffer.length)
            }
        }
    }
}