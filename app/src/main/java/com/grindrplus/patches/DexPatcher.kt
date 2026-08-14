/*
 * DexPatcher — on-device DEX no-op pass that replaces LSPatch.doCommandLine() in
 * PatchApkStep. Mirrors morphe-patches' `killPairIpFull` recipe.
 *
 * Implementation note: we use DexBuilder directly (NOT DexPool/DexRewriter) because
 * DexPool materialises the entire DEX pool in memory before writing — Android's
 * ~192MB heap OOMs on the 11MB classes.dex. DexBuilder writes incrementally to
 * the backing FileDataStore.
 *
 * No-ops applied (same spec as morphe-patches killPairIpFull + the cubesolver
 * emptyList-getComponents recipe):
 *
 *   1. PairIP verification chain:
 *      - SignatureCheck.verifyIntegrity → return-void
 *      - SignatureCheck.verifySignatureMatches → const/4 v0, 0x1; return v0
 *      - VMRunner.<clinit> → return-void  (blocks System.loadLibrary("pairipcore"))
 *      - VMRunner.invoke → const/4 v0, 0x0; return-object v0
 *      - StartupLauncher.launch → return-void
 *      - LicenseClient.{checkLicense, processResponse, startPaywallActivity,
 *        connectToLicensingService, initializeLicenseCheck,
 *        scheduleRepeatedLicenseCheck, handleError} → return-void
 *      - LicenseClient.performLocalInstallerCheck → const/4 v0, 0x1; return v0
 *      - LicenseClient.<clinit> — INTENTIONALLY UNTOUCHED (it initialises static
 *        state needed elsewhere; `repeatedCheckEnabled` already defaults to false)
 *      - Every external method that calls Lcom/pairip/VMRunner;->invoke(...) → return-void
 *
 *   2. Firebase ComponentRegistrar chain:
 *      - {Crashlytics, AnalyticsConnector, FirebasePerf, FirebaseSessions}Registrar
 *        .getComponents() → Collections.emptyList()
 */
