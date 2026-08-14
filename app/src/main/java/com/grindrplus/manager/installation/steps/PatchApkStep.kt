package com.grindrplus.manager.installation.steps

import android.content.Context
import com.grindrplus.manager.installation.BaseStep
import com.grindrplus.manager.installation.Print
import com.reandroid.apk.ApkModule
import com.reandroid.xml.StyleDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.lsposed.patch.LSPatch
import org.lsposed.patch.util.Logger
import java.io.File
import java.io.IOException

// 5th
class PatchApkStep(
    private val unzipFolder: File,
    private val outputDir: File,
    private val modFile: File,
    private val keyStore: File,
    private val customMapsApiKey: String?,
    private val embedLSPatch: Boolean = true
) : BaseStep() {
    override val name = "Patching Grindr APK"

    private companion object {
        const val MAPS_API_KEY_NAME = "com.google.android.geo.API_KEY"
    }

    override suspend fun doExecute(context: Context, print: Print) {
        print("Cleaning output directory...")
        outputDir.listFiles()?.forEach { it.delete() }

        val apkFiles = unzipFolder.listFiles()?.filter { it.name.endsWith(".apk") && it.exists() && it.length() > 0 }

        if (apkFiles.isNullOrEmpty()) {
            throw IOException("No valid APK files found to patch")
        }

        try {
            val baseApk = apkFiles.find {
                it.name == "base.apk" || it.name.startsWith("base.apk-")
            } ?: apkFiles.first()

            print("Inspecting ${baseApk.name} for manifest adjustments...")
            val apkModule = ApkModule.loadApkFile(baseApk)
            var manifestModified = false

            // Neutralize PairIP DRM wrapper by pointing application class directly to RealApplication
            val appElement = apkModule.androidManifest.applicationElement
            val appNameAttr = appElement.searchAttributeByName("name")
            if (appNameAttr != null && appNameAttr.valueString == "com.pairip.application.Application") {
                print("PairIP wrapper detected. Replacing application class with RealApplication...")
                appNameAttr.setValueAsString(StyleDocument.parseStyledString("com.grindrapp.android.RealApplication"))
                manifestModified = true
            }

            if (customMapsApiKey != null) {
                print("Attempting to apply custom Maps API key...")
                val metaElements = apkModule.androidManifest.applicationElement.getElements { element ->
                    element.name == "meta-data"
                }

                var found = false
                while (metaElements.hasNext() && !found) {
                    val element = metaElements.next()
                    val nameAttr = element.searchAttributeByName("name")

                    if (nameAttr != null && nameAttr.valueString == MAPS_API_KEY_NAME) {
                        val valueAttr = element.searchAttributeByName("value")
                        if (valueAttr != null) {
                            print("Found Maps API key element, replacing with custom key")
                            valueAttr.setValueAsString(StyleDocument.parseStyledString(customMapsApiKey))
                            found = true
                            manifestModified = true
                        }
                    }
                }

                if (!found) {
                    print("Maps API key element not found in manifest, skipping replacement")
                }
            }

            if (manifestModified) {
                print("Saving modified manifest to ${baseApk.name}...")
                apkModule.writeApk(baseApk)
                print("Manifest updated successfully")
            }
        } catch (e: Exception) {
            print("Error adjusting manifest: ${e.message}")
        }

        // Neutralize PairIP native binary in any split APKs
        replacePairIpNativeLibs(context, apkFiles, print)

        if (!embedLSPatch) {
            print("Skipping LSPatch as embedLSPatch is disabled")

            apkFiles.forEach { apkFile ->
                val outputFile = File(outputDir, apkFile.name)
                apkFile.copyTo(outputFile, overwrite = true)
                print("Copied ${apkFile.name} to output directory")
            }

            val copiedFiles = outputDir.listFiles()
            if (copiedFiles.isNullOrEmpty()) {
                throw IOException("Copying APKs failed - no output files generated")
            }

            print("Copying completed successfully")
            print("Copied ${copiedFiles.size} files")

            copiedFiles.forEachIndexed { index, file ->
                print("  ${index + 1}. ${file.name} (${file.length() / 1024}KB)")
            }

            return
        }

        print("Starting LSPatch process with ${apkFiles.size} APK files")

        val apkFilePaths = apkFiles.map { it.absolutePath }.toTypedArray()

        val logger = object : Logger() {
            override fun d(message: String?) {
                message?.let { print("DEBUG: $it") }
            }

            override fun i(message: String?) {
                message?.let { print("INFO: $it") }
            }

            override fun e(message: String?) {
                message?.let { print("ERROR: $it") }
            }
        }

        print("Using mod file: ${modFile.absolutePath}")
        print("Using keystore: ${keyStore.absolutePath}")

        withContext(Dispatchers.IO) {
            LSPatch(
                logger,
                *apkFilePaths,
                "-o", outputDir.absolutePath,
                "-l", "2",
                "-f",
                "-v",
                "-m", modFile.absolutePath,
                "-k", keyStore.absolutePath,
                "password",
                "alias",
                "password"
            ).doCommandLine()
        }

        val patchedFiles = outputDir.listFiles()
        if (patchedFiles.isNullOrEmpty()) {
            throw IOException("Patching failed - no output files generated")
        }

        print("Patching completed successfully")
        print("Generated ${patchedFiles.size} patched files")

        patchedFiles.forEachIndexed { index, file ->
            print("  ${index + 1}. ${file.name} (${file.length() / 1024}KB)")
        }
    }

    private fun replacePairIpNativeLibs(context: Context, apkFiles: List<File>, print: Print) {
        val stubMap = mapOf(
            "lib/x86_64/libpairipcore.so" to "pairip/libpairipcore_x86_64.so",
            "lib/arm64-v8a/libpairipcore.so" to "pairip/libpairipcore_arm64_v8a.so",
            "lib/armeabi-v7a/libpairipcore.so" to "pairip/libpairipcore_armeabi_v7a.so",
            "lib/x86/libpairipcore.so" to "pairip/libpairipcore_x86.so"
        )

        for (apk in apkFiles) {
            try {
                var foundPairIp = false
                val zipFile = java.util.zip.ZipFile(apk)
                for (entryName in stubMap.keys) {
                    if (zipFile.getEntry(entryName) != null) {
                        foundPairIp = true
                        break
                    }
                }
                zipFile.close()

                if (foundPairIp) {
                    print("Replacing PairIP native library in ${apk.name} with stub...")
                    val tempApk = File(apk.parentFile, "${apk.name}.tmp")
                    val zin = java.util.zip.ZipInputStream(apk.inputStream().buffered())
                    val zout = java.util.zip.ZipOutputStream(tempApk.outputStream().buffered())

                    var entry = zin.nextEntry
                    while (entry != null) {
                        val assetPath = stubMap[entry.name]
                        if (assetPath != null) {
                            val newEntry = java.util.zip.ZipEntry(entry.name)
                            zout.putNextEntry(newEntry)
                            context.assets.open(assetPath).use { it.copyTo(zout) }
                            zout.closeEntry()
                        } else {
                            zout.putNextEntry(java.util.zip.ZipEntry(entry.name))
                            zin.copyTo(zout)
                            zout.closeEntry()
                        }
                        entry = zin.nextEntry
                    }
                    zin.close()
                    zout.close()

                    if (apk.delete()) {
                        tempApk.renameTo(apk)
                        print("Successfully neutralized PairIP in ${apk.name}")
                    } else {
                        tempApk.delete()
                    }
                }
            } catch (e: Exception) {
                print("Warning: Failed to stub PairIP library in ${apk.name}: ${e.message}")
            }
        }
    }
}