package app.morphe.patches.music.sync

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.music.video.information.VideoEndFingerprint
import app.morphe.patches.music.video.information.musicVideoIdHook
import app.morphe.patches.music.video.information.musicVideoInformationPatch
import app.morphe.patches.music.video.information.musicVideoTimeHook
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference
import app.morphe.util.addStaticFieldToExtension
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction3rc
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/music/sync/PlaybackSync;"

/**
 * Player-state codes dispatched through the controller's K(I) method.
 * The play candidate ends with K(7), the pause candidate with K(3).
 */
private const val STATE_PLAY = 7
private const val STATE_PAUSE = 3

private fun Method.paramTypes() = parameters.map { it.type.toString() }

@Suppress("unused")
val playbackSyncPatch = bytecodePatch(
    name = "Playback sync",
    description = "Spotify Connect-style playback sync between devices via self-hosted server."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        // Provides video-id + ~1s position hooks and the seekTo bridge.
        musicVideoInformationPatch,
    )
    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        PreferenceScreen.MISC.addPreferences(
            SwitchPreference("morphe_music_sync_enabled"),
            TextPreference("morphe_music_sync_server_url"),
            TextPreference("morphe_music_sync_room"),
        )

        // Track change -> PlaybackSync.onVideoId(String).
        musicVideoIdHook(
            "$EXTENSION_CLASS_DESCRIPTOR->onVideoId(Ljava/lang/String;)V"
        )
        // Position tick (~1s) -> PlaybackSync.onVideoTime(J).
        musicVideoTimeHook(
            EXTENSION_CLASS_DESCRIPTOR,
            "onVideoTime",
        )

        val playerClass = VideoEndFingerprint.classDef
        val playerType = playerClass.type

        // Resolve the active player-controller: the field read in the seek
        // path whose type declares the D(String, String, Z) log method.
        val controllerField = findControllerField(playerType)
        val controllerType = controllerField.type
        val controllerClass = classDefBy(controllerType)

        val stateDispatcher = findStateDispatcher(controllerClass)
        val playMethod = findTransportMethod(controllerClass, controllerType, stateDispatcher, STATE_PLAY)
        val pauseMethod = findTransportMethod(controllerClass, controllerType, stateDispatcher, STATE_PAUSE)

        // Inject play()/pause() bridges into the extension. Each gets its own
        // player-instance field; both are populated by the constructor hook.
        addTransportBridge("playerInstancePlay", "play", playerType, controllerField, controllerType, playMethod)
        addTransportBridge("playerInstancePause", "pause", playerType, controllerField, controllerType, pauseMethod)

        // Populate both instance fields whenever a player is constructed.
        // Must be AFTER the super <init> call (like VideoInformationPatch does):
        // touching `this` before it fails class verification at load time.
        val playerConstructor = playerClass.methods.first { it.name == "<init>" }
        val superInitIndex = playerConstructor.indexOfFirstInstructionOrThrow {
            opcode == Opcode.INVOKE_DIRECT &&
                    getReference<MethodReference>()?.name == "<init>"
        } + 1
        playerConstructor.addInstruction(
            superInitIndex,
            "sput-object p0, $EXTENSION_CLASS_DESCRIPTOR->playerInstancePlay:$playerType",
        )
        playerConstructor.addInstruction(
            superInitIndex + 1,
            "sput-object p0, $EXTENSION_CLASS_DESCRIPTOR->playerInstancePause:$playerType",
        )
    }
}

/**
 * Controller field used in the VideoEnd (seek) method: traces the
 * D(String, String, Z) log call back to the IGET_OBJECT that loaded it.
 */