package com.grindrplus.patches

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction10x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11n
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.DexFile
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.MethodImplementation
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.Reference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.writer.builder.DexBuilder
import com.android.tools.smali.dexlib2.writer.io.FileDataStore
import java.io.File
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object DexPatcher {

    private const val TAG = "DexPatcher"

    private val VmRunnerType = "Lcom/pairip/VMRunner;"

    private sealed class NoOpBody {
        object ReturnVoid : NoOpBody()
        data class ReturnInt(val value: Int) : NoOpBody()
        object ReturnNull : NoOpBody()
        object ReturnEmptyList : NoOpBody()
    }

    private data class MethodSpec(
        val type: String,
        val name: String,
        val params: List<String>,
        val body: NoOpBody,
    )

    private val CollectionsEmptyList = ImmutableMethodReference(
        "Ljava/util/Collections;",
        "emptyList",
        emptyList<String>(),
        "Ljava/util/List;",
    )

    private val pairIpSpecs = listOf(
        MethodSpec("Lcom/pairip/SignatureCheck;", "verifyIntegrity",
            listOf("Landroid/content/Context;"), NoOpBody.ReturnVoid),
        MethodSpec("Lcom/pairip/SignatureCheck;", "verifySignatureMatches",
            listOf("Ljava/lang/String;"), NoOpBody.ReturnInt(1)),
        MethodSpec("Lcom/pairip/VMRunner;", "<clinit>", emptyList(), NoOpBody.ReturnVoid),
        MethodSpec("Lcom/pairip/VMRunner;", "invoke",
            listOf("Ljava/lang/String;", "[Ljava/lang/Object;"), NoOpBody.ReturnNull),
        MethodSpec("Lcom/pairip/StartupLauncher;", "launch", emptyList(), NoOpBody.ReturnVoid),
        MethodSpec("Lcom/pairip/licensecheck/LicenseClient;", "checkLicense",
            listOf("Landroid/content/Context;"), NoOpBody.ReturnVoid),
        MethodSpec("Lcom/pairip/licensecheck/LicenseClient;", "processResponse",
            listOf("Landroid/content/Context;", "Lcom/pairip/licensecheck/LicenseResponse;"),
            NoOpBody.ReturnVoid),
        MethodSpec("Lcom/pairip/licensecheck/LicenseClient;", "startPaywallActivity",
            listOf("Landroid/content/Context;"), NoOpBody.ReturnVoid),
        MethodSpec("Lcom/pairip/licensecheck/LicenseClient;", "performLocalInstallerCheck",
            listOf("Landroid/content/Context;"), NoOpBody.ReturnInt(1)),
        MethodSpec("Lcom/pairip/licensecheck/LicenseClient;", "connectToLicensingService",
            emptyList(), NoOpBody.ReturnVoid),
        MethodSpec("Lcom/pairip/licensecheck/LicenseClient;", "initializeLicenseCheck",
            listOf("Landroid/content/Context;"), NoOpBody.ReturnVoid),
        MethodSpec("Lcom/pairip/licensecheck/LicenseClient;", "scheduleRepeatedLicenseCheck",
            listOf("Lcom/pairip/licensecheck/RepeatedCheckMetadata;"), NoOpBody.ReturnVoid),
        MethodSpec("Lcom/pairip/licensecheck/LicenseClient;", "handleError",
            listOf("Lcom/pairip/licensecheck/LicenseCheckException;"), NoOpBody.ReturnVoid),
    )

    private val firebaseSpecs = listOf(
        MethodSpec("Lcom/google/firebase/crashlytics/CrashlyticsRegistrar;",
            "getComponents", emptyList(), NoOpBody.ReturnEmptyList),
        MethodSpec("Lcom/google/firebase/analytics/connector/internal/AnalyticsConnectorRegistrar;",
            "getComponents", emptyList(), NoOpBody.ReturnEmptyList),
        MethodSpec("Lcom/google/firebase/perf/FirebasePerfRegistrar;",
            "getComponents", emptyList(), NoOpBody.ReturnEmptyList),
        MethodSpec("Lcom/google/firebase/sessions/FirebaseSessionsRegistrar;",
            "getComponents", emptyList(), NoOpBody.ReturnEmptyList),
    )

    private val allSpecs = pairIpSpecs + firebaseSpecs

    fun patch(baseApk: File): Int {
        require(baseApk.exists()) { "Base APK not found: ${baseApk.absolutePath}" }

        val dexFiles = listDexFiles(baseApk)
        val patched = HashMap<String, ByteArray>(dexFiles.size)
        var patchedCount = 0
        for ((dexName, dexBytes) in dexFiles) {
            val (bytes, count) = patchDex(dexBytes)
            patched[dexName] = bytes
            patchedCount += count
        }
        writePatchedDexes(baseApk, patched)
        return patchedCount
    }

    private data class DexFileEntry(val name: String, val bytes: ByteArray)

    private fun listDexFiles(apk: File): List<DexFileEntry> {
        val out = mutableListOf<DexFileEntry>()
        ZipFile(apk).use { zf ->
            for (entry in zf.entries()) {
                if (!entry.name.matches(Regex("classes\\d*\\.dex"))) continue
                out += DexFileEntry(entry.name, zf.getInputStream(entry).readBytes())
            }
        }
        return out.sortedBy { it.name }
    }

    private fun patchDex(dexBytes: ByteArray): Pair<ByteArray, Int> {
        val dex = DexBackedDexFile.fromInputStream(Opcodes.getDefault(), dexBytes.inputStream())

        val dexBuilder = DexBuilder(Opcodes.getDefault())
        var patchedCount = 0
        for (classDef in dex.classes) {
            val needsPatch = needsPatching(classDef)
            if (needsPatch) {
                // Build a new class with rebuilt methods. Fields stay byte-identical.
                val builderMethods = classDef.getMethods().map { method ->
                    val spec = matchSpec(method)
                    if (spec != null) {
                        patchedCount++
                        buildNoOpMethod(dexBuilder, method, spec.body)
                    } else if (callsVmInvoke(method)) {
                        patchedCount++
                        buildNoOpMethod(dexBuilder, method, NoOpBody.ReturnVoid)
                    } else {
                        // Original method unchanged — DexBuilder interns as BuilderMethod
                        // preserving its bytes.
                        dexBuilder.internMethod(
                            method.definingClass,
                            method.name,
                            method.parameters,
                            method.returnType,
                            method.accessFlags,
                            method.annotations,
                            method.hiddenApiRestrictions,
                            method.implementation,
                        )
                    }
                }
                // Intern fields unchanged (preserves bytes)
                val builderFields = classDef.getFields().map { field ->
                    dexBuilder.internField(
                        field.definingClass,
                        field.name,
                        field.getType(),
                        field.accessFlags,
                        field.initialValue,
                        field.annotations,
                        field.hiddenApiRestrictions,
                    )
                }
                dexBuilder.internClassDef(
                    classDef.getType(),
                    classDef.getAccessFlags(),
                    classDef.getSuperclass() ?: "",
                    classDef.getInterfaces(),
                    classDef.getSourceFile() ?: "",
                    classDef.getAnnotations(),
                    builderFields,
                    builderMethods,
                )
            } else {
                // Class is unchanged — rebuild via internClassDef (preserves bytes since
                // we pass through the original field/method/annotation lists).
                val builderFields = classDef.getFields().map { field ->
                    dexBuilder.internField(
                        classDef.getType(),
                        field.getName(),
                        field.getType(),
                        field.getAccessFlags(),
                        field.getInitialValue(),
                        field.getAnnotations(),
                        field.getHiddenApiRestrictions(),
                    )
                }
                val builderMethods = classDef.getMethods().map { method ->
                    // Wrap the DexBackedMethodImplementation in an ImmutableMethodImplementation
                    // so DexBuilder doesn't have to convert a DexBacked object directly
                    // (which can fail with "Exception occurred while writing code_item" on
                    // some Kotlin synthetic methods like La1;-><init>(Lkotlin/jvm/functions/Function0;I)V).
                    val origImpl = method.getImplementation()
                    val implForBuilder: com.android.tools.smali.dexlib2.iface.MethodImplementation =
                        if (origImpl == null) {
                            com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation(
                                1, emptyList(), emptyList(), emptyList()
                            )
                        } else {
                            com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation(
                                origImpl.getRegisterCount(),
                                origImpl.getInstructions(),
                                origImpl.getTryBlocks(),
                                emptyList(),  // strip debug info — re-applying it can trigger code_item encoding errors
                            )
                        }
                    dexBuilder.internMethod(
                        classDef.getType(),
                        method.getName(),
                        method.getParameters(),
                        method.getReturnType(),
                        method.getAccessFlags(),
                        method.getAnnotations(),
                        method.getHiddenApiRestrictions(),
                        implForBuilder,
                    )
                }
                dexBuilder.internClassDef(
                    classDef.getType(),
                    classDef.getAccessFlags(),
                    classDef.getSuperclass() ?: "",
                    classDef.getInterfaces(),
                    classDef.getSourceFile() ?: "",
                    classDef.getAnnotations(),
                    builderFields,
                    builderMethods,
                )
            }
        }

        val tmp = File.createTempFile("dex-out-", ".dex")
        try {
            val store = FileDataStore(tmp)
            dexBuilder.writeTo(store)
            store.close()
            return tmp.readBytes() to patchedCount
        } finally {
            tmp.delete()
        }
    }

    private fun needsPatching(classDef: ClassDef): Boolean {
        // PairIP classes always need to be checked (could have signature methods etc.)
        if (classDef.getType().startsWith("Lcom/pairip/")) {
            return classDef.getMethods().any { matchSpec(it) != null }
        }
        // Non-PairIP classes: only those that call VMRunner.invoke
        return classDef.getMethods().any { callsVmInvoke(it) }
    }

    private fun matchSpec(method: Method): MethodSpec? {
        val params = method.parameters.map { it.getType() }
        return allSpecs.firstOrNull {
            it.type == method.definingClass &&
                it.name == method.name &&
                it.params == params
        }
    }

    private fun callsVmInvoke(method: Method): Boolean {
        val impl = method.implementation ?: return false
        return impl.instructions.any { insn ->
            val ref = insn as? Reference ?: return@any false
            ref is MethodReference &&
                ref.definingClass == VmRunnerType &&
                ref.name == "invoke"
        }
    }

    private fun buildNoOpMethod(dexBuilder: DexBuilder, method: Method, body: NoOpBody): com.android.tools.smali.dexlib2.writer.builder.BuilderMethod {
        val oldImpl = method.implementation
        // Preserve the original register count exactly — some methods (e.g. synthetic
        // <init> for kotlin.lambdas) have specific requirements that would fail
        // code_item encoding if we arbitrarily lower regCount.
        val regCount = oldImpl?.registerCount ?: 1
        val instructions: List<BuilderInstruction> = when (body) {
            NoOpBody.ReturnVoid -> listOf(BuilderInstruction10x(Opcode.RETURN_VOID))
            is NoOpBody.ReturnInt -> listOf(
                BuilderInstruction11n(Opcode.CONST_4, 0, body.value),
                BuilderInstruction11x(Opcode.RETURN, 0),
            )
            NoOpBody.ReturnNull -> listOf(
                BuilderInstruction11n(Opcode.CONST_4, 0, 0),
                BuilderInstruction11x(Opcode.RETURN_OBJECT, 0),
            )
            NoOpBody.ReturnEmptyList -> listOf(
                BuilderInstruction35c(
                    Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0, CollectionsEmptyList
                ),
                BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
                BuilderInstruction11x(Opcode.RETURN_OBJECT, 0),
            )
        }
        val newImpl: MethodImplementation = ImmutableMethodImplementation(
            regCount, instructions, emptyList(), emptyList(),
        )
        // Use DexBuilder.internMethod which returns a BuilderMethod
        return dexBuilder.internMethod(
            method.definingClass,
            method.name,
            method.parameters,
            method.returnType,
            method.accessFlags,
            method.annotations,
            method.hiddenApiRestrictions,
            newImpl,
        )
    }

    private fun writePatchedDexes(apk: File, dexes: Map<String, ByteArray>) {
        val tmp = File(apk.parentFile, apk.name + ".tmp")
        ZipFile(apk).use { zin ->
            FileOutputStream(tmp).use { fos ->
                ZipOutputStream(fos).use { zout ->
                    for (entry in zin.entries()) {
                        val patched = dexes[entry.name]
                        if (patched != null) {
                            val crcValue = CRC32().apply { update(patched) }.value
                            val outEntry = ZipEntry(entry.name).apply {
                                method = ZipEntry.STORED
                                size = patched.size.toLong()
                                compressedSize = patched.size.toLong()
                                crc = crc
                                time = entry.time
                            }
                            zout.putNextEntry(outEntry)
                            zout.write(patched)
                            zout.closeEntry()
                        } else {
                            zout.putNextEntry(entry)
                            zin.getInputStream(entry).use { it.copyTo(zout) }
                            zout.closeEntry()
                        }
                    }
                }
            }
        }
        if (apk.exists()) apk.delete()
        if (!tmp.renameTo(apk)) throw RuntimeException("Failed to rename ${tmp.absolutePath} → ${apk.absolutePath}")
    }
}
