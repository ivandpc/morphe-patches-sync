package app.morphe.patches.music.sync

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.music.video.information.musicVideoIdHook
import app.morphe.patches.music.video.information.musicVideoInformationPatch
import app.morphe.patches.music.video.information.musicVideoTimeHook
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/music/sync/PlaybackSync;"

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
    }
}
