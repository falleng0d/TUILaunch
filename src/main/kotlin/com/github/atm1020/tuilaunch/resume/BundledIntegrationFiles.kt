package com.github.atm1020.tuilaunch.resume

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

object BundledIntegrationFiles {
    private const val STAGING_SUFFIX = ".tmp"

    fun ensure(resourcePath: String, target: Path): Path {
        val bundled = resourceBytes(resourcePath) ?: return target
        if (theTargetAlreadyHolds(target, bundled)) return target
        write(target, bundled)
        return target
    }

    private fun resourceBytes(resourcePath: String): ByteArray? = try {
        BundledIntegrationFiles::class.java.getResourceAsStream(resourcePath)?.use { it.readBytes() }
    } catch (_: IOException) {
        null
    }

    private fun theTargetAlreadyHolds(target: Path, bundled: ByteArray): Boolean = try {
        Files.isRegularFile(target) && Files.readAllBytes(target).contentEquals(bundled)
    } catch (_: IOException) {
        false
    }

    private fun write(target: Path, bundled: ByteArray) {
        val staging = target.resolveSibling("${target.fileName}$STAGING_SUFFIX")
        try {
            target.parent?.let { Files.createDirectories(it) }
            Files.write(staging, bundled)
            Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: IOException) {
            try {
                Files.deleteIfExists(staging)
            } catch (_: IOException) {
            }
        }
    }
}
