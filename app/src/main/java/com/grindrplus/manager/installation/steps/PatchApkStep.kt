package com.grindrplus.manager.installation.steps

import android.content.Context
import com.grindrplus.manager.installation.BaseStep
import com.grindrplus.manager.installation.Print
import com.grindrplus.patches.DexPatcher
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
    // Default false: LSPatch's DEX round-trip strips Kotlin metadata and crashes the app
    // with a kotlinx.coroutines NPE on first launch (see Issue #1 + README's known bug list).
    // LSPosed (rooted) is the supported path for runtime hooks; LSPatch-only installs must
    // bypass the LSPatch step until the metadata issue is fixed in LSPatch itself.
    private val embedLSPatch: Boolean = false
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

        val baseApk = apkFiles.find {
            it.name == "base.apk" || it.name.startsWith("base.apk-")
        } ?: apkFiles.first()

        try {
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

            // Disable PairIP's LicenseActivity — even if some code path tries to start it,
            // android:enabled=false prevents the system from launching it.
            apkModule.androidManifest.applicationElement.getElements { element ->
                element.name == "activity"
            }.forEach { activity ->
                val nameAttr = activity.searchAttributeByName("name")
                if (nameAttr != null && nameAttr.valueString?.contains("LicenseActivity") == true) {
                    val existing = activity.searchAttributeByName("enabled")
                    if (existing != null) {
                        existing.setValueAsString("false")
                    } else {
                        val enabledAttr = activity.newAttribute()
                        enabledAttr.setName("enabled", 0x0101000e) // android:enabled
                        enabledAttr.setValueAsString("false")
                        activity.addAttribute(enabledAttr)
                    }
                    print("Disabled LicenseActivity: ${nameAttr.valueString}")
                    manifestModified = true
                }
            }

            // Disable com.google.firebase.provider.FirebaseInitProvider — debug-signed APKs
            // hit a kotlinx.coroutines NPE during FirebaseApp initialization because the original
            // signing cert isn't present (zzc.<clinit> reads null zzjo.zza). Skipping Firebase
            // auto-init avoids the chain entirely.
            apkModule.androidManifest.applicationElement.getElements { element ->
                element.name == "provider"
            }.forEach { provider ->
                val nameAttr = provider.searchAttributeByName("name")
                if (nameAttr != null && nameAttr.valueString == "com.google.firebase.provider.FirebaseInitProvider") {
                    val existing = provider.searchAttributeByName("enabled")
                    if (existing != null) {
                        existing.setValueAsString("false")
                    } else {
                        val enabledAttr = provider.newAttribute()
                        enabledAttr.setName("enabled", 0x0101000e) // android:enabled
                        enabledAttr.setValueAsString("false")
                        provider.addAttribute(enabledAttr)
                    }
                    print("Disabled FirebaseInitProvider")
                    manifestModified = true
                }
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

            val extractNativeLibsAttr = appElement.searchAttributeByName("extractNativeLibs")
            if (extractNativeLibsAttr != null) {
                print("Setting extractNativeLibs to true...")
                extractNativeLibsAttr.setValueAsString(StyleDocument.parseStyledString("true"))
                manifestModified = true
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

        // Apply the PairIP + Firebase safe-guard no-op DEX pass to base.apk.
        // Uses dexlib2's in-memory `DexRewriter` + `DexPool.writeTo` — only the methods
        // we touch are rebuilt; every other class stays byte-identical, preserving
        // R8-inlined string constants (the root cause of Issue #1's NPE).
        try {
            print("Running DexPatcher on ${baseApk.name} (in-memory killPairIpFull + Firebase getComponents emptyList)...")
            val patchedCount = DexPatcher.patch(baseApk)
            print("DexPatcher: $patchedCount classes rewritten in ${baseApk.name}")
        } catch (e: Exception) {
            print("DexPatcher failed: ${e.message}")
            throw IOException("DexPatcher failed: ${e.localizedMessage}", e)
        }

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
                "-l", "0",
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

                if (foundPairIp) {
                    print("Replacing PairIP native library in ${apk.name} with stub...")
                    val tempApk = File(apk.parentFile, "${apk.name}.tmp")
                    val zout = java.util.zip.ZipOutputStream(tempApk.outputStream().buffered())

                    val entries = zipFile.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        val assetPath = stubMap[entry.name]
                        if (assetPath != null) {
                            val stubBytes = context.assets.open(assetPath).use { it.readBytes() }
                            val newEntry = java.util.zip.ZipEntry(entry.name)
                            if (entry.method == java.util.zip.ZipEntry.STORED) {
                                newEntry.method = java.util.zip.ZipEntry.STORED
                                newEntry.size = stubBytes.size.toLong()
                                newEntry.compressedSize = stubBytes.size.toLong()
                                val crc = java.util.zip.CRC32()
                                crc.update(stubBytes)
                                newEntry.crc = crc.value
                            }
                            zout.putNextEntry(newEntry)
                            zout.write(stubBytes)
                            zout.closeEntry()
                        } else {
                            val dataBytes = zipFile.getInputStream(entry).use { it.readBytes() }
                            val newEntry = java.util.zip.ZipEntry(entry.name)
                            if (entry.method == java.util.zip.ZipEntry.STORED) {
                                newEntry.method = java.util.zip.ZipEntry.STORED
                                newEntry.size = entry.size
                                newEntry.compressedSize = entry.compressedSize
                                newEntry.crc = entry.crc
                            }
                            zout.putNextEntry(newEntry)
                            zout.write(dataBytes)
                            zout.closeEntry()
                        }
                    }
                    zout.close()
                    zipFile.close()

                    if (apk.delete()) {
                        tempApk.renameTo(apk)
                        print("Successfully neutralized PairIP in ${apk.name}")
                    } else {
                        tempApk.delete()
                    }
                } else {
                    zipFile.close()
                }
            } catch (e: Exception) {
                print("Warning: Failed to stub PairIP library in ${apk.name}: ${e.message}")
            }
        }
    }
}