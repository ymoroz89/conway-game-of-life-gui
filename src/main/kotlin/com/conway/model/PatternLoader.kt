package com.conway.model

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.UncheckedIOException
import java.nio.charset.StandardCharsets

/** Loads [Pattern]s from plaintext `.cells` resources on the classpath. */
object PatternLoader {
    /** Classpath location of the Gosper glider gun shipped with the application. */
    const val GOSPER_GLIDER_GUN = "/patterns/gosper-glider-gun.cells"

    /**
     * Loads a pattern from a classpath resource.
     *
     * @throws IllegalArgumentException if the resource does not exist or is malformed
     * @throws UncheckedIOException     if the resource cannot be read
     */
    fun fromResource(resourcePath: String): Pattern {
        val stream = PatternLoader::class.java.getResourceAsStream(resourcePath)
            ?: throw IllegalArgumentException("Pattern resource not found: $resourcePath")

        // Kotlin has no checked exceptions, so an IOException here would otherwise escape
        // raw and unattributed. Wrapping it keeps the failure naming the resource that
        // could not be read, which is the contract this function documents.
        val lines = try {
            stream.use {
                BufferedReader(InputStreamReader(it, StandardCharsets.UTF_8)).use { reader ->
                    generateSequence(reader::readLine).toList()
                }
            }
        } catch (e: IOException) {
            throw UncheckedIOException("Failed to read pattern resource $resourcePath", e)
        }
        return Pattern.parse(nameOf(resourcePath), lines)
    }

    private fun nameOf(resourcePath: String): String {
        val slash = resourcePath.lastIndexOf('/')
        val fileName = if (slash < 0) resourcePath else resourcePath.substring(slash + 1)
        val dot = fileName.lastIndexOf('.')
        return if (dot < 0) fileName else fileName.substring(0, dot)
    }
}