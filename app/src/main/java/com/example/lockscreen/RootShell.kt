package com.example.lockscreen

import java.io.InputStreamReader

object RootShell {

    @Volatile
    private var cachedRootAvailable: Boolean? = null

    fun isRootAvailable(forceCheck: Boolean = false): Boolean {
        if (!forceCheck) {
            cachedRootAvailable?.let { return it }
        }

        val available = runCatching {
            val process = ProcessBuilder("su", "-c", "id -u").redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            val exitCode = process.waitFor()
            exitCode == 0 && output == "0"
        }.getOrDefault(false)

        cachedRootAvailable = available
        return available
    }

    fun run(command: String): Boolean {
        val result = execute(command)
        return result.exitCode == 0
    }

    fun runForOutput(command: String): String {
        return execute(command).output.trim()
    }

    private fun execute(command: String): CommandResult {
        return runCatching {
            val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            val output = InputStreamReader(process.inputStream).buffered().use { it.readText() }
            val exitCode = process.waitFor()
            CommandResult(exitCode = exitCode, output = output)
        }.getOrDefault(CommandResult(exitCode = -1, output = ""))
    }

    private data class CommandResult(
        val exitCode: Int,
        val output: String
    )
}