private fun BytecodePatchContext.findControllerField(playerType: String): FieldReference {
    val method = VideoEndFingerprint.method
    val insns = method.implementation?.instructions?.toList()
        ?: throw IllegalStateException("Playback sync: VideoEnd has no implementation")
    for (i in insns.indices) {
        val insn = insns[i]
        if (insn.opcode != Opcode.INVOKE_VIRTUAL && insn.opcode != Opcode.INVOKE_VIRTUAL_RANGE) continue
        val ref = (insn as? ReferenceInstruction)?.reference as? MethodReference ?: continue
        if (ref.name != "D") continue
        if (ref.parameterTypes.map(Any::toString) !=
            listOf("Ljava/lang/String;", "Ljava/lang/String;", "Z")
        ) continue
        if (ref.definingClass == playerType) continue
        val objReg = when (insn) {
            is Instruction35c -> insn.registerC
            is Instruction3rc -> insn.startRegister
            else -> continue
        }
        // Walk back to the iget that loaded objReg.
        for (j in (i - 1) downTo 0) {
            val prev = insns[j]
            if (prev.opcode != Opcode.IGET_OBJECT) continue
            if ((prev as TwoRegisterInstruction).registerA != objReg) continue
            val field = (prev as ReferenceInstruction).reference as? FieldReference ?: continue
            if (field.definingClass == playerType) return field
        }
    }
    throw IllegalStateException("Playback sync: controller field not found")
}

/**
 * The K(I) dispatcher: name of the single-int-param void method in the
 * controller class that forwards to a static two-arg (instance, int) method.
 */
private fun findStateDispatcher(controllerClass: ClassDef): String {
    for (m in controllerClass.methods) {
        if (m.returnType != "V") continue
        if (m.paramTypes() != listOf("I")) continue
        val impl = m.implementation ?: continue
        for (insn in impl.instructions) {
            if (insn.opcode != Opcode.INVOKE_STATIC && insn.opcode != Opcode.INVOKE_STATIC_RANGE) continue
            val ref = (insn as? ReferenceInstruction)?.reference as? MethodReference ?: continue
            if (ref.parameterTypes.size == 2 && ref.parameterTypes[1].toString() == "I") {
                return m.name
            }
        }
    }
    throw IllegalStateException("Playback sync: state dispatcher not found")
}

/** Name of the no-arg void method passing [stateCode] to the [dispatcher]. */
private fun findTransportMethod(
    controllerClass: ClassDef,
    controllerType: String,
    dispatcher: String,
    stateCode: Int,
): String {
    for (m in controllerClass.methods) {
        if (m.returnType != "V") continue
        if (m.parameters.isNotEmpty()) continue
        val impl = m.implementation ?: continue
        val insns = impl.instructions.toList()
        for (i in insns.indices) {
            val insn = insns[i]
            if (insn.opcode != Opcode.INVOKE_DIRECT && insn.opcode != Opcode.INVOKE_DIRECT_RANGE) continue
            val ref = (insn as? ReferenceInstruction)?.reference as? MethodReference ?: continue
            if (ref.definingClass != controllerType || ref.name != dispatcher) continue
            val argReg = when (insn) {
                is Instruction35c -> insn.registerD
                is Instruction3rc -> insn.startRegister + 1
                else -> continue
            }
            // Walk back for the const that loaded argReg with stateCode.
            for (j in (i - 1) downTo 0) {
                val prev = insns[j]
                if (prev !is NarrowLiteralInstruction) continue
                if ((prev as OneRegisterInstruction).registerA != argReg) continue
                if (prev.narrowLiteral == stateCode) return m.name
                break
            }
        }
    }
    throw IllegalStateException("Playback sync: transport method for state $stateCode not found")
}

/**
 * Adds `methodName()` to the extension: loads the player instance, reads the
 * controller field, and invokes the transport method. Single temp register.
 */
private fun BytecodePatchContext.addTransportBridge(
    fieldName: String,
    methodName: String,
    playerType: String,
    controllerField: FieldReference,
    controllerType: String,
    transportName: String,
) {
    addStaticFieldToExtension(
        EXTENSION_CLASS_DESCRIPTOR,
        methodName,
        fieldName,
        playerType,
        """
            sget-object v0, $EXTENSION_CLASS_DESCRIPTOR->$fieldName:$playerType
            if-eqz v0, :done
            iget-object v0, v0, $playerType->${controllerField.name}:$controllerType
            if-eqz v0, :done
            invoke-virtual { v0 }, $controllerType->$transportName()V
            :done
            return-void
        """,
    )
}
