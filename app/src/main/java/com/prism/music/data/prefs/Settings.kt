package com.prism.music.data.prefs

import com.prism.music.R
import com.prism.music.tr
import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore("prism_settings")

enum class ThemeMode(val label: String) { SYSTEM(tr(R.string.system)), LIGHT(tr(R.string.light)), DARK(tr(R.string.dark)) }
enum class ColorSource(val label: String) { ARTWORK(tr(R.string.widget_now_playing)), DYNAMIC(tr(R.string.wallpaper)), CUSTOM(tr(R.string.custom)) }
// CLEAR: see-through glass. Its own word (1.8.0): the shared "clear" is the verb ("Clear history"), and translations
// read it as "erase" (消去, Hapus…).
enum class GlassKind(val label: String) { REGULAR(tr(R.string.regular)), CLEAR(tr(R.string.glass_clear)) }
/** Appearance → Color → Spectrum: the split light through the app, or white light (neutral greys and ink, no accent). */
enum class LightStyle(val label: String) { SPECTRUM(tr(R.string.spectrum)), WHITE(tr(R.string.white_light)) }
/** Appearance → Color → Background (1.7.2): how much color the page itself carries. */
enum class SurfaceTint(val label: String) {
    /** As Prism always did: a hint of the accent in Spectrum, neutral in White light. */
    AUTO(tr(R.string.automatic_2)), NEUTRAL(tr(R.string.neutral)), ACCENT(tr(R.string.accent)), CUSTOM(tr(R.string.your_color))
}
/** Appearance → Color → Contrast (1.7.2): how firm secondary text, lines and the accent are. */
enum class ContrastLevel(val label: String, val minRatio: Float) { STANDARD(tr(R.string.standard), 4.5f), MEDIUM(tr(R.string.medium), 6f), HIGH(tr(R.string.high), 7.5f) }
/** Which of the cover's colors "Now playing" takes. */
enum class ArtworkSwatch(val label: String) { VIBRANT(tr(R.string.vibrant)), MUTED(tr(R.string.muted)), DOMINANT(tr(R.string.main_color)) }
/** How the "Now playing" color moves between songs. */
enum class ArtworkChange(val label: String, val ms: Int) { INSTANT(tr(R.string.at_once), 0), FADE(tr(R.string.fade), 900), SLOW(tr(R.string.slow_fade), 2400), ALBUM(tr(R.string.new_albums_only), 900) }
enum class AudioQuality(val label: String) { HIGH(tr(R.string.high)), NORMAL(tr(R.string.normal)), LOW(tr(R.string.low)) }
/** How lyrics show on Android Auto's player. */
enum class CarLyricsStyle(val label: String, val detail: String) {
    KARAOKE(tr(R.string.karaoke_2), tr(R.string.the_line_on_the_cover_art)),
    LINES(tr(R.string.line_by_line), tr(R.string.the_current_and_next_line_on)),
    TEXT(tr(R.string.title_only), tr(R.string.the_current_line_in_place_of)),
}

/** Where lyrics come from; [detail] says what each is good for, in Settings → Lyrics → Sources (#51). */
enum class LyricsSource(val label: String, val detail: String) {
    APPLE(tr(R.string.better_lyrics), tr(R.string.apple_music_s_lyrics_word_by)),
    LYRICSPLUS(tr(R.string.lyricsplus), tr(R.string.apple_music_musixmatch_and_spotify_s)),
    BINI(tr(R.string.bini), tr(R.string.apple_music_s_lyrics_word_by)),
    QQ(tr(R.string.qq_portato), tr(R.string.qq_music_word_by_word_the)),
    LRCLIB("LRCLIB", tr(R.string.open_lyrics_database_line_by_line)),
    NETEASE(tr(R.string.netease), tr(R.string.netease_cloud_music_word_by_word)),
    KUGOU(tr(R.string.kugou), tr(R.string.kugou_word_by_word_many_asian)),
    UNISON(tr(R.string.unison), tr(R.string.lyrics_listeners_submit_few_songs_so)),
    YTMUSIC(tr(R.string.prism_music), tr(R.string.lyricfind_lines_or_plain)),
    GENIUS(tr(R.string.genius), tr(R.string.plain_text_also_fills_in_starred)),
}

/** Tiles in Home's greeting section. */
enum class HomeShortcut(val label: String) {
    LIKED(tr(R.string.liked_songs)), DOWNLOADS(tr(R.string.downloads)), REPLAY(tr(R.string.replay_2)), LIBRARY(tr(R.string.library)),
    MOODS(tr(R.string.moods_genres)), SEARCH(tr(R.string.search)), EQUALIZER(tr(R.string.equalizer)), TOGETHER(tr(R.string.listen_together)),
}

/** A playlist pinned to Home's greeting tiles. */
@Serializable
data class PinnedPlaylist(val id: String, val title: String, val thumbnail: String? = null)

/** Where Autoplay finds what to play next. */
enum class AutoplaySource(val label: String) { MIXED(tr(R.string.both)), YOURS(tr(R.string.your_music)), YOUTUBE(tr(R.string.prism_music)) }

/** DIAL (Reddit, 1.7.9.1): pages of 3 × 3 square tiles, as YouTube Music's Speed dial, for any section's items. */
enum class SectionStyle(val label: String) { CAROUSEL(tr(R.string.carousel)), GRID(tr(R.string.grid)), LIST(tr(R.string.list)), HERO(tr(R.string.spotlight)), DIAL(tr(R.string.grid_3x3)) }

// ---------------------------------------------------------------- Look & layout

/**
 * The app's typeface. [PRISM] is Outfit, bundled with Prism (res/font, a variable font: every weight from one file).
 * The device fonts are used where the phone has them, with a fallback otherwise.
 */
/**
 * The app's typeface. Every one but System and Your font ships with Prism (res/font, SIL Open Font License), so it
 * looks the same on every phone; [face] is the typeface's own name. SERIF and MONO keep their stored names.
 */
enum class FontChoice(val label: String, val face: String? = null) {
    PRISM(tr(R.string.app_name), tr(R.string.outfit)), CLEAN(tr(R.string.clean), tr(R.string.manrope)), GEOMETRIC(tr(R.string.geometric), tr(R.string.plus_jakarta_sans)), AIRY(tr(R.string.airy), tr(R.string.urbanist)),
    ROUNDED(tr(R.string.rounded), tr(R.string.nunito)), SERIF(tr(R.string.editorial), tr(R.string.fraunces)), MONO(tr(R.string.mono), tr(R.string.geist_mono)),
    /** Made to be told apart at a glance: low vision (1.7.7). */
    LEGIBLE(tr(R.string.legible), tr(R.string.atkinson_hyperlegible)),
    /** Weighted letters that don't flip or swap: dyslexia (1.7.7). Two weights, not variable. */
    DYSLEXIC(tr(R.string.dyslexia_friendly), tr(R.string.opendyslexic)),
    SYSTEM(tr(R.string.system)),
    /** A .ttf / .otf the listener imported (#12; `AppFonts.FontSlot`). */
    CUSTOM(tr(R.string.your_font_2));

    companion object {
        /**
         * A stored font name as it reads now: 1.7 dropped Google Sans and Condensed (both needed fonts only some phones
         * have), and picks of either move to Clean.
         */
        fun upgrade(name: String): String = when (name) { "GOOGLE_SANS", "CONDENSED" -> CLEAN.name; else -> name }
    }
}

/** How round cards, tiles and artwork are. */
enum class Corners(val label: String, val scale: Float) { SHARP(tr(R.string.sharp), 0.2f), SUBTLE(tr(R.string.subtle), 0.6f), ROUND(tr(R.string.round), 1f), EXTRA(tr(R.string.extra), 1.5f) }
/** A cover's shape (1.7.7): drawn by Prism ([com.prism.music.ui.theme.CoverShapes]), no library. */
enum class CoverShape(val label: String) {
    ROUNDED(tr(R.string.rounded)), SQUARE(tr(R.string.square)), CIRCLE(tr(R.string.circle)), SQUIRCLE(tr(R.string.squircle)), FLOWER(tr(R.string.flower)), COOKIE(tr(R.string.cookie)), ARCH(tr(R.string.arch)), SLANTED(tr(R.string.slanted)),
}
/** Prism's buttons (Play, Shuffle, dialogs' buttons), 1.7.7. */
enum class ButtonShape(val label: String) { PILL(tr(R.string.pill)), ROUNDED(tr(R.string.rounded)), SQUARE(tr(R.string.square)) }

/** Lyrics, your way (1.7.5). Every default draws the lyrics as 1.7.4 did. Nested since 1.7.7 (see [ShapeLook]). */
data class LyricsLookPrefs(
    val style: LyricsStyle = LyricsStyle.PRISM,
    /** Focus blur's strength (0 off … 2 strong; 1 as before). It replaces the old on/off `lyricsBlur`, which it starts from. */
    val blurAmount: Float = 1f,
    /** Room between lines (0.6 tight … 1.8 airy; 1 as before). */
    val spacing: Float = 1f,
    /** How bright the lines not being sung are (0.2 … 1; 0.55 as before). */
    val othersAlpha: Float = 0.55f,
    val sungColor: LyricsColor = LyricsColor.WHITE,
    val sungCustom: Int = 0xFFFFD166.toInt(),
    val otherColor: LyricsColor = LyricsColor.WHITE,
    val otherCustom: Int = 0xFFB8C4FF.toInt(),
    val weight: LyricsWeight = LyricsWeight.LIGHT,
    /** Background vocals' size against their line (0.4 … 0.9; 0.6 as before). */
    val backingSize: Float = 0.6f,
    /** A soft shadow under the words, for photo and animated backgrounds. */
    val shadow: Boolean = false,
    val uppercase: Boolean = false,
    val ahead: LyricsAhead = LyricsAhead.ALL,
    val position: LyricsPosition = LyricsPosition.TOP,
    /** Translations and romanized lines: size (0.7 … 1.4 of before), color, italics and where they go. */
    val extraSize: Float = 1f,
    val extraColor: LyricsColor = LyricsColor.WHITE,
    val extraItalic: Boolean = false,
    val extraPlace: LyricsExtraPlace = LyricsExtraPlace.BELOW,
    val background: LyricsBackground = LyricsBackground.PLAYER,
    val immersiveBackground: LyricsBackground = LyricsBackground.PLAYER,
    /** [LyricsBackground.COLOR]'s colors; the second is a gradient's end (0: one color). */
    val bgColor: Int = 0xFF1B1530.toInt(),
    val bgColor2: Int = 0,
    /** The current-lyric strip and immersive mode draw the lyrics as the lyrics page does (style, colors, weight…). */
    val everywhere: Boolean = true,
    /** What an instrumental break shows on the lyrics page (#158): the three dots, or a music note. */
    val gapMark: LyricsGapMark = LyricsGapMark.DOTS,
    // 1.8.0 lyrics look (#253): the parts a Style used to fix, each the listener's own once picked. Unset (null), a
    // part is what [style] (now "Start from") gives it, so 1.7.9's looks, older Look codes and backups draw as they did.
    val motion: LyricsMotion? = null,
    val wordByWord: Boolean? = null,
    val card: Boolean? = null,
    val halo: Boolean? = null,
    val boldLines: Boolean? = null,
) {
    // 1.8.0 lyrics look: each part as it's drawn.
    /** How the line being sung moves. */
    val motionNow: LyricsMotion get() = motion ?: style.motion
    /** Lines timed only as a whole get word timings of their own (by length), so they move word by word too. */
    val wordByWordNow: Boolean get() = wordByWord ?: style.wordByWord
    /** Bold card: the lines on the cover's color, the ones to come dark (only with that background; see [pageBackground]). */
    val cardNow: Boolean get() = card ?: (style == LyricsStyle.CARD)
    /** A glow round the sung words (Android 12 and later). */
    val haloNow: Boolean get() = halo ?: (style == LyricsStyle.GLOW)
    /** Bold card's type: at least Bold, whatever [weight] says, until a weight is picked. */
    val boldLinesNow: Boolean get() = boldLines ?: (style == LyricsStyle.CARD)
    /** The weight the lines are drawn in. */
    val weightNow: LyricsWeight get() = if (boldLinesNow && weight.weight < LyricsWeight.BOLD.weight) LyricsWeight.BOLD else weight
    /** The lines' size against the size setting: starting from Bold card, a tenth bigger. */
    val sizeScale: Float get() = if (style == LyricsStyle.CARD) 1.1f else 1f
    /** What's behind the lyrics page: Bold card brings its own color when the background is left as it is. */
    val pageBackground: LyricsBackground get() = if (cardNow && background == LyricsBackground.PLAYER) LyricsBackground.COVER_COLOR else background

    /**
     * The look started again from [template] ("Start from"): its animation, type, card and glow, every other choice
     * (colors, size, where the lines sit…) kept. Bold card brings its color back when another background hid it.
     */
    fun startFrom(template: LyricsStyle): LyricsLookPrefs = copy(
        style = template, motion = null, wordByWord = null, card = null, halo = null, boldLines = null,
        background = if (template == LyricsStyle.CARD && background != LyricsBackground.COVER_COLOR) LyricsBackground.PLAYER else background,
    )

    /** Bold card is drawn on the lyrics page. */
    val onCard: Boolean get() = cardNow && pageBackground == LyricsBackground.COVER_COLOR

    /** Whether a part [style] sets (animation, type, card, glow) was changed since it was picked. */
    val changedFromStart: Boolean get() = startFrom(style).let { t ->
        motionNow != t.motionNow || wordByWordNow != t.wordByWordNow || haloNow != t.haloNow || weightNow != t.weightNow || onCard != t.onCard
    }
}

// ---------------------------------------------------------------------------- 1.8.0 lyrics look (#253)

/** How the line being sung moves (1.8.0: a part of its own; 1.7.9's Styles each had one). */
enum class LyricsMotion(val label: String, val note: String) {
    GROW(tr(R.string.grows_bolder), tr(R.string.each_word_grows_bolder_as_it)),
    FILL(tr(R.string.color_fills), tr(R.string.color_fills_the_words_as_they)),
    SWEEP(tr(R.string.light_sweeps), tr(R.string.a_light_sweeps_through_the_words)),
    TYPEWRITER(tr(R.string.typewriter), tr(R.string.words_appear_as_they_re_sung)),
    NONE(tr(R.string.none), tr(R.string.no_animation_the_line_simply_brightens)),
}

/** The animation each Style starts with: Prism's words grow, Karaoke fills, Bold card and Glow sweep a light. */
val LyricsStyle.motion: LyricsMotion get() = when (this) {
    LyricsStyle.PRISM -> LyricsMotion.GROW
    LyricsStyle.KARAOKE -> LyricsMotion.FILL
    LyricsStyle.CARD, LyricsStyle.GLOW -> LyricsMotion.SWEEP
    LyricsStyle.TYPEWRITER -> LyricsMotion.TYPEWRITER
    LyricsStyle.CALM -> LyricsMotion.NONE
}

/** Whether a Style moves lines timed only as a whole word by word (Karaoke, Bold card and Typewriter did). */
val LyricsStyle.wordByWord: Boolean get() = this == LyricsStyle.KARAOKE || this == LyricsStyle.CARD || this == LyricsStyle.TYPEWRITER

/** An instrumental break on the lyrics page (1.7.8, #158). */
enum class LyricsGapMark(val label: String) { DOTS(tr(R.string.three_dots)), NOTE(tr(R.string.music_note)) }

/** How heavy all of Prism's text is, against its own weights (1.7.7). */
enum class TypeWeight(val label: String, val shift: Int) { LIGHT(tr(R.string.light), -100), REGULAR(tr(R.string.regular), 0), BOLD(tr(R.string.bold), 100) }
/** How far apart letters sit (1.7.7), in em added to each style's own. */
enum class TypeSpacing(val label: String, val em: Float) { TIGHT(tr(R.string.tight), -0.015f), NORMAL(tr(R.string.normal), 0f), WIDE(tr(R.string.wide), 0.03f) }

/** Customize Prism → Text (1.7.7): weight, spacing, and a font of its own for titles. Nested (see [ShapeLook]). */
data class TypeLook(
    val weight: TypeWeight = TypeWeight.REGULAR,
    val spacing: TypeSpacing = TypeSpacing.NORMAL,
    /** Page, section and hero titles in another font than the rest; null: the same font. */
    val titleFont: FontChoice? = null,
    /** The player's song title (and artist): how heavy (Reddit R7). */
    val playerTitleWeight: PlayerTitleWeight = PlayerTitleWeight.LIGHT,
    /** Prism 1.6's type (1.7.8, #155): bold headings, no thin titles, Material's sizes. */
    val classic: Boolean = false,
)

/**
 * The player's title weight. [LIGHT] is Prism's own (its large titles are light, and Text → Weight moves them); the
 * others set the title outright, and Bold makes the artist's name heavier too.
 */
enum class PlayerTitleWeight(val label: String, val title: androidx.compose.ui.text.font.FontWeight?, val artist: androidx.compose.ui.text.font.FontWeight?) {
    LIGHT(tr(R.string.light), null, null),
    REGULAR(tr(R.string.regular), androidx.compose.ui.text.font.FontWeight.Normal, null),
    BOLD(tr(R.string.bold), androidx.compose.ui.text.font.FontWeight.Bold, androidx.compose.ui.text.font.FontWeight.SemiBold),
}

/** Customize Prism → Icons & shapes (1.7.7). */
data class ShapeLook(
    /** Corners as a slider: 0 square … 2× round; null follows [AppSettings.corners] (Looks made before 1.7.7 name a step). */
    val cornerScale: Float? = null,
    /** Covers' shapes: songs in lists, albums and playlists, artists, and the player's cover. */
    val songs: CoverShape = CoverShape.ROUNDED,
    val albums: CoverShape = CoverShape.ROUNDED,
    val artists: CoverShape = CoverShape.CIRCLE,
    val player: CoverShape = CoverShape.ROUNDED,
    val button: ButtonShape = ButtonShape.PILL,
    /** Album and playlist pages' Play: outlined rather than filled. */
    val playOutlined: Boolean = false,
    /** The current tab's icon: in ink as before, the accent, or the light. */
    val tabTint: TabTint = TabTint.INK,
    /** Prism 1.6's switches, sliders and buttons (1.7.8, #155): Material's, in the accent. */
    val classicControls: Boolean = false,
)

/** The current tab's icon color (1.7.7). */
enum class TabTint(val label: String) { INK(tr(R.string.ink)), ACCENT(tr(R.string.accent)), LIGHT(tr(R.string.the_light)) }

/**
 * How Prism behaves, added in 1.7.8 (not part of a Look). Nested (see [ShapeLook]). Every default keeps 1.7.7's way.
 */
data class BehaviorPrefs(
    /** Phones stay upright (#190); tablets keep their own layouts. */
    val lockPortrait: Boolean = false,
    /** Turning the phone's media volume all the way down pauses, turning it up again plays on (#157). */
    val pauseOnMute: Boolean = false,
    /** When a video goes picture-in-picture (#167). */
    val pip: PipMode = PipMode.AUTO,
    /** Swipe up on the player's lower half for the queue (or lyrics), down to close them (#200, #197). */
    val panelSwipes: Boolean = false,
    /** Home's Recently played: every song, or the albums and playlists they were played from (#196). */
    val recentShows: RecentShows = RecentShows.SONGS,
    /** The queue's order is locked: no drag handles, no swiping songs away (#159), so scrolling can't move anything. */
    val queueLocked: Boolean = false,
    /**
     * Scrolling back up the lyrics or the queue brings the player's controls back (as before, 1.7.9). Off, only a tap
     * or the bar at the bottom does (#214: scrolling up to an earlier chorus kept bringing them over the lyrics).
     */
    val scrollShowsControls: Boolean = true,
    /** Playlists' genre chips, each song's genre and Group by genre (1.7.9, Reddit); off, a playlist is just its songs in order. */
    val playlistGenres: Boolean = true,
    /** Pages fade into each other (#227: off, a page swaps at once, so a tap can't land on the one fading out). */
    val screenFades: Boolean = true,
    /** #259 (1.7.9.2): the cover's swipes, double tap and long press work around the controls too. */
    val gesturesEverywhere: Boolean = false,
    /** #208 (1.7.9.2): pages slide in from the side and Back slides them away, instead of only fading. */
    val pageSlide: Boolean = false,
    /** #264 (1.8.0): on a tablet, the tabs along the bottom or down the side; phones always have them at the bottom. */
    val tabBarPosition: TabBarPosition = TabBarPosition.SIDE,
    /** #216 (1.8.1): explicit songs shown and played (as before), skipped when they'd play, or hidden too. */
    val explicitContent: ExplicitContent = ExplicitContent.SHOW,
    /**
     * #296 (1.8.1): history is paused until this time (0: not paused; Long.MAX_VALUE: until turned back on). See
     * [com.prism.music.data.HistoryPause].
     */
    val historyPausedUntil: Long = 0L,
    /** #263 (1.8.1): seconds a song must be heard to count as a play (10, as always, unless changed). */
    val playCountSeconds: Int = 10,
    /** #263: YouTube Music's history waits for the same; off, it's told as a song starts (as before). */
    val playCountOnAccount: Boolean = false,
)

/**
 * Explicit songs (#216, 1.8.1): YouTube Music marks them "E". [SHOW] is as before; [SKIP] leaves them out of what plays
 * (radios, autoplay, mixes, the queue) while they stay listed; [HIDE] also leaves them out of search and the song lists.
 */
enum class ExplicitContent(val label: String) { SHOW(tr(R.string.explicit_show)), SKIP(tr(R.string.explicit_skip)), HIDE(tr(R.string.explicit_hide)) }

/** Where the tabs sit on a tablet or a wide window (#264); phones always have them at the bottom. Side = as before 1.8.0. */
enum class TabBarPosition(val label: String) { SIDE(tr(R.string.down_the_side)), BOTTOM(tr(R.string.at_the_bottom)) }
/**
 * Prism TV's settings and the phone's desk mode (1.8.0): how Prism behaves on a TV, and the ambient screen both share
 * (not part of a Look). Nested (see [ShapeLook]).
 */
data class TvPrefs(
    /** On a TV, minutes with no remote while music plays before the ambient screen (0: never). */
    val ambientMinutes: Int = 3,
    /** Phones: while music plays and the phone is charging, the ambient screen instead of the phone sleeping (#265). */
    val deskMode: Boolean = false,
    /** How large lyrics are on the TV (1 = as designed). */
    val lyricsScale: Float = 1f,
    /** This TV has shown Prism TV's welcome (sign in with the phone) once: also for a TV that had the phone app before. */
    val welcomed: Boolean = false,
    /**
     * The TV Home's rows, in order, shown or hidden (the owner, 1.8.0: "You should be able to customize the
     * homescreen"). Keyed as the phone's Home sections where the rows are the same kind ([HomeSections]), plus the TV's
     * own now-playing hero. Empty: Home as it comes (see `TvHomeLayout`).
     */
    val homeLayout: List<HomeSectionConfig> = emptyList(),
    /**
     * How dark the background behind the TV's player and lyrics is (1.8.1, Reddit: "options on TV to change background
     * opacity, especially in lyrics mode"): [DIM_LIGHTER] (a music video shows through more) to [DIM_DARKEST]; 0 is
     * Prism as it comes.
     */
    val backdropDim: Int = 0,
) {
    companion object {
        const val DIM_LIGHTER = -1
        const val DIM_DARKEST = 3

        /** Black laid over the cover's backdrop at [dim]: none as it comes (and lighter: the cover's own can't be). */
        fun coverShade(dim: Int): Float = when (dim.coerceIn(DIM_LIGHTER, DIM_DARKEST)) { 1 -> 0.25f; 2 -> 0.5f; 3 -> 0.75f; else -> 0f }

        /** How much of the shade over a music video (its lyrics' scrim) stays, at [dim]: 1 as it comes. */
        fun videoShade(dim: Int): Float = when (dim.coerceIn(DIM_LIGHTER, DIM_DARKEST)) { -1 -> 0.6f; 1 -> 1.2f; 2 -> 1.4f; 3 -> 1.6f; else -> 1f }
    }
}

/** Picture-in-picture for a playing video (1.7.8, #167). */
enum class PipMode(val label: String) { AUTO(tr(R.string.when_you_leave_prism)), MENU(tr(R.string.only_with_its_button)) }

/** What Home's Recently played lists (1.7.8, #196). */
enum class RecentShows(val label: String) { SONGS(tr(R.string.songs)), COLLECTIONS(tr(R.string.albums_playlists)) }

/** The player's look, added in 1.7.8 (part of a Look). Nested (see [ShapeLook]). Every default keeps 1.7.7's look. */
data class ViewLook(
    /** The player's controls while the lyrics or the queue are open (#193, #197). */
    val panelPlayer: PanelPlayer = PanelPlayer.AUTO_HIDE,
    /** The queue's "Played" and "Up next" headings (#197). */
    val queueHeadings: Boolean = true,
    /** #275 (1.8.1.1): the DJ button in the queue's header (off: more room there; DJ can be one of the player's buttons). */
    val queueDjChip: Boolean = true,
    /** The cast mark after the output's name under the player (#149, R13). */
    val castMark: Boolean = true,
    /** Immersive mode shows the playing video behind the lyrics instead of the cover (#170). */
    val immersiveVideo: Boolean = false,
    /** Full-bleed artwork: the cover cropped to fill the screen, or shown whole with a blurred fill around it (#175). */
    val bleedFit: BleedFit = BleedFit.FILL,
    /** Opening the player, the mini player's cover flies into place (#199); off, the player slides up as before. */
    val coverFlight: Boolean = false,
    /** Over a playing video, the controls hide after a few seconds; a tap brings them back (#166). */
    val videoControlsHide: Boolean = false,
    /** A mark on the progress bar where the crossfade into the next song starts (A6). */
    val crossfadeMark: Boolean = false,
    /** While a crossfade plays, the cover dissolves into the next song's over the blend (A6). */
    val blendCovers: Boolean = false,
    /** The player's bottom row in one glass capsule (1.7), or as separate buttons as in 1.6 (#155). */
    val buttonsPill: Boolean = true,
    /** #231 (1.7.9.2): the mini player's progress in the split light (as before), your accent, ink or the cover's color. */
    val miniProgressColor: ScrubberColor = ScrubberColor.SPECTRUM,
    /** #231: how strongly Fill tints the card (0.22 = Prism's, up to nearly solid). */
    val miniFillStrength: Float = 0.22f,
    /** #231: the split-light ring around the player's play button. */
    val playRing: Boolean = true,
    /** Reddit (1.8.1): play / pause on the mini player; off, a tap on it opens the player and its swipes still skip. */
    val miniPlayButton: Boolean = true,
    /** Reddit (1.7.9.2): on album, playlist and artist pages, the playing song's bars and title in the page's picture color. */
    val playingInPageColor: Boolean = false,
    /** 1.8.0: what's on the player and what's in its ⋯ menu ([PlayerParts]). */
    val parts: PlayerParts = PlayerParts(),
    /**
     * 1.8.1 (the owner's): with Crossfade on and a blend planned, the progress bar's end tapers as the song fades out and
     * the next song's bar grows in as it fades in, along the crossfade's own curve ([CrossfadeCurve.gains]).
     */
    val crossfadeShape: Boolean = false,
    /** #310 (1.8.1): the album's name under each song in Downloads (as before); off, just the artist. */
    val downloadsAlbums: Boolean = true,
    /** #289 (1.8.1): with more than one artist, each name under the player's title opens its own page (off: a menu asks which). */
    val artistLinks: Boolean = false,
    /** #283 (1.8.1): the "Now playing" background fades into the next song's cover (at "Between songs"' pace) instead of cutting. */
    val backdropFade: Boolean = false,
    /** #286 (1.8.1): what the Replay card's bars show: this year's months (as before), the last weeks or the last days. */
    val replayGraph: ReplayGraph = ReplayGraph.MONTHS,
    /** #287 (1.8.1): Replay and Rewind stories in your app font, instead of Prism's poster type. */
    val replayStoryFont: Boolean = false,
)

/** The Replay card's bars (#286): one for each month of this year so far, each of the last 12 weeks, or the last 14 days. */
enum class ReplayGraph(val label: String, val count: Int) {
    MONTHS(tr(R.string.replay_graph_months), 12), WEEKS(tr(R.string.replay_graph_weeks), 12), DAYS(tr(R.string.replay_graph_days), 14),
}

// 1.8.0 player editor
/**
 * A part of the player that can be hidden or shown ("What's on the player", 1.8.0). Only play / pause always stays.
 * Parts that had a switch of their own keep it ([AppSettings.shows]); the rest are kept in [PlayerParts.hidden].
 */
enum class PlayerPart(val label: String) {
    HANDLE(tr(R.string.handle_at_the_top)), FROM_ALBUM(tr(R.string.from_the_album)), COVER(tr(R.string.cover)),
    TITLE(tr(R.string.title)), ARTIST(tr(R.string.artist)), LIKE(tr(R.string.widget_like)), MENU(tr(R.string.song_menu)),
    LYRIC_LINE(tr(R.string.current_lyric)), PROGRESS(tr(R.string.progress_bar)), TIMES(tr(R.string.times)),
    QUALITY(tr(R.string.stream_quality)), BEST_PART(tr(R.string.best_part)), PREV_NEXT(tr(R.string.previous_and_next)),
    /** Shown only while Crossfade is on (Settings → Sound): the mark where the next song comes in, and the covers blending. */
    CROSSFADE_MARK(tr(R.string.crossfade_mark)), BLEND_COVERS(tr(R.string.covers_blend_too)),
    SHUFFLE_REPEAT(tr(R.string.shuffle_repeat)), VOLUME(tr(R.string.volume)), BUTTONS(tr(R.string.buttons)),
    OUTPUT(tr(R.string.output_device)), NAV_BAND(tr(R.string.band_behind_the_navigation_buttons));

    companion object {
        /** The parts with no switch of their own before 1.8.0: hidden ones are kept by name in [PlayerParts.hidden]. */
        val NEW = setOf(COVER, TITLE, ARTIST, LIKE, MENU, PROGRESS, PREV_NEXT, BUTTONS)
        /** Only on the player (and in the editor) while Crossfade is on. */
        val CROSSFADE = setOf(CROSSFADE_MARK, BLEND_COVERS)
    }
}

/** The ⋯ menu's groups, in the menu's order (1.8.0). */
enum class MenuGroup(val label: String) { SONG(tr(R.string.this_song)), LYRICS(tr(R.string.lyrics)), PLAYING(tr(R.string.playing_now)), HELP(tr(R.string.help)) }

/**
 * An action in the player's ⋯ menu (1.8.0), in its group's order. Any of them can be one of the four quick tiles or be
 * hidden ("Edit this menu"); a hidden one folds under "More" at the menu's end, never gone. "Player settings" and "Edit
 * this menu" aren't here: they always stay. [fromPlayer] actions only show while their part of the player is hidden;
 * a [foldable] one of them can still go under "More" while the player has another way to it ([menuFolds]).
 */
enum class MenuAction(val group: MenuGroup, val fromPlayer: Boolean = false, val foldable: Boolean = !fromPlayer) {
    ADD_TO_PLAYLIST(MenuGroup.SONG), RADIO(MenuGroup.SONG), DOWNLOAD(MenuGroup.SONG), ALBUM(MenuGroup.SONG), ARTIST(MenuGroup.SONG),
    CREDITS(MenuGroup.SONG), VERSION(MenuGroup.SONG),
    /** #305 (1.8.1): a cover of the listener's choosing for the song. */
    COVER(MenuGroup.SONG),
    NOT_FOR_ME(MenuGroup.SONG),
    SING(MenuGroup.LYRICS), LYRICS_SOURCE(MenuGroup.LYRICS), LYRICS_TIMING(MenuGroup.LYRICS), TRANSLATE(MenuGroup.LYRICS),
    ROMANIZE(MenuGroup.LYRICS), LYRICS_AGAIN(MenuGroup.LYRICS), LYRICS_INTO_FILE(MenuGroup.LYRICS),
    /** #345: "Show lyrics" can be folded away while the current lyric (or a cover gesture) opens them too. */
    OPEN_LYRICS(MenuGroup.PLAYING, true, foldable = true), OPEN_QUEUE(MenuGroup.PLAYING, true), VIDEO_SWITCH(MenuGroup.PLAYING, true),
    PREVIOUS(MenuGroup.PLAYING, true), NEXT(MenuGroup.PLAYING, true), DJ(MenuGroup.PLAYING, true), EQUALIZER(MenuGroup.PLAYING, true),
    SLEEP(MenuGroup.PLAYING), SPEED_PITCH(MenuGroup.PLAYING), OUTPUT(MenuGroup.PLAYING), IMMERSIVE(MenuGroup.PLAYING),
    VIDEO_DOWNLOAD(MenuGroup.PLAYING), LISTEN_TOGETHER(MenuGroup.PLAYING), BEST_PART(MenuGroup.PLAYING),
    ANIMATED_COVER(MenuGroup.PLAYING), SAVE_ANIMATED_COVER(MenuGroup.PLAYING),
    STATS(MenuGroup.HELP), MARK_STUTTER(MenuGroup.HELP);

    companion object {
        /** The tiles as the menu always had them. */
        val DEFAULT_TILES = listOf(RADIO, DOWNLOAD, ADD_TO_PLAYLIST, SLEEP)
        const val MAX_TILES = 4
    }
}

/**
 * What's on the player and in its ⋯ menu (1.8.0), in one value (AppSettings is at its slot limit). Every default is the
 * player and the menu as 1.7.9 had them. The menu's choices aren't part of a Look.
 */
data class PlayerParts(
    /** Hidden parts of [PlayerPart.NEW] (the others keep their own switches). */
    val hidden: Set<PlayerPart> = emptySet(),
    /** Menu actions folded under "More". */
    val menuHidden: Set<MenuAction> = emptySet(),
    /** The quick tiles, in order (up to [MenuAction.MAX_TILES]). */
    val menuTiles: List<MenuAction> = MenuAction.DEFAULT_TILES,
)

/** Whether [part] is on the player (each part that had its own switch still reads it). */
fun AppSettings.shows(part: PlayerPart): Boolean = when (part) {
    PlayerPart.HANDLE -> playerHandle
    PlayerPart.FROM_ALBUM -> showPlayingFrom
    PlayerPart.LYRIC_LINE -> lyricsOnPlayer
    PlayerPart.TIMES -> playerTimes != PlayerTimes.NONE
    PlayerPart.QUALITY -> showQuality
    PlayerPart.BEST_PART -> bestPartButton
    PlayerPart.SHUFFLE_REPEAT -> playerShuffleRepeat
    // With Prism's own volume the slider is the only way to change it: shown whatever a Look says (a Look applied
    // after picking it could hide it, and then it couldn't be brought back: canHide below).
    PlayerPart.VOLUME -> !hideVolumeBar || volumeControl == VolumeControl.PRISM
    PlayerPart.OUTPUT -> showOutputDevice
    PlayerPart.NAV_BAND -> playerNavBand
    PlayerPart.CROSSFADE_MARK -> view.crossfadeMark
    PlayerPart.BLEND_COVERS -> view.blendCovers
    else -> part !in view.parts.hidden
}

/** Prism's own volume (#116) has no buttons of its own: its slider stays while it's picked. */
fun AppSettings.canHide(part: PlayerPart): Boolean = !(part == PlayerPart.VOLUME && volumeControl == VolumeControl.PRISM)

/**
 * The ⋯ button shows unless hidden, and even then while nothing else on the player opens the menu: a long press on the
 * title or the artist does while either shows, and a gesture set to the song menu does.
 */
fun AppSettings.showsMenuButton(wide: Boolean = false): Boolean = shows(PlayerPart.MENU) ||
    (!shows(PlayerPart.TITLE) && !shows(PlayerPart.ARTIST) &&
        // The wide player's cover (a tablet, a phone on its side) only seeks and skips: no gesture opens the menu there.
        (wide || PlayerGesture.MENU !in listOf(coverSwipeLeft, coverSwipeRight, coverSwipeUp, coverDoubleTap, coverLongPress)))

/** The player's cover is drawn: shown, or (hidden) once it's shrinking into the header over the lyrics or the queue. */
fun AppSettings.drawsCover(collapsedPastHalf: Boolean): Boolean = shows(PlayerPart.COVER) || collapsedPastHalf

/**
 * A podcast episode's ⋯ menu gets what its hidden parts did too: with the row hidden, the queue (the episode keeps its
 * back / ahead skips whatever previous & next are set to, and its speed is in the menu already).
 */
fun AppSettings.episodeMenuFromPlayer(): List<MenuAction> = listOfNotNull(MenuAction.OPEN_QUEUE.takeIf { !shows(PlayerPart.BUTTONS) })

/** The bottom row's buttons as they show on the player (none while the row is hidden). */
fun AppSettings.rowButtons(): List<PlayerButton> =
    if (!shows(PlayerPart.BUTTONS)) emptyList() else playerButtons.filter { it != PlayerButton.VIDEO || showVideoSwitch }

/**
 * The ⋯ menu's [MenuAction.fromPlayer] actions: what the player no longer shows, so hiding never takes a feature away.
 * (Shuffle and repeat live in the queue, which is either on the row or here.)
 */
fun AppSettings.menuFromPlayer(): List<MenuAction> {
    val row = rowButtons()
    return listOfNotNull(
        MenuAction.OPEN_LYRICS.takeIf { PlayerButton.LYRICS !in row },
        MenuAction.OPEN_QUEUE.takeIf { PlayerButton.QUEUE !in row },
        MenuAction.VIDEO_SWITCH.takeIf { PlayerButton.VIDEO !in row && showVideoSwitch },
        MenuAction.PREVIOUS.takeIf { !shows(PlayerPart.PREV_NEXT) },
        MenuAction.NEXT.takeIf { !shows(PlayerPart.PREV_NEXT) },
        MenuAction.DJ.takeIf { PlayerButton.DJ in playerButtons && PlayerButton.DJ !in row },
        MenuAction.EQUALIZER.takeIf { PlayerButton.EQUALIZER in playerButtons && PlayerButton.EQUALIZER !in row },
    )
}

/**
 * #345: the player opens its lyrics without the menu's "Show lyrics": a tap on the current lyric, a swipe up on the
 * controls ("Swipe up for the queue"), or a gesture on the cover (while the cover's there, or gestures work anywhere).
 */
fun AppSettings.lyricsElsewhere(): Boolean = shows(PlayerPart.LYRIC_LINE) || behavior.panelSwipes ||
    ((shows(PlayerPart.COVER) || behavior.gesturesEverywhere) &&
        PlayerGesture.LYRICS in listOf(coverSwipeLeft, coverSwipeRight, coverSwipeUp, coverDoubleTap, coverLongPress))

/**
 * The [MenuAction.fromPlayer] actions the listener may fold under "More" now (#345): "Show lyrics" while the lyrics are
 * reachable another way ([lyricsElsewhere]); while it's the only way, it stays in the menu whatever was picked.
 */
fun AppSettings.menuFolds(): Set<MenuAction> = setOfNotNull(MenuAction.OPEN_LYRICS.takeIf { lyricsElsewhere() })

/** The ⋯ menu laid out (1.8.0): the quick tiles, each group's shown actions, and the hidden ones folded under "More". */
data class MenuLayout(val tiles: List<MenuAction>, val groups: List<Pair<MenuGroup, List<MenuAction>>>, val more: List<MenuAction>)

/**
 * [available]: the actions this song has (a file on the phone has no radio…), in any order. [folds]: the actions from
 * the player that may be folded away now ([menuFolds]).
 */
fun PlayerParts.menuLayout(available: Collection<MenuAction>, folds: Set<MenuAction> = emptySet()): MenuLayout {
    val here = available.toSet()
    val tiles = menuTiles.filter { it in here }.distinct().take(MenuAction.MAX_TILES)
    // An action from the player isn't folded away (it's there because its part is hidden), unless the player has
    // another way to it (#345).
    fun folds(a: MenuAction) = (!a.fromPlayer || a in folds) && a in menuHidden
    val rest = MenuAction.entries.filter { it in here && it !in tiles }
    return MenuLayout(
        tiles,
        MenuGroup.entries.map { g -> g to rest.filter { it.group == g && !folds(it) } }.filter { it.second.isNotEmpty() },
        rest.filter(::folds),
    )
}

/**
 * How music sounds, added in 1.7.8 (A1–A5, R12; not part of a Look). Nested (see [ShapeLook]). Every default keeps
 * 1.7.7's sound.
 */
data class SoundPrefs(
    /** How the two songs' volumes cross in a crossfade (A1). */
    val crossfadeCurve: CrossfadeCurve = CrossfadeCurve.EQUAL_POWER,
    /** Normalize volume's level, in LUFS (A2). */
    val normalizeTarget: Int = -14,
    /** An album playing through keeps its own song-to-song levels: one level for the whole album (A2). */
    val normalizeAlbums: Boolean = false,
    /** Songs' speed each time Prism starts (A4, R12): 0.5–2. */
    val musicSpeed: Float = 1f,
    /** Songs' pitch each time Prism starts, in semitones (A4, R12): −12 … +12. */
    val musicPitch: Int = 0,
    /** Skip silence in podcast episodes (A4); [AppSettings.skipSilence] is for songs. */
    val skipSilenceEpisodes: Boolean = false,
    /** The sleep timer fades the music out over this many seconds before it pauses (A5); 0 stops at once. */
    val sleepFadeSeconds: Int = 0,
    /** Pausing and playing fade the sound out and in over a moment instead of cutting (A5). */
    val pauseFade: Boolean = false,
    /**
     * #314 (1.8.1): your own MP3/AAC/Ogg files on the phone that match songs play in place of the stream and show in
     * Downloads ("Use your own song files"). Off by default; doesn't need lossless playback.
     */
    val ownLossyFiles: Boolean = false,
)

/** How a crossfade's two volumes cross (1.7.8, A1). */
enum class CrossfadeCurve(val label: String, val detail: String) {
    EQUAL_POWER(tr(R.string.smooth), tr(R.string.equal_power_the_loudness_holds_all)),
    LINEAR(tr(R.string.straight), tr(R.string.one_goes_down_as_the_other)),
    S_CURVE(tr(R.string.gentle), tr(R.string.slow_at_both_ends_quicker_in)),
    DJ_CUT(tr(R.string.dj_cut), tr(R.string.both_play_at_full_then_a));

    /** The (incoming, outgoing) volumes at [p] (0 … 1) of the way through the blend. */
    fun gains(p: Float): Pair<Float, Float> {
        val x = p.coerceIn(0f, 1f)
        return when (this) {
            EQUAL_POWER -> kotlin.math.sin(x * Math.PI / 2).toFloat() to kotlin.math.cos(x * Math.PI / 2).toFloat()
            LINEAR -> x to 1f - x
            S_CURVE -> (x * x * (3 - 2 * x)).let { it to 1f - it }
            DJ_CUT -> ((x - 0.42f) / 0.16f).coerceIn(0f, 1f).let {
                kotlin.math.sin(it * Math.PI / 2).toFloat() to kotlin.math.cos(it * Math.PI / 2).toFloat()
            }
        }
    }
}

/** Normalize volume's levels (A2): the loudness each one brings songs to. */
val NORMALIZE_TARGETS = listOf(-8 to tr(R.string.loud), -11 to tr(R.string.louder), -14 to tr(R.string.standard), -16 to tr(R.string.quieter), -23 to tr(R.string.broadcast))

/** The player's controls over the lyrics and the queue (1.7.8). */
enum class PanelPlayer(val label: String, val detail: String) {
    AUTO_HIDE(tr(R.string.hide_after_a_moment), tr(R.string.as_now_they_come_back_when)),
    ALWAYS(tr(R.string.always_shown), tr(R.string.the_controls_stay_under_the_lyrics)),
    HIDDEN(tr(R.string.full_screen), tr(R.string.lyrics_and_the_queue_get_the)),
    /** #230 (1.7.9.2): the controls stay, smaller (progress, play and the buttons), so they're less in the way of scrolling. */
    SMALL(tr(R.string.small), tr(R.string.progress_play_and_the_buttons_stay_smaller)),
}

/** Full-bleed artwork's fit (1.7.8, #175). */
enum class BleedFit(val label: String) { FILL(tr(R.string.fill_the_screen)), FIT(tr(R.string.whole_cover)) }

/** How much room rows and sections get. */
enum class UiDensity(val label: String, val scale: Float) { COMPACT(tr(R.string.compact), 0.7f), COMFORTABLE(tr(R.string.comfortable), 1f), ROOMY(tr(R.string.roomy), 1.3f) }

/** The big page titles (Library, Replay, Settings…). */
enum class TitleSize(val label: String) { LARGE(tr(R.string.large)), MEDIUM(tr(R.string.medium)), SMALL(tr(R.string.small)) }

/** Width of album / playlist cards in carousels. */
enum class CardSize(val label: String, val widthDp: Int) { SMALL(tr(R.string.small), 124), MEDIUM(tr(R.string.medium), 152), LARGE(tr(R.string.large), 184) }

enum class NavBarStyle(val label: String) { FLOATING(tr(R.string.floating)), DOCKED(tr(R.string.docked)), MINIMAL(tr(R.string.minimal)) }
enum class NavLabels(val label: String) { ALWAYS(tr(R.string.always)), SELECTED(tr(R.string.selected)), NEVER(tr(R.string.never)), WORDS(tr(R.string.words_only)) }

/** A tab's own name as kept: one line, no separators, short enough for the bar. */
fun cleanTabName(name: String) = name.replace(Regex("[|=\\n\\r]"), " ").trim().take(18)

/** "HOME=Start|LIBRARY=Mine": tabs given their own names (a shared Look's are cleaned the same way). */
fun parseTabNames(v: String): Map<NavTab, String> = v.split('|').mapNotNull { part ->
    val tab = NavTab.entries.firstOrNull { it.name == part.substringBefore('=') } ?: return@mapNotNull null
    cleanTabName(part.substringAfter('=', "")).takeIf { it.isNotEmpty() }?.let { tab to it }
}.toMap()
/** The tab bar's height (1.7.6): its floating, docked and minimal heights. */
enum class NavBarSize(val label: String, val floating: Int, val docked: Int, val minimal: Int) {
    COMPACT(tr(R.string.compact), 56, 54, 48), STANDARD(tr(R.string.standard), 64, 60, 54), TALL(tr(R.string.tall), 72, 68, 60),
}
/** What's behind the tabs (1.7.6): the glass as it is, lighter so the page shows through, or solid. */
enum class NavBarFill(val label: String) { GLASS(tr(R.string.as_the_glass)), LIGHT(tr(R.string.see_through)), SOLID(tr(R.string.solid)) }

/** Tabs the bottom bar can hold. */
/** The sections along the top of Local files, in the order the listener picked (#29). */
enum class LocalSection(val label: String) {
    SONGS(tr(R.string.songs)), FAVOURITES(tr(R.string.favorites)), ARTISTS(tr(R.string.artists)), ALBUMS(tr(R.string.albums)),
    ADDED_SONGS(tr(R.string.recently_added)), ADDED_ALBUMS(tr(R.string.added_albums)), DOWNLOADS(tr(R.string.downloads)),
}

enum class NavTab(val label: String) {
    HOME(tr(R.string.home)), SEARCH(tr(R.string.search)), LIBRARY(tr(R.string.library)), REPLAY(tr(R.string.replay_2)), SETTINGS(tr(R.string.settings)), LOCAL(tr(R.string.local_files)),
    // 1.7.6: any of these pages as a tab too.
    DOWNLOADS(tr(R.string.downloads)), LIKED(tr(R.string.liked_songs)), PODCASTS(tr(R.string.podcasts));

    companion object {
        /** The most tabs the bar holds (labels still fit on a phone). */
        const val MAX = 6
    }
}

enum class MiniPlayerStyle(val label: String) { CARD(tr(R.string.card)), SLIM(tr(R.string.slim)), PILL(tr(R.string.pill)) }
enum class MiniProgress(val label: String) { LINE(tr(R.string.line)), FILL(tr(R.string.fill)), NONE(tr(R.string.none)) }

/** What's behind the full player. */
enum class PlayerBackground(val label: String) {
    MESH(tr(R.string.color_mesh)), BLUR(tr(R.string.blurred_cover)),
    /** The background picked for Home in Appearance → Backgrounds, in its dark version. */
    APPEARANCE(tr(R.string.your_background)),
    THEME(tr(R.string.theme)), BLACK(tr(R.string.black)),
    /**
     * #320 (1.8.1): a white player, its type and controls dark (for the light theme; the lyrics and the queue keep their
     * dark blurred cover). Only when picked.
     */
    WHITE(tr(R.string.white)),
}
enum class TransportStyle(val label: String) { GLYPHS(tr(R.string.classic)), BUTTON(tr(R.string.play_button)), OUTLINE(tr(R.string.rings)) }
/** How wide the player's cover is: [extra] is how far it reaches into each side margin. */
enum class CoverSize(val label: String, val extra: Float) { STANDARD(tr(R.string.standard), 0f), LARGE(tr(R.string.large), 18f), FULL(tr(R.string.full_width), 30f) }
/** Immersive mode's cover: [zoom] 1 fills the screen (its sides cut off), 0 shows all of it across the width. */
/** How much of the queue is loaded ahead while a song plays: the next song's first seconds, or whole songs. */
enum class Preload(val label: String, val songs: Int) { START(tr(R.string.next_song_s_start), 0), NEXT(tr(R.string.next_song), 1), NEXT3(tr(R.string.next_3_songs), 3) }

/** Where immersive mode's lyrics go: a line at a time high, in the middle or low on the screen, or all of them over it. */
enum class ImmersiveLyrics(val label: String) { HIGH(tr(R.string.high)), MIDDLE(tr(R.string.middle)), LOW(tr(R.string.low)), FULL(tr(R.string.full_screen)) }
enum class ImmersiveArt(val label: String, val zoom: Float) { FILL(tr(R.string.fill_the_screen), 1f), ZOOMED(tr(R.string.zoomed_out), 0.4f), WHOLE(tr(R.string.whole_cover), 0f) }
/** [WAVE] (1.7.4): the song's own loudness, drawn from its scan, as the bar (a plain line until the song's been scanned). */
enum class ScrubberStyle(val label: String) {
    HAIRLINE(tr(R.string.hairline)), BOLD(tr(R.string.bold)), THUMB(tr(R.string.thumb)), WAVE(tr(R.string.waveform)),
    /** A filled bar and knob with thin strands flowing over the played part (#151, after One UI's; 1.7.7). */
    RIBBONS(tr(R.string.ribbons)),
}

// ---------------------------------------------------------------------------- The player, your way (1.7.4)

/** How the player is laid out: presets that reuse the same pieces (cover, deck, lyrics), each still tweakable. */
enum class PlayerLayout(val label: String, val note: String) {
    STANDARD(tr(R.string.standard), tr(R.string.the_cover_then_everything_under_it)),
    COVER(tr(R.string.cover), tr(R.string.a_big_cover_tap_it_for)),
    LYRICS(tr(R.string.lyrics), tr(R.string.opens_on_the_lyrics_a_small)),
    MINIMAL(tr(R.string.minimal), tr(R.string.the_title_and_play_gestures_do)),
    VINYL(tr(R.string.vinyl), tr(R.string.the_cover_as_a_record_s)),
    CASSETTE(tr(R.string.cassette), tr(R.string.the_cover_on_a_tape_whose)),
}

/** The knob at the progress bar's playhead ([AUTO]: a dot with the Thumb style, none with the others). */
enum class ScrubberThumb(val label: String) { AUTO(tr(R.string.with_the_style)), NONE(tr(R.string.none)), DOT(tr(R.string.dot)), LINE(tr(R.string.line)), RING(tr(R.string.ring)) }

/** How the visualizer moves with the music (1.7.4). */
enum class VisualizerStyle(val label: String, val note: String) {
    OFF(tr(R.string.off), tr(R.string.nothing_moves_with_the_music)),
    BARS(tr(R.string.bars), tr(R.string.along_the_bottom_of_the_player)),
    WAVE(tr(R.string.wave), tr(R.string.a_soft_wave_behind_the_title)),
    RING(tr(R.string.ring), tr(R.string.around_the_cover)),
    PULSE(tr(R.string.pulse), tr(R.string.the_progress_bar_pulses_with_the)),
    GLOW(tr(R.string.glow), tr(R.string.the_background_breathes_with_the_music)),
}

/** The parts under the player's title, in the order the listener arranged them. */
enum class DeckPart(val label: String) { STRIP(tr(R.string.current_lyric)), SCRUBBER(tr(R.string.progress_bar)), TRANSPORT(tr(R.string.controls)), VOLUME(tr(R.string.volume)), BUTTONS(tr(R.string.buttons)) }

/** What a button in the player's bottom row does: the listener picks three to six, in order. */
enum class PlayerButton(val label: String) {
    LYRICS(tr(R.string.lyrics)), VIDEO(tr(R.string.video)), QUEUE(tr(R.string.up_next)), OUTPUT(tr(R.string.play_on)), SLEEP(tr(R.string.sleep_timer)), LIKE(tr(R.string.widget_like)),
    DISLIKE(tr(R.string.not_for_me)), ADD_TO_PLAYLIST(tr(R.string.add_to_playlist)), SHARE(tr(R.string.share)), SING(tr(R.string.sing)), DJ(tr(R.string.dj_mode)),
    EQUALIZER(tr(R.string.equalizer)), DOWNLOAD(tr(R.string.download)), RADIO(tr(R.string.start_radio)), SPEED(tr(R.string.speed));

    companion object {
        /** As 1.7 had it: lyrics, the music video and the queue. */
        val DEFAULT = listOf(LYRICS, VIDEO, QUEUE)
        const val MIN = 3
        const val MAX = 6
    }
}

/**
 * The buttons beside previous / play / next in the media session, in order: the phone's notification and lock screen
 * show the first two, Android Auto and other controllers show them all.
 */
enum class SessionButton(val label: String) {
    LIKE(tr(R.string.widget_like)), SHUFFLE(tr(R.string.shuffle)), REPEAT(tr(R.string.repeat)), CAR_LYRICS(tr(R.string.lyrics_in_the_car)), RADIO(tr(R.string.start_radio)),
    DISLIKE(tr(R.string.not_for_me)), BACK_10(tr(R.string.back_10_s)), AHEAD_30(tr(R.string.ahead_30_s));

    companion object {
        /** As 1.7 had them (like and shuffle beside play on the phone). */
        val DEFAULT = listOf(LIKE, SHUFFLE, REPEAT, CAR_LYRICS, RADIO, DISLIKE)
    }
}

/** The progress bar's color. */
enum class ScrubberColor(val label: String) { SPECTRUM(tr(R.string.your_light)), ACCENT(tr(R.string.accent)), WHITE(tr(R.string.white)), COVER(tr(R.string.from_the_cover)) }

// ---------------------------------------------------------------------------- Lyrics, your way (1.7.5)

/** How the line being sung is drawn. [PRISM] is the lyrics as 1.7 drew them. */
enum class LyricsStyle(val label: String, val note: String) {
    PRISM(tr(R.string.app_name), tr(R.string.each_word_grows_bolder_as_it)),
    KARAOKE(tr(R.string.classic_karaoke), tr(R.string.color_fills_the_words_as_they)),
    CARD(tr(R.string.bold_card), tr(R.string.big_bold_lines_on_the_cover)),
    GLOW(tr(R.string.glow), tr(R.string.the_sung_words_glow)),
    TYPEWRITER(tr(R.string.typewriter), tr(R.string.words_appear_as_they_re_sung)),
    CALM(tr(R.string.calm), tr(R.string.no_animation_the_line_simply_brightens)),
}

/** What the player's volume slider changes (#116): the phone's media volume (as before), or Prism's own. */
enum class VolumeControl(val label: String) { PHONE(tr(R.string.the_phone_s_volume)), PRISM(tr(R.string.prism_s_own_volume)) }

/** A color for lyrics: white, your light, the accent, the cover's, or one of your own. */
enum class LyricsColor(val label: String) { WHITE(tr(R.string.white)), SPECTRUM(tr(R.string.your_light)), ACCENT(tr(R.string.accent)), COVER(tr(R.string.from_the_cover)), CUSTOM(tr(R.string.your_color)) }

/** How heavy the lyrics are; the sung word grows two steps bolder (Prism and Bold card). [LIGHT] as 1.7. */
enum class LyricsWeight(val label: String, val weight: Int) { THIN(tr(R.string.thin), 200), LIGHT(tr(R.string.light), 300), REGULAR(tr(R.string.regular), 400), MEDIUM(tr(R.string.medium), 500), BOLD(tr(R.string.bold), 700) }

/** Where the line being sung settles on the lyrics page ([fraction] of the way down). [TOP] as 1.7. */
enum class LyricsPosition(val label: String, val note: String, val fraction: Float) {
    TOP(tr(R.string.near_the_top), tr(R.string.the_most_lines_ahead_show), 0f),
    THIRD(tr(R.string.a_third_down), tr(R.string.a_few_sung_lines_stay_in), 0.28f),
    MIDDLE(tr(R.string.in_the_middle), tr(R.string.centered_as_on_a_lyric_sheet), 0.42f),
}

/** How many of the lines to come show on the lyrics page. [ALL] as 1.7. */
enum class LyricsAhead(val label: String, val lines: Int) { ALL(tr(R.string.all), Int.MAX_VALUE), THREE(tr(R.string.three), 3), ONE(tr(R.string.one), 1), NONE(tr(R.string.none), 0) }

/** Where a translation or romanized line goes. */
enum class LyricsExtraPlace(val label: String) { BELOW(tr(R.string.under_the_line)), ABOVE(tr(R.string.over_the_line)) }

/**
 * What's behind the lyrics: on the lyrics page [PLAYER] is the player's own background (as 1.7), in immersive mode it's
 * the cover filling the screen (as 1.7).
 */
enum class LyricsBackground(val label: String, val note: String) {
    PLAYER(tr(R.string.as_now), tr(R.string.the_player_s_background_or_the)),
    DARK(tr(R.string.plain_dark), tr(R.string.nothing_but_the_words)),
    BLUR(tr(R.string.blurred_cover), tr(R.string.the_cover_soft_and_dimmed)),
    CANVAS(tr(R.string.animated_cover), tr(R.string.the_song_s_moving_cover_when)),
    COVER_COLOR(tr(R.string.the_cover_s_color), tr(R.string.one_color_taken_from_the_cover)),
    COLOR(tr(R.string.your_colors), tr(R.string.a_color_or_two_as_a)),
}
enum class PlayerTitleAlign(val label: String) { START(tr(R.string.left)), CENTER(tr(R.string.centered)) }
enum class PlayerTitleSize(val label: String, val scale: Float) { SMALL(tr(R.string.small), 0.82f), MEDIUM(tr(R.string.medium), 1f), LARGE(tr(R.string.large), 1.2f) }
/** The times under the progress bar. */
enum class PlayerTimes(val label: String) { BOTH(tr(R.string.both)), ELAPSED(tr(R.string.elapsed)), LEFT(tr(R.string.time_left)), NONE(tr(R.string.none)) }

/** What a gesture on the player's cover (or the mini player) does. */
enum class PlayerGesture(val label: String) {
    NEXT(tr(R.string.next_song)), PREVIOUS(tr(R.string.previous_song)), SEEK_BACK(tr(R.string.back_10_s)), SEEK_AHEAD(tr(R.string.ahead_10_s)),
    /** Double tap only: the cover's left half goes back, its right half ahead (#79). */
    SEEK_SIDES(tr(R.string.seek_by_side)),
    PLAY_PAUSE(tr(R.string.play_pause_2)), LIKE(tr(R.string.widget_like)), QUEUE(tr(R.string.up_next)), LYRICS(tr(R.string.lyrics)), SLEEP(tr(R.string.sleep_timer)), VIDEO(tr(R.string.video)),
    SING(tr(R.string.sing)), MENU(tr(R.string.song_menu)), STOP(tr(R.string.stop)),
    /** Mini player only (#149, 1.7.7): what a tap does, for swiping up. */
    OPEN(tr(R.string.open_the_player)),
    NONE(tr(R.string.nothing));

    companion object {
        /** The choices offered for a gesture ([double]: double tap, which also has [SEEK_SIDES]; [mini]: the mini player). */
        fun choices(double: Boolean = false, mini: Boolean = false): List<PlayerGesture> = entries.filter {
            when (it) {
                SEEK_SIDES -> double
                STOP, OPEN -> mini
                LYRICS, VIDEO, SING -> !mini
                else -> true
            }
        }
    }
}
enum class LyricsAlign(val label: String) { START(tr(R.string.left)), CENTER(tr(R.string.center)) }
/** Lyrics in a script the listener can't read, in Latin letters too: under each line, or in its place. */
enum class RomanizeMode(val label: String) { OFF(tr(R.string.off)), UNDER(tr(R.string.under_each_line)), INSTEAD(tr(R.string.instead)) }
enum class LibraryView(val label: String) { GRID(tr(R.string.grid)), LIST(tr(R.string.list)) }
/** The top of Home (1.7.6): the greeting as before, only its buttons, or the greeting over a picture. */
enum class HomeHeader(val label: String, val note: String) {
    GREETING(tr(R.string.greeting), tr(R.string.the_date_and_a_hello_as)),
    NONE(tr(R.string.just_the_buttons), tr(R.string.no_greeting_home_starts_with_its)),
    PICTURE(tr(R.string.a_picture), tr(R.string.your_photo_or_a_design_behind)),
    NOW_PLAYING(tr(R.string.widget_now_playing), tr(R.string.the_playing_cover_frosted_behind_it)),
}
enum class HomeHeaderHeight(val label: String, val dp: Int) { SHORT(tr(R.string.short_text), 200), MEDIUM(tr(R.string.medium), 260), TALL(tr(R.string.tall), 340) }

/** What Search shows before you type (1.7.6), in the listener's order; the ones left out are hidden. */
enum class SearchSection(val label: String) {
    // The shortcut tiles (Recognize, Find by lyrics, Podcasts, Your files) went before release: the search box has
    // Recognize, a line of lyrics finds its song, and podcasts and your files are searched with everything else.
    RECENT(tr(R.string.recent)), ARTISTS(tr(R.string.your_artists)), MOODS(tr(R.string.moods_genres));

    companion object {
        fun parse(v: String?): List<SearchSection> =
            v?.split(',')?.mapNotNull { n -> entries.firstOrNull { it.name == n } }?.distinct()?.takeIf { it.isNotEmpty() } ?: entries.toList()
    }
}

/** Covers across Library's grid: Auto fits as many as the width allows (as before 1.7.6). */
enum class LibraryColumns(val label: String, val count: Int) { AUTO(tr(R.string.auto), 0), TWO("2", 2), THREE("3", 3), FOUR("4", 4) }

/** "PLAYLISTS=GRID|ALBUMS=LIST": Library tabs shown their own way. */
fun parseLibraryViews(v: String): Map<String, LibraryView> = v.split('|').mapNotNull { part ->
    val tab = part.substringBefore('=').takeIf { it in setOf("PLAYLISTS", "ALBUMS", "ARTISTS") } ?: return@mapNotNull null
    LibraryView.entries.firstOrNull { it.name == part.substringAfter('=', "") }?.let { tab to it }
}.toMap()
/** The tiles at the top of Library's Playlists tab (Reddit 1.7: "let me reorder or hide them"); shown in the order kept. */
enum class LibraryTile(val label: String) {
    LIKED(tr(R.string.liked_songs)), DOWNLOADS(tr(R.string.downloads)), LOCAL(tr(R.string.local_files)), REPLAY(tr(R.string.replay_2)),
    /** Library's own History tab as a tile (#136, 1.7.7); not shown until ticked. */
    HISTORY(tr(R.string.history)), PODCASTS(tr(R.string.podcasts)),
    TOGETHER(tr(R.string.listen_together)), BLEND(tr(R.string.blend)), RECOGNIZED(tr(R.string.recognized)), NEW_PLAYLIST(tr(R.string.new_playlist)), IMPORT(tr(R.string.import_playlists));

    companion object {
        /** A new install's tiles: all but those added later, so nobody's Library changes on its own. */
        val initial = entries - HISTORY
    }
}

@Serializable
data class HomeSectionConfig(
    val key: String,
    val title: String,
    val visible: Boolean = true,
    val style: SectionStyle = SectionStyle.CAROUSEL,
    /** The listener's own name for it (1.7.6); null keeps its own. */
    val name: String? = null,
    /** A pinned album's, playlist's or artist's picture (1.7.6), for Customize Home's list. */
    val art: String? = null,
) {
    /** What Home calls it: the listener's name for it, else [default] (or its own title). */
    fun titled(default: String = title) = name ?: default
}

@Serializable
data class EqSettings(
    val enabled: Boolean = false,
    val preset: String = "Flat",
    /** Gains in dB for the 10 ISO bands (31Hz .. 16kHz). */
    val bands: List<Float> = List(10) { 0f },
    val bassBoost: Int = 0,      // 0..1000
    val virtualizer: Int = 0,    // 0..1000
    val loudnessGainMb: Int = 0, // millibels
    val preampDb: Float = 0f,
)

object HomeSections {
    const val GREETING = "local:greeting"
    const val QUICK_PICKS = "local:quick_picks"
    const val RECENT = "local:recent"
    const val LIKED = "local:liked"
    const val DOWNLOADS = "local:downloads"
    const val REPLAY = "local:replay"
    const val GENRES = "local:genres"
    /** YouTube Music's Speed dial (#53): your go-to songs, albums, playlists and artists as big tiles, nine to a page. */
    const val SPEED_DIAL = "local:speed_dial"
    const val MOODS = "yt:moods"
    /** Prism's own mixes: the time-of-day mix, Daily Mixes, Release Radar. */
    const val MADE_FOR_YOU = "local:made_for_you"
    /** Release Radar's new albums, singles and EPs from the listener's artists. */
    const val NEW_RELEASES = "local:new_releases"
    /** Made on the phone from your own plays: your month's most played, what you used to play, likes gone quiet, mixes. */
    const val ON_REPEAT = "local:on_repeat"
    const val REPEAT_REWIND = "local:repeat_rewind"
    const val FORGOTTEN = "local:forgotten"
    /** Was its own section ("Mixes from your music"); its mixes are in Made for you now ([mergeYourMixes]). */
    const val YOUR_MIXES = "local:your_mixes"
    /**
     * Prism's holiday mixes (1.8.0): "Thanksgiving" through November, "Holiday music" through December, and not on Home
     * at all outside them ([inSeason]). Moved, renamed or hidden like any other section.
     */
    const val SEASONAL = "local:holiday"
    /** A holiday mix pinned on its own: "pin:holiday:<mix key>". */
    const val HOLIDAY_PIN = "holiday"
    const val YT_PREFIX = "yt:shelf:"
    /** One of YouTube Music's own shelves, by its title (the phone's Home and the TV's name it the same). */
    fun shelfKey(title: String) = YT_PREFIX + title.trim().lowercase()
    /** GREETING's key predates the rename: the section is only the shortcut tiles. */
    const val TILES_TITLE = "Shortcut tiles"
    /** Release Radar's section: not "New releases", which is YouTube's own shelf on Home too. */
    const val NEW_RELEASES_TITLE = "New from your artists"

    /**
     * Prism's own sections by their title in the listener's language (1.7.9). The stored title is the English default
     * (a rename is [HomeSectionConfig.name]), so it's swapped as the layout is read and saved back unchanged in meaning.
     */
    fun inYourLanguage(s: HomeSectionConfig): HomeSectionConfig {
        val id = when (s.key) {
            REPLAY -> com.prism.music.R.string.section_replay_teaser
            GREETING -> com.prism.music.R.string.section_shortcut_tiles
            QUICK_PICKS -> com.prism.music.R.string.quick_picks
            MADE_FOR_YOU -> com.prism.music.R.string.made_for_you
            NEW_RELEASES -> com.prism.music.R.string.section_new_from_your_artists
            RECENT -> com.prism.music.R.string.recently_played
            GENRES -> com.prism.music.R.string.your_top_genres
            MOODS -> com.prism.music.R.string.moods_genres
            LIKED -> com.prism.music.R.string.liked_songs
            DOWNLOADS -> com.prism.music.R.string.downloads
            SPEED_DIAL -> com.prism.music.R.string.section_speed_dial
            ON_REPEAT -> com.prism.music.R.string.section_on_repeat
            REPEAT_REWIND -> com.prism.music.R.string.section_repeat_rewind
            FORGOTTEN -> com.prism.music.R.string.section_forgotten_favorites
            SEASONAL -> com.prism.music.R.string.section_holiday_music
            else -> return s
        }
        return s.copy(title = com.prism.music.tr(id))
    }

    /** An album, playlist, artist or mix pinned to Home as a section of its own (1.7.6): "pin:album:<id>". */
    const val PIN_PREFIX = "pin:"
    fun pinKey(kind: String, id: String) = "$PIN_PREFIX$kind:$id"

    /** Sections made from rules over your own listening (1.7.6). */
    const val LIKED_MONTH = "rule:liked_month"
    const val UNPLAYED_DOWNLOADS = "rule:unplayed_downloads"
    const val YEAR_PREFIX = "rule:year:"
    fun yearKey(year: Int) = "$YEAR_PREFIX$year"

    /** Kinds of YouTube Music's own sections that can be left off Home (1.7.6). */
    const val KIND_VIDEOS = "VIDEOS"
    const val KIND_PODCASTS = "PODCASTS"

    val defaults = listOf(
        HomeSectionConfig(REPLAY, "Replay teaser", style = SectionStyle.HERO),
        HomeSectionConfig(GREETING, TILES_TITLE, style = SectionStyle.HERO),
        HomeSectionConfig(QUICK_PICKS, "Quick picks", style = SectionStyle.LIST),
        HomeSectionConfig(MADE_FOR_YOU, "Made for you"),
        HomeSectionConfig(NEW_RELEASES, NEW_RELEASES_TITLE),
        HomeSectionConfig(RECENT, "Recently played"),
        HomeSectionConfig(GENRES, "Your top genres", style = SectionStyle.GRID),
        HomeSectionConfig(MOODS, "Moods & genres", style = SectionStyle.GRID),
        HomeSectionConfig(LIKED, "Liked songs"),
        HomeSectionConfig(DOWNLOADS, "Downloads", visible = false),
        HomeSectionConfig(SPEED_DIAL, "Speed dial", style = SectionStyle.GRID),
        HomeSectionConfig(ON_REPEAT, "On repeat"),
        HomeSectionConfig(REPEAT_REWIND, "Repeat rewind"),
        HomeSectionConfig(FORGOTTEN, "Forgotten favorites"),
        HomeSectionConfig(SEASONAL, "Holiday music"),
    )

    /** What a fresh install's Home shows (and what Reset goes back to). */
    val initial = listOf(
        HomeSectionConfig(REPLAY, "Replay teaser", style = SectionStyle.HERO),
        HomeSectionConfig(GREETING, TILES_TITLE, style = SectionStyle.HERO),
        HomeSectionConfig(QUICK_PICKS, "Quick picks", style = SectionStyle.LIST),
        HomeSectionConfig(SEASONAL, "Holiday music"),
        HomeSectionConfig(MADE_FOR_YOU, "Made for you"),
        HomeSectionConfig(NEW_RELEASES, NEW_RELEASES_TITLE),
        HomeSectionConfig(ON_REPEAT, "On repeat"),
        HomeSectionConfig("${YT_PREFIX}mixed for you", "Mixed for you"),
    )

    /**
     * Sections added in a later Prism, put into an existing layout once (after Quick picks, or at the top), so people
     * who customized Home before still get them; removing them afterwards sticks.
     */
    fun withNewSections(
        layout: List<HomeSectionConfig>,
        sections: List<HomeSectionConfig> = listOf(HomeSectionConfig(MADE_FOR_YOU, "Made for you"), HomeSectionConfig(NEW_RELEASES, NEW_RELEASES_TITLE)),
        after: String = QUICK_PICKS,
    ): List<HomeSectionConfig> {
        val add = sections.filter { n -> layout.none { it.key == n.key } }
        if (add.isEmpty()) return layout
        val at = layout.indexOfFirst { it.key == after }.let { if (it < 0) layout.indexOfFirst { it.key == QUICK_PICKS } else it }
            .let { if (it < 0) minOf(1, layout.size) else it + 1 }
        return layout.toMutableList().apply { addAll(at, add) }
    }

    /**
     * "Mixes from your music" is part of Made for you now: a layout with both keeps Made for you where it was; one with
     * only the old section gets Made for you in its place (shown or hidden, in its style, as it was).
     */
    fun mergeYourMixes(layout: List<HomeSectionConfig>): List<HomeSectionConfig> {
        val old = layout.indexOfFirst { it.key == YOUR_MIXES }
        if (old < 0) return layout
        if (layout.any { it.key == MADE_FOR_YOU }) return layout.filterNot { it.key == YOUR_MIXES }
        return layout.toMutableList().apply { this[old] = this[old].copy(key = MADE_FOR_YOU, title = "Made for you") }
    }

    /**
     * What Home shows of [layout] on [date]: the holiday section and holiday mixes pinned on their own only in their
     * season (they stay in the layout, where the listener put them, for next year).
     */
    fun inSeason(layout: List<HomeSectionConfig>, date: java.time.LocalDate): List<HomeSectionConfig> = layout.filter { s ->
        when {
            s.key == SEASONAL -> com.prism.music.data.seasonal.HolidayMixes.on(date).isNotEmpty()
            s.key.startsWith(pinKey(HOLIDAY_PIN, "")) ->
                com.prism.music.data.seasonal.HolidayMixes.byId(com.prism.music.data.seasonal.HolidayMixes.PREFIX + s.key.removePrefix(pinKey(HOLIDAY_PIN, "")))?.shownOn(date) == true
            else -> true
        }
    }

    /**
     * The Replay card heads Home (1.7), as on a fresh install: an older layout's Replay teaser moves to the top as the
     * card (Spotlight), shown or hidden as it was; a layout without one gets it.
     */
    fun replayOnTop(layout: List<HomeSectionConfig>): List<HomeSectionConfig> {
        val replay = layout.firstOrNull { it.key == REPLAY } ?: HomeSectionConfig(REPLAY, "Replay teaser")
        return listOf(replay.copy(style = SectionStyle.HERO)) + layout.filterNot { it.key == REPLAY }
    }
}

/** The order lyric sources are tried in on a fresh install. */
val defaultLyricsOrder = listOf(
    LyricsSource.KUGOU, LyricsSource.QQ, LyricsSource.APPLE, LyricsSource.LYRICSPLUS, LyricsSource.BINI, LyricsSource.YTMUSIC,
    LyricsSource.NETEASE, LyricsSource.LRCLIB, LyricsSource.UNISON, LyricsSource.GENIUS,
)

/** "Room for sung songs" at its last step: no limit (#238). */
const val SING_ROOM_UNLIMITED = Int.MAX_VALUE

data class AppSettings(
    val onboarded: Boolean = false,
    // Account
    val cookie: String = "",
    val visitorData: String = "",
    val dataSyncId: String = "",
    /** Which of the Google accounts signed in on this phone's session requests go out as (X-Goog-AuthUser). */
    val authUser: Int = 0,
    /** Discord shows what's playing (Settings → Discord). Off until the listener signs in to Discord there. */
    val discordPresence: Boolean = false,
    val accountName: String = "",
    val accountEmail: String = "",
    val accountAvatar: String = "",
    // Appearance
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val pureBlack: Boolean = false,
    val colorSource: ColorSource = ColorSource.CUSTOM,
    val accent: Int = 0xFF3A86FF.toInt(),
    val lightStyle: LightStyle = LightStyle.SPECTRUM,
    /** The accent as picked (true), or tuned by Prism for dark and light pages (false, as before 1.7.2). */
    val accentExact: Boolean = false,
    /** A different accent on light pages; 0 = the same as [accent]. */
    val accentLight: Int = 0,
    /** The split light's colors: a [com.prism.music.ui.theme.SpectrumPresets] key, or "CUSTOM" for [spectrumStops]. */
    val spectrumPreset: String = "PRISM",
    val spectrumStops: List<Int> = emptyList(),
    /** The split light in hard bands (as the logo's bars) instead of a blend. */
    val spectrumBands: Boolean = false,
    val surfaceTint: SurfaceTint = SurfaceTint.AUTO,
    /** 0 none … 1 Prism's usual hint … 3 strong. */
    val surfaceTintStrength: Float = 1f,
    val surfaceColor: Int = 0xFF3A86FF.toInt(),
    val contrast: ContrastLevel = ContrastLevel.STANDARD,
    val artworkSwatch: ArtworkSwatch = ArtworkSwatch.VIBRANT,
    /** Keep the cover's color lively (a floor on its saturation). */
    val artworkVivid: Boolean = false,
    val artworkChange: ArtworkChange = ArtworkChange.FADE,
    /**
     * Artist, album and playlist pages (and menus) take their accent from their picture instead of the listener's.
     * Off by default (owner, 2026-10-22): the accent picked on the Color page shows on every page.
     */
    val pageColors: Boolean = false,
    val liquidGlass: Boolean = false,
    val glassKind: GlassKind = GlassKind.REGULAR,
    /** Blurred, non-refractive glass. Mutually exclusive with Liquid Glass. */
    // Aura × Spectrum (1.7): floating glass (tab bar, mini player) blurs what's behind it.
    val frostedGlass: Boolean = true,
    /** Taps and swipes on player controls give a little buzz. */
    val haptics: Boolean = true,
    /** Artwork runs edge to edge at the top of the player, dissolving into the backdrop. */
    // Aura × Spectrum: the cover framed, with a glow of its own color.
    val fullBleedArtwork: Boolean = false,
    /** A music video fills the top of the player edge to edge, as YouTube Music's does (#120), instead of a box. */
    val videoFullBleed: Boolean = false,
    /** Immersive mode (the player's ⋯ menu): the cover fills the screen, lyrics play over it, everything else hides. */
    val immersivePlayer: Boolean = false,
    val immersiveArt: ImmersiveArt = ImmersiveArt.ZOOMED,
    val immersiveLyrics: ImmersiveLyrics = ImmersiveLyrics.LOW,
    /** Immersive mode keeps previous / play / next on screen instead of only after a tap. */
    val immersiveControls: Boolean = false,
    /** Shuffle and repeat buttons either side of the player's transport, always (not only with the queue up). */
    val playerShuffleRepeat: Boolean = false,
    /** How much of what's queued is loaded ahead while a song plays ([Preload]). */
    val preload: Preload = Preload.START,
    /** Songs are loaded ahead ([preload]: a start or whole songs) on mobile data too (else only on Wi-Fi). */
    val preloadCellular: Boolean = false,
    /** Animated cover art (motion artwork / video covers) over the sleeve. */
    val canvasEnabled: Boolean = true,
    val canvasOnCellular: Boolean = true,
    val hideVolumeBar: Boolean = true,
    /** "Playing from …" above the artwork in the player. */
    val showPlayingFrom: Boolean = true,
    val lyricsBlur: Boolean = true,
    /** The thin line of split light under the line being sung (lyrics page and immersive). */
    val lyricsProgressLine: Boolean = true,
    /** A glass button beside Sing under the open lyrics: where they come from; a tap picks another source (1.7.9). */
    val lyricsSourceButton: Boolean = false,
    /** Each screen's background (screen name -> "STYLE" or "PHOTO|path"); missing means the default. */
    val backdrops: Map<String, String> = emptyMap(),
    /** How much the theme color washes over custom backgrounds (0 = none). */
    val backdropDim: Float = 0.35f,
    val backdropMotion: Boolean = true,
    // Lyrics
    val lyricsOnPlayer: Boolean = true,
    val lyricsOrder: List<LyricsSource> = defaultLyricsOrder,
    /** Lyric sources the listener turned off: never asked (#51). */
    val lyricsOff: Set<LyricsSource> = emptySet(),
    val preferSynced: Boolean = true,
    /** Pass over sources whose lyrics star out swear words when another has them in full. */
    val skipCensored: Boolean = true,
    /** A translation under each lyric line (machine translation, asked of Google Translate), when the song isn't in it. */
    val lyricsTranslate: Boolean = false,
    /** Language code to translate lyrics into; blank means the phone's language. */
    val lyricsTranslateTo: String = "",
    /** Lyrics saved into music files keep each word's timing, as enhanced LRC (Reddit, 1.7.9.2; off: lines, as before). */
    val lyricsFileWords: Boolean = false,
    /** Lyrics saved into music files carry the translation under each line (Reddit, 1.7.9.2). */
    val lyricsFileTranslation: Boolean = false,
    /** Romanized lyrics (Lyrics → Display). */
    val romanize: RomanizeMode = RomanizeMode.UNDER,
    /** Also scripts of the phone's own language (off: a Korean phone isn't shown Korean in Latin letters). */
    val romanizeAll: Boolean = false,
    /** Sing gets songs ready (downloads them whole) on mobile data too. */
    val singOnCellular: Boolean = false,
    /** Settings → Lyrics → Sing: the playing and next songs are got ready even while Sing is off, so it starts at once. */
    val singAlwaysReady: Boolean = false,
    /** How much room Sing's separated vocals may take, in MB; [SING_ROOM_UNLIMITED] for no limit (#238). */
    val singCacheMb: Int = 1024,
    /** DJ mode's energy curve (picked in the queue, where DJ mode is switched on for the queue playing). */
    val djFlow: com.prism.music.playback.DjFlow = com.prism.music.playback.DjFlow.STEADY,
    /** A notification when an artist the listener starred puts something out (Release Radar). */
    val releaseNotifications: Boolean = true,
    /** A notification on 1 December when the year's Rewind is ready. */
    val rewindNotification: Boolean = true,
    /** Prism Connect: control Prism from a browser on the same Wi-Fi (off until switched on; paired browsers only). */
    val webRemote: Boolean = false,
    /** Prism Connect: other Prism devices on the Wi-Fi can find this one and move what they're playing here. */
    val handoffAvailable: Boolean = false,
    val lyricsScale: Float = 1f,
    /**
     * Lyrics on Android Auto's player (the car's Lyrics button flips this too). Off to start with in a build for cars
     * (`-Pcar`), where the car's own screen is the driver's: the title and artist show as they are.
     */
    val carLyrics: Boolean = !com.prism.music.BuildConfig.CAR_BUILD,
    val carLyricsStyle: CarLyricsStyle = CarLyricsStyle.TEXT,
    /** Also on the phone's own media controls (notification, lock screen) when not in the car. */
    val carLyricsOnPhone: Boolean = false,
    /**
     * The song's title and artist stay as they are and the lyrics show only on the cover (Karaoke and Line by line):
     * the car's small media card reads as usual, while its full player shows the lyric card big.
     */
    val carLyricsKeepTitle: Boolean = false,
    /** Android Auto shows Home and Library as cover tiles under section headings (like Spotify's), not plain lists. */
    val autoGrid: Boolean = true,
    /** Shared links as youtube.com / youtu.be ones instead of music.youtube.com (Reddit R9; albums stay YouTube Music's). */
    val shareYoutubeLinks: Boolean = false,
    // Playback
    val showNerdStats: Boolean = false,
    /** Downloaded songs save their animated cover too. */
    val canvasWithDownloads: Boolean = true,
    /** ...on mobile data as well (off: they're saved once on Wi-Fi). */
    val canvasWithDownloadsCellular: Boolean = false,
    /** The stream's quality (codec and bitrate, or "Downloaded") under the player's scrubber. */
    val showQuality: Boolean = true,
    /** Videos played as songs get a square cover: the thumbnail's black bars trimmed and its middle cut square (#92). */
    val squareVideoCovers: Boolean = true,
    /** Lyrics are looked up on mobile data as each song starts; off, only once the lyrics are opened (#95). */
    val lyricsOnMobileData: Boolean = true,
    /** A "Best part" button by the scrubber in a song's first seconds, to skip to its most replayed stretch. */
    val bestPartButton: Boolean = true,
    /** The Song / Video switch on the player. Off, the player stays audio only. */
    val showVideoSwitch: Boolean = true,
    /** Holding previous / next seeks back / forward this many seconds, again every moment it's held (0 = off). */
    val holdSeekSeconds: Int = 10,
    val audioQuality: AudioQuality = AudioQuality.HIGH,
    val cellularQuality: AudioQuality = AudioQuality.HIGH,
    /** Ask for songs as the signed-in account so Music Premium's 256 kbps streams come (StreamResolver.premiumFormats). */
    val premiumStream: Boolean = false,
    val autoplay: Boolean = true,
    /** Where Autoplay's songs come from: your own music, YouTube Music's radio, or both (offline it's always yours). */
    val autoplaySource: AutoplaySource = AutoplaySource.MIXED,
    /** Home's Replay card look ([com.prism.music.ui.screens.ReplayCardStyle] name). */
    val replayCard: String = "WASH",
    /** Autoplay and radios play songs: music videos swap for their song, and videos with no song version are left out (#39). */
    val radioSongsOnly: Boolean = true,
    /**
     * "Songs, not videos" everywhere (#80): with [radioSongsOnly], a music video in any queue (search, albums, playlists,
     * Liked songs, downloads) plays as its song, the video kept for the Video button; without a song it plays as it is.
     */
    val songsEverywhere: Boolean = false,
    /** The player's right-hand time shows the song's length instead of the time left (#82; tapping it switches). */
    val showTrackLength: Boolean = false,
    /** Playback diagnostics (Settings → About): a log of what the audio did, for chasing stutters ([AudioDiagnostics]). */
    val audioDiagnostics: Boolean = false,
    /** Search opens with the keyboard up (#71). */
    val searchKeyboard: Boolean = false,
    /** Double-tapping the left or right of the player's cover seeks back / ahead this many seconds (#79); 0 = off. */
    val doubleTapSeekSeconds: Int = 10,
    /** Swiping Prism away from Recents stops the music (#45); off, it keeps playing in the background. */
    val stopOnSwipeAway: Boolean = false,
    /**
     * Data saver, on mobile data (#40): smaller pictures, no artist header photos or animated covers, and covers
     * and lyrics only fetched for what's on screen. Wi-Fi is unaffected.
     */
    val dataSaver: Boolean = false,
    /** With a music video showing, the next song plays as its video too (until switched back to the song). */
    val videoContinues: Boolean = true,
    /** Lossless playback: always the best stream, hi-res output, and the lossless add-on when it's on. */
    val lossless: Boolean = true,
    /** The lossless add-on: play matching FLAC/WAV/AIFF files on the phone instead of the stream. */
    val losslessLocal: Boolean = false,
    val normalize: Boolean = true,
    val skipSilence: Boolean = false,
    /** Crossfade: songs fade into each other when one runs into the next (after Spotify's), instead of stopping and starting. */
    val mix: Boolean = false,
    /** How long a crossfade lasts, in seconds, when it isn't [mixAuto]. */
    val mixSeconds: Int = 6,
    /** Crossfade works out the best length for each pair of songs, from how one ends and the next begins. */
    val mixAuto: Boolean = true,
    /** DJ-style: lines up the songs' beats and plays the incoming song at the outgoing one's tempo during the crossfade. */
    val mixBeatMatch: Boolean = false,
    /**
     * An album's songs never cross into each other, wherever they sit side by side (#247; an album playing through
     * already didn't). Off as before: a playlist or shuffle putting two of an album's songs together blends them.
     */
    val mixKeepAlbums: Boolean = false,
    /** Tempo analysis (a 1–4 MB download per song) may use mobile data, not only Wi-Fi. */
    val mixTempoOnCellular: Boolean = false,
    val videoMaxHeight: Int = 1080,
    /**
     * The country YouTube Music is asked as (ISO code, e.g. "US"); blank follows the phone. Where YouTube Music
     * isn't offered, asking as a country where it is brings Home, charts and search back.
     */
    val contentCountry: String = "",
    // Storage & downloads
    val cacheSizeMb: Int = 4096,
    val downloadWifiOnly: Boolean = true,
    val downloadLikes: Boolean = true,
    /**
     * Smart Downloads' picks: the songs you're most likely to play are downloaded ahead. Off on a new install (#339,
     * 1.8.1.1: Home offers it once); listeners who had it on before keep it ([addNewHomeSections]).
     */
    val smartDownloads: Boolean = false,
    /** Space smart downloads may fill, in GB (before 1.6.3; now part of [offlineGb]). */
    val smartDownloadGb: Float = 1f,
    /**
     * Smart Downloads' space, in GB: its picks plus the songs you've streamed (the song cache), which used to be two
     * settings. Phones that had them start with their sum.
     */
    val offlineGb: Float = 5f,
    /** Smart Downloads may download its picks on mobile data too. */
    val smartDownloadsCellular: Boolean = false,
    // Audio effects
    val eq: EqSettings = EqSettings(bassBoost = 32, virtualizer = 6, loudnessGainMb = 39),
    /** Prism Spatial, the software spatializer. */
    val spatial: Boolean = false,
    val spatialAmount: Float = 0.75f,
    // Home
    val homeLayout: List<HomeSectionConfig> = HomeSections.initial,
    val autoAddSections: Boolean = true,
    /** Search's page before you type (1.7.6): its sections, in order. */
    val searchSections: List<SearchSection> = SearchSection.entries.toList(),
    /** Search's chips in the listener's order (#224, 1.7.9.2): SearchFilter names and "PHONE"; the first is where Search opens. Empty: Prism's order. */
    val searchChips: List<String> = emptyList(),
    val homeHeader: HomeHeader = HomeHeader.GREETING,
    val homeHeaderHeight: HomeHeaderHeight = HomeHeaderHeight.MEDIUM,
    /** The picture behind a PICTURE header: a background design's name, or "PHOTO|<file>" (as backgrounds keep theirs). */
    val homeHeaderPicture: String = "AURORA",
    /** Kinds of YouTube Music's sections left off Home ([HomeSections.KIND_VIDEOS], [HomeSections.KIND_PODCASTS]; 1.7.6). */
    val homeHideKinds: Set<String> = emptySet(),
    /** Replay shows listening time as hours and minutes (20h 4m) instead of minutes (1,204 min). */
    val replayHours: Boolean = true,
    /** Home's headline; blank means good morning / afternoon / evening. */
    val greetingText: String = "",
    val greetingShowName: Boolean = true,
    val homeShortcuts: List<HomeShortcut> = listOf(HomeShortcut.LIKED, HomeShortcut.DOWNLOADS, HomeShortcut.REPLAY),
    /** Recently played albums added after the shortcuts. */
    val homeRecentTiles: Int = 3,
    /** Playlists shown as tiles after the shortcuts. */
    val homePlaylists: List<PinnedPlaylist> = emptyList(),
    val homeCustomizeButton: Boolean = true,
    /** Settings, Home screen, Continue line: the Continue line under Home's greeting. */
    val homeContinue: Boolean = true,
    /** Tapping a song in Quick picks starts a radio from it (as on YouTube Music), instead of playing Quick picks in order (#38). */
    val quickPicksRadio: Boolean = true,
    /** Home's Speed dial shows only YouTube's own tiles, without the albums and songs Prism adds from your plays (#131). */
    val speedDialYouTubeOnly: Boolean = false,
    /** The round Customize button at the top of Home (beside Podcasts); separate from the one at the bottom. */
    val homeCustomizeTopButton: Boolean = true,
    /** The Podcasts button at the top of Home. Off, Podcasts opens from a Library tile instead (#28). */
    val homePodcastsButton: Boolean = true,
    // Interface
    val fontChoice: FontChoice = FontChoice.PRISM,
    /** The imported app font's name, and when it (or the lyrics font) was last imported, so the UI picks it up. */
    val appFontName: String = "",
    val fontStamp: Long = 0,
    /** The lyrics' font: "" = the app's, else a [FontChoice] name (CUSTOM = its own imported file, [lyricsFontName]). */
    val lyricsFont: String = "",
    val lyricsFontName: String = "",
    val textScale: Float = 1f,
    val corners: Corners = Corners.ROUND,
    /**
     * Shapes (1.7.7), in one value: AppSettings is at the JVM's limit of a method's arguments (its copy and default
     * constructors), so new groups of settings go in nested classes like this one.
     */
    val shapes: ShapeLook = ShapeLook(),
    /** Weight, letter spacing and a title font (1.7.7). */
    val type: TypeLook = TypeLook(),
    /** 1.7.8's player look and behaviour options. */
    val view: ViewLook = ViewLook(),
    val sound: SoundPrefs = SoundPrefs(),
    val behavior: BehaviorPrefs = BehaviorPrefs(),
    /** Prism TV and the phone's desk mode (1.8.0). */
    val tv: TvPrefs = TvPrefs(),
    val uiDensity: UiDensity = UiDensity.COMFORTABLE,
    val titleSize: TitleSize = TitleSize.LARGE,
    val cardSize: CardSize = CardSize.MEDIUM,
    val reduceMotion: Boolean = false,
    // Navigation
    // Aura × Spectrum: a floating glass capsule.
    val navStyle: NavBarStyle = NavBarStyle.FLOATING,
    val navLabels: NavLabels = NavLabels.ALWAYS,
    val navHideOnScroll: Boolean = true,
    val navTabs: List<NavTab> = listOf(NavTab.HOME, NavTab.LIBRARY, NavTab.SEARCH, NavTab.REPLAY),
    /** Tabs given their own names (1.7.6); the rest keep theirs. */
    val tabNames: Map<NavTab, String> = emptyMap(),
    val navBarSize: NavBarSize = NavBarSize.STANDARD,
    val navBarFill: NavBarFill = NavBarFill.GLASS,
    /** Swiping sideways across a tab's page goes to the tab beside it (1.7.6, off by default). */
    val swipeTabs: Boolean = false,
    /** The mini player and the floating tab bar as one piece of glass (1.7.6). */
    val miniPlayerJoined: Boolean = false,
    /** Local files' sections, shown in this order. */
    val localSections: List<LocalSection> = LocalSection.entries.toList(),
    /** Local files → Songs' order (a name from the page's sort list). */
    val localSort: String = "TITLE",
    val startTab: NavTab = NavTab.HOME,
    val miniPlayerStyle: MiniPlayerStyle = MiniPlayerStyle.CARD,
    val miniSkipButtons: Boolean = true,
    /** The mini player's previous button (off by default: swiping right goes back too). */
    val miniPrevButton: Boolean = false,
    val miniProgress: MiniProgress = MiniProgress.LINE,
    // Player look
    val playerBackground: PlayerBackground = PlayerBackground.MESH,
    /**
     * 0..1: how much of the player's background shows through its darkening (blurred cover and your background); below
     * 0, down to [com.prism.music.ui.player.BACKDROP_SHOW_MIN], it darkens on to black (#309, 1.8.1).
     */
    val playerBackdropShow: Float = 0.35f,
    val playerArtCorners: Int = 20,
    val coverSize: CoverSize = CoverSize.STANDARD,
    // Aura × Spectrum (1.7): a solid play button, ringed in split light.
    val transportStyle: TransportStyle = TransportStyle.BUTTON,
    val scrubberStyle: ScrubberStyle = ScrubberStyle.HAIRLINE,
    val artShrinkOnPause: Boolean = true,
    val showOutputDevice: Boolean = true,
    val lyricsAlign: LyricsAlign = LyricsAlign.START,
    /** Lyrics, your way (1.7.5), in one value since 1.7.7 ([LyricsLookPrefs]); read as `lyricsStyle` etc. below. */
    val lyricsLook: LyricsLookPrefs = LyricsLookPrefs(),
    /** The current-lyric line on the player (#121): where it sits, and whether a long line may take two lines. */
    val lyricsStripAlign: LyricsAlign = LyricsAlign.START,
    val lyricsStripTwoLines: Boolean = false,
    // GitHub requests picked for 1.7.5.
    /** #111: a liked song and its liked music video show once, as the song (Liked songs, Library, the car). */
    val mergeLikedVideos: Boolean = true,
    /** #115: the status bar stays hidden in Prism (a swipe from the top shows it for a moment). */
    val hideStatusBar: Boolean = false,
    /** #116: what the player's volume slider changes, and Prism's own level (0 … 1) when it's Prism's. */
    val volumeControl: VolumeControl = VolumeControl.PHONE,
    val appVolume: Float = 1f,
    // The player, your way (1.7.4). Every default is the player as 1.7 had it.
    val playerLayout: PlayerLayout = PlayerLayout.STANDARD,
    /** Vinyl: the tonearm swings onto the record while it plays. */
    val vinylTonearm: Boolean = true,
    /** The parts under the title, in this order (what's switched off elsewhere still hides). */
    val playerDeck: List<DeckPart> = DeckPart.entries,
    val playerButtons: List<PlayerButton> = PlayerButton.DEFAULT,
    /** The buttons beside play in the notification, on the lock screen and in Android Auto, in order. */
    val sessionButtons: List<SessionButton> = SessionButton.DEFAULT,
    val scrubberColor: ScrubberColor = ScrubberColor.SPECTRUM,
    val playerTitleAlign: PlayerTitleAlign = PlayerTitleAlign.START,
    val playerTitleSize: PlayerTitleSize = PlayerTitleSize.MEDIUM,
    /** A title too long for the line scrolls across (off: it's cut short with "…"). */
    val playerTitleScroll: Boolean = true,
    val playerTimes: PlayerTimes = PlayerTimes.BOTH,
    /**
     * The color mesh behind the player: how fast it drifts (0 still, as before, … 1) and how strong its colors are
     * (0.4 soft … 1.6 rich; 1 as before).
     */
    val meshSpeed: Float = 0f,
    val meshIntensity: Float = 1f,
    /** How soft the blurred cover is (0.5 … 1.5; 1 as before). */
    val playerBlur: Float = 1f,
    /** An animated cover fills the whole player behind everything, not only the cover. */
    val canvasFillsBackground: Boolean = false,
    /** The progress bar's thickness (0.6 thin … 2 thick; 1 as the style draws it) and the knob at the playhead. */
    val scrubberThickness: Float = 1f,
    val scrubberThumb: ScrubberThumb = ScrubberThumb.AUTO,
    /** The volume bar takes the progress bar's style and thickness (Reddit, 1.7.9.1); off, its own height as before. */
    val volumeMatchesProgress: Boolean = false,
    /** The grey handle at the player's top (Reddit, 1.7.9.1); a swipe down closes the player with or without it. */
    val playerHandle: Boolean = true,
    /** The status bar hidden only while the player is open, with its lyrics and queue (Reddit, 1.7.9.1). */
    val hideStatusBarPlayer: Boolean = false,
    /** The dark band behind three-button navigation on the player (#250, 1.7.9.1); off, the player shows through it. */
    val playerNavBand: Boolean = true,
    /**
     * The visualizer (off by default: it's the one part of the player that works on every frame): moves with the music
     * Prism plays (its own sound, so no microphone), only while the player is on screen.
     */
    val visualizer: VisualizerStyle = VisualizerStyle.OFF,
    val visualizerColor: ScrubberColor = ScrubberColor.SPECTRUM,
    // Gestures on the player's cover and on the mini player.
    val coverSwipeLeft: PlayerGesture = PlayerGesture.NEXT,
    val coverSwipeRight: PlayerGesture = PlayerGesture.PREVIOUS,
    val coverSwipeUp: PlayerGesture = PlayerGesture.QUEUE,
    val coverDoubleTap: PlayerGesture = PlayerGesture.SEEK_SIDES,
    val coverLongPress: PlayerGesture = PlayerGesture.NONE,
    val miniSwipeLeft: PlayerGesture = PlayerGesture.NEXT,
    val miniSwipeRight: PlayerGesture = PlayerGesture.PREVIOUS,
    val miniSwipeDown: PlayerGesture = PlayerGesture.STOP,
    /** Swiping the mini player up (#149, 1.7.7): it did nothing before, so opening the player changes no habit. */
    val miniSwipeUp: PlayerGesture = PlayerGesture.OPEN,
    // Library & Replay
    val libraryView: LibraryView = LibraryView.LIST,
    /** Library tabs shown their own way (1.7.6: "PLAYLISTS", "ALBUMS", "ARTISTS"); the rest as [libraryView]. */
    val libraryViews: Map<String, LibraryView> = emptyMap(),
    /** How many covers across Library's grid (1.7.6). */
    val libraryColumns: LibraryColumns = LibraryColumns.AUTO,
    /** Song counts, owners and years under Library's playlists, albums and artists (1.7.6). */
    val librarySubtitles: Boolean = true,
    val libraryStartTab: String = "PLAYLISTS",
    /** Library's tiles that show, in order (the rest are hidden). */
    val libraryTiles: List<LibraryTile> = LibraryTile.initial,
    /** Library → Playlists shows your playlists above the tiles (Liked songs, Downloads…) instead of below them. */
    val libraryPlaylistsFirst: Boolean = false,
    /** Playlist pages open with their picture edge to edge, like an artist page. */
    val playlistHeroCover: Boolean = true,
    /** Artist, album and playlist pages sit on frosted glass made from their picture (Apple Music's look). */
    // Aura × Spectrum (1.7): ambient color from what's playing behind every page.
    val frostedPages: Boolean = true,
    val replayThemeColors: Boolean = false,
    /**
     * Performance mode (1.7.6): Prism drawn as lightly as 1.6 was, for slower phones ([effective]). Every feature stays;
     * only the effects that cost the phone the most each frame are paused, and the listener's own choices come back
     * when it's off.
     */
    val performanceMode: Boolean = false,
    val performanceOffered: Boolean = false,
) {
    val isLoggedIn: Boolean get() = cookie.contains("SAPISID")

    // The lyrics' look, read as before (1.7.7 moved them into [lyricsLook]; see [LyricsLookPrefs]).
    val lyricsStyle: LyricsStyle get() = lyricsLook.style
    val lyricsBlurAmount: Float get() = lyricsLook.blurAmount
    val lyricsSpacing: Float get() = lyricsLook.spacing
    val lyricsOthersAlpha: Float get() = lyricsLook.othersAlpha
    val lyricsSungColor: LyricsColor get() = lyricsLook.sungColor
    val lyricsSungCustom: Int get() = lyricsLook.sungCustom
    val lyricsOtherColor: LyricsColor get() = lyricsLook.otherColor
    val lyricsOtherCustom: Int get() = lyricsLook.otherCustom
    val lyricsWeight: LyricsWeight get() = lyricsLook.weight
    val lyricsBackingSize: Float get() = lyricsLook.backingSize
    val lyricsShadow: Boolean get() = lyricsLook.shadow
    val lyricsUppercase: Boolean get() = lyricsLook.uppercase
    val lyricsAhead: LyricsAhead get() = lyricsLook.ahead
    val lyricsPosition: LyricsPosition get() = lyricsLook.position
    val lyricsExtraSize: Float get() = lyricsLook.extraSize
    val lyricsExtraColor: LyricsColor get() = lyricsLook.extraColor
    val lyricsExtraItalic: Boolean get() = lyricsLook.extraItalic
    val lyricsExtraPlace: LyricsExtraPlace get() = lyricsLook.extraPlace
    val lyricsBackground: LyricsBackground get() = lyricsLook.background
    val immersiveBackground: LyricsBackground get() = lyricsLook.immersiveBackground
    val lyricsBgColor: Int get() = lyricsLook.bgColor
    val lyricsBgColor2: Int get() = lyricsLook.bgColor2
    val lyricsEverywhere: Boolean get() = lyricsLook.everywhere
    val lyricsGapMark: LyricsGapMark get() = lyricsLook.gapMark

    /**
     * What Prism draws with: these settings, or with [performanceMode] the lighter look. Blurred glass, frosted pages
     * and the frost behind every page, moving and illustrated backgrounds (plain pages instead), animated covers, the
     * visualizer, the drifting color mesh, focus blur and glow in the lyrics, the picture at the top of Home, and
     * motion (as Reduce motion). Nothing stored changes.
     */
    fun effective(): AppSettings = if (!performanceMode) this else copy(
        liquidGlass = false,
        frostedGlass = false,
        frostedPages = false,
        // Your own photo stays (owner, 1.8.1): a still picture costs next to nothing; the drawn and moving ones pause.
        backdrops = backdrops.filterValues { it.substringBefore('|') == "PHOTO" },
        backdropMotion = false,
        reduceMotion = true,
        canvasEnabled = false,
        canvasFillsBackground = false,
        visualizer = VisualizerStyle.OFF,
        meshSpeed = 0f,
        playerBackground = if (playerBackground == PlayerBackground.BLUR || playerBackground == PlayerBackground.APPEARANCE) PlayerBackground.MESH else playerBackground,
        lyricsBlur = false,
        lyricsLook = lyricsLook.copy(
            blurAmount = 0f,
            shadow = false,
            halo = false,
            background = if (lyricsBackground == LyricsBackground.BLUR || lyricsBackground == LyricsBackground.CANVAS) LyricsBackground.PLAYER else lyricsBackground,
            immersiveBackground = if (immersiveBackground == LyricsBackground.CANVAS) LyricsBackground.PLAYER else immersiveBackground,
        ),
        homeHeader = if (homeHeader == HomeHeader.PICTURE || homeHeader == HomeHeader.NOW_PLAYING) HomeHeader.GREETING else homeHeader,
        view = view.copy(crossfadeShape = false),
    )

    /** The lyric sources Prism asks, in the listener's order: the ones turned off are left out (#51). */
    val lyricsSources: List<LyricsSource> get() = lyricsOrder.filter { it !in lyricsOff }.ifEmpty { lyricsOrder }
}

class SettingsRepository(private val context: Context, scope: CoroutineScope) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private object K {
        val onboarded = booleanPreferencesKey("onboarded")
        val cookie = stringPreferencesKey("cookie")
        val visitorData = stringPreferencesKey("visitor_data")
        val dataSyncId = stringPreferencesKey("data_sync_id")
        val authUser = intPreferencesKey("auth_user")
        val discordPresence = booleanPreferencesKey("discord_presence")
        val accountName = stringPreferencesKey("account_name")
        val accountEmail = stringPreferencesKey("account_email")
        val accountAvatar = stringPreferencesKey("account_avatar")
        val themeMode = stringPreferencesKey("theme_mode")
        val pureBlack = booleanPreferencesKey("pure_black")
        val colorSource = stringPreferencesKey("color_source")
        val accent = intPreferencesKey("accent")
        val liquidGlass = booleanPreferencesKey("liquid_glass")
        val glassKind = stringPreferencesKey("glass_kind")
        val lightStyle = stringPreferencesKey("light_style")
        val accentExact = booleanPreferencesKey("accent_exact")
        val accentLight = intPreferencesKey("accent_light")
        val spectrumPreset = stringPreferencesKey("spectrum_preset")
        val spectrumStops = stringPreferencesKey("spectrum_stops")
        val spectrumBands = booleanPreferencesKey("spectrum_bands")
        val surfaceTint = stringPreferencesKey("surface_tint")
        val surfaceTintStrength = floatPreferencesKey("surface_tint_strength")
        val surfaceColor = intPreferencesKey("surface_color")
        val contrast = stringPreferencesKey("contrast_level")
        val artworkSwatch = stringPreferencesKey("artwork_swatch")
        val artworkVivid = booleanPreferencesKey("artwork_vivid")
        val artworkChange = stringPreferencesKey("artwork_change")
        // A new key with the new default (your accent): "page_colors" was only ever in unreleased 1.7.2 test builds,
        // where it defaulted to the picture's color, so those settings are left behind on purpose.
        val pageColors = booleanPreferencesKey("page_picture_accent")

        /** Everything Appearance → Color's "Reset colors" puts back. */
        val colors: List<Preferences.Key<*>> by lazy {
            listOf(
                colorSource, accent, lightStyle, accentExact, accentLight, spectrumPreset, spectrumStops, spectrumBands,
                surfaceTint, surfaceTintStrength, surfaceColor, contrast, artworkSwatch, artworkVivid, artworkChange, pageColors, playingInPageColor,
            )
        }
        val frostedGlass = booleanPreferencesKey("frosted_glass")
        val haptics = booleanPreferencesKey("haptics")
        val fullBleedArtwork = booleanPreferencesKey("full_bleed_artwork")
        val videoFullBleed = booleanPreferencesKey("video_full_bleed")
        val immersivePlayer = booleanPreferencesKey("immersive_player")
        val immersiveArt = stringPreferencesKey("immersive_art")
        val immersiveLyrics = stringPreferencesKey("immersive_lyrics")
        val immersiveControls = booleanPreferencesKey("immersive_controls")
        val playerShuffleRepeat = booleanPreferencesKey("player_shuffle_repeat")
        val preload = stringPreferencesKey("preload")
        val preloadCellular = booleanPreferencesKey("preload_cellular")
        val canvasEnabled = booleanPreferencesKey("canvas_enabled")
        val canvasOnCellular = booleanPreferencesKey("canvas_on_cellular")
        val hideVolumeBar = booleanPreferencesKey("hide_volume_bar")
        val showPlayingFrom = booleanPreferencesKey("show_playing_from")
        val spatial = booleanPreferencesKey("soft_spatial")
        val spatialAmount = floatPreferencesKey("soft_spatial_amount")
        val lyricsBlur = booleanPreferencesKey("lyrics_blur")
        val lyricsProgressLine = booleanPreferencesKey("lyrics_progress_line")
        val lyricsSourceButton = booleanPreferencesKey("lyrics_source_button")
        val backdrops = stringPreferencesKey("backdrops")
        val backdropDim = floatPreferencesKey("backdrop_dim")
        val backdropMotion = booleanPreferencesKey("backdrop_motion")
        val lyricsOnPlayer = booleanPreferencesKey("lyrics_on_player")
        val lyricsOrder = stringPreferencesKey("lyrics_order")
        val lyricsOff = stringPreferencesKey("lyrics_off")
        val preferSynced = booleanPreferencesKey("prefer_synced")
        val skipCensored = booleanPreferencesKey("skip_censored")
        val lyricsTranslate = booleanPreferencesKey("lyrics_translate")
        val lyricsTranslateTo = stringPreferencesKey("lyrics_translate_to")
        val lyricsFileWords = booleanPreferencesKey("lyrics_file_words")
        val lyricsFileTranslation = booleanPreferencesKey("lyrics_file_translation")
        val romanize = stringPreferencesKey("romanize_lyrics")
        val romanizeAll = booleanPreferencesKey("romanize_all")
        val singOnCellular = booleanPreferencesKey("sing_cellular")
        val singAlwaysReady = booleanPreferencesKey("sing_always_ready")
        val singCacheMb = intPreferencesKey("sing_cache_mb")
        val djFlow = stringPreferencesKey("dj_flow")
        val releaseNotifications = booleanPreferencesKey("release_notifications")
        val rewindNotification = booleanPreferencesKey("rewind_notification")
        val webRemote = booleanPreferencesKey("web_remote")
        val handoffAvailable = booleanPreferencesKey("handoff_available")
        val lyricsScale = floatPreferencesKey("lyrics_scale")
        val carLyrics = booleanPreferencesKey("car_lyrics")
        val carLyricsStyle = stringPreferencesKey("car_lyrics_style")
        val carLyricsOnPhone = booleanPreferencesKey("car_lyrics_on_phone")
        val carLyricsKeepTitle = booleanPreferencesKey("car_lyrics_keep_title")
        val autoGrid = booleanPreferencesKey("auto_grid")
        val shareYoutubeLinks = booleanPreferencesKey("share_youtube_links")
        val showNerdStats = booleanPreferencesKey("nerd_stats")
        val showQuality = booleanPreferencesKey("show_quality")
        val squareVideoCovers = booleanPreferencesKey("square_video_covers")
        val lyricsOnMobileData = booleanPreferencesKey("lyrics_on_mobile_data")
        val bestPartButton = booleanPreferencesKey("best_part_button")
        val showVideoSwitch = booleanPreferencesKey("show_video_switch")
        val holdSeekSeconds = intPreferencesKey("hold_seek_seconds")
        val canvasWithDownloads = booleanPreferencesKey("canvas_with_downloads")
        val canvasWithDownloadsCellular = booleanPreferencesKey("canvas_with_downloads_cellular")
        val audioQuality = stringPreferencesKey("audio_quality")
        val cellularQuality = stringPreferencesKey("cellular_quality")
        val premiumStream = booleanPreferencesKey("premium_stream")
        val autoplay = booleanPreferencesKey("autoplay")
        val autoplaySource = stringPreferencesKey("autoplay_source")
        val replayCard = stringPreferencesKey("replay_card")
        val radioSongsOnly = booleanPreferencesKey("radio_songs_only")
        val songsEverywhere = booleanPreferencesKey("songs_everywhere")
        val showTrackLength = booleanPreferencesKey("show_track_length")
        val audioDiagnostics = booleanPreferencesKey("audio_diagnostics")
        val searchKeyboard = booleanPreferencesKey("search_keyboard")
        val doubleTapSeekSeconds = intPreferencesKey("double_tap_seek_seconds")
        val stopOnSwipeAway = booleanPreferencesKey("stop_on_swipe_away")
        val dataSaver = booleanPreferencesKey("data_saver")
        val videoContinues = booleanPreferencesKey("video_continues")
        val lossless = booleanPreferencesKey("lossless")
        val losslessLocal = booleanPreferencesKey("lossless_local")
        val normalize = booleanPreferencesKey("normalize")
        val skipSilence = booleanPreferencesKey("skip_silence")
        val mix = booleanPreferencesKey("mix")
        val mixSeconds = intPreferencesKey("mix_seconds")
        val mixAuto = booleanPreferencesKey("mix_auto")
        val mixBeatMatch = booleanPreferencesKey("mix_beat_match")
        val mixKeepAlbums = booleanPreferencesKey("mix_keep_albums")
        val mixTempoOnCellular = booleanPreferencesKey("mix_tempo_cellular")
        val contentCountry = stringPreferencesKey("content_country")
        val videoMaxHeight = intPreferencesKey("video_max_height")
        val cacheSizeMb = intPreferencesKey("cache_size_mb")
        val downloadWifiOnly = booleanPreferencesKey("download_wifi_only")
        val downloadLikes = booleanPreferencesKey("download_likes")
        val smartDownloads = booleanPreferencesKey("smart_downloads")
        val smartDownloadGb = floatPreferencesKey("smart_download_gb")
        val offlineGb = floatPreferencesKey("offline_gb")
        val smartDownloadsCellular = booleanPreferencesKey("smart_downloads_cellular")
        val eq = stringPreferencesKey("eq")
        val homeLayout = stringPreferencesKey("home_layout")
        val autoAddSections = booleanPreferencesKey("auto_add_sections")
        val homeHideKinds = stringPreferencesKey("home_hide_kinds")
        val searchSections = stringPreferencesKey("search_sections")
        val searchChips = stringPreferencesKey("search_chips")
        val homeHeader = stringPreferencesKey("home_header")
        val homeHeaderHeight = stringPreferencesKey("home_header_height")
        val homeHeaderPicture = stringPreferencesKey("home_header_picture")
        val replayHours = booleanPreferencesKey("replay_hours")
        val greetingText = stringPreferencesKey("greeting_text")
        val greetingShowName = booleanPreferencesKey("greeting_show_name")
        val homeShortcuts = stringPreferencesKey("home_shortcuts")
        val homeRecentTiles = intPreferencesKey("home_recent_tiles")
        val homePlaylists = stringPreferencesKey("home_playlists")
        val homeCustomizeButton = booleanPreferencesKey("home_customize_button")
        val homeContinue = booleanPreferencesKey("home_continue")
        val quickPicksRadio = booleanPreferencesKey("quick_picks_radio")
        val speedDialYouTubeOnly = booleanPreferencesKey("speed_dial_youtube_only")
        val homeCustomizeTopButton = booleanPreferencesKey("home_customize_top_button")
        val homePodcastsButton = booleanPreferencesKey("home_podcasts_button")
        val fontChoice = stringPreferencesKey("font_choice")
        val appFontName = stringPreferencesKey("app_font_name")
        val fontStamp = longPreferencesKey("font_stamp")
        val lyricsFont = stringPreferencesKey("lyrics_font")
        val lyricsFontName = stringPreferencesKey("lyrics_font_name")
        val textScale = floatPreferencesKey("text_scale")
        val corners = stringPreferencesKey("corners")
        val cornerScale = floatPreferencesKey("corner_scale")
        val songShape = stringPreferencesKey("cover_shape_songs")
        val albumShape = stringPreferencesKey("cover_shape_albums")
        val artistShape = stringPreferencesKey("cover_shape_artists")
        val playerShape = stringPreferencesKey("cover_shape_player")
        val buttonShape = stringPreferencesKey("button_shape")
        val playOutlined = booleanPreferencesKey("play_outlined")
        val typeClassic = booleanPreferencesKey("type_classic")
        val controlsClassic = booleanPreferencesKey("controls_classic")
        val tabTint = stringPreferencesKey("tab_icon_tint")
        val typeWeight = stringPreferencesKey("type_weight")
        val typeSpacing = stringPreferencesKey("type_spacing")
        val titleFont = stringPreferencesKey("title_font")
        val playerTitleWeight = stringPreferencesKey("player_title_weight")
        val uiDensity = stringPreferencesKey("ui_density")
        val titleSize = stringPreferencesKey("title_size")
        val cardSize = stringPreferencesKey("card_size")
        val reduceMotion = booleanPreferencesKey("reduce_motion")
        val navStyle = stringPreferencesKey("nav_style")
        val navLabels = stringPreferencesKey("nav_labels")
        val navHideOnScroll = booleanPreferencesKey("nav_hide_on_scroll")
        val navTabs = stringPreferencesKey("nav_tabs")
        val tabNames = stringPreferencesKey("tab_names")
        val navBarSize = stringPreferencesKey("nav_bar_size")
        val navBarFill = stringPreferencesKey("nav_bar_fill")
        val swipeTabs = booleanPreferencesKey("swipe_tabs")
        val miniPlayerJoined = booleanPreferencesKey("mini_player_joined")
        val localSections = stringPreferencesKey("local_sections")
        val localSort = stringPreferencesKey("local_sort")
        val startTab = stringPreferencesKey("start_tab")
        val miniPlayerStyle = stringPreferencesKey("mini_player_style")
        val miniSkipButtons = booleanPreferencesKey("mini_skip_buttons")
        val miniPrevButton = booleanPreferencesKey("mini_prev_button")
        val miniProgress = stringPreferencesKey("mini_progress")
        val miniProgressColor = stringPreferencesKey("mini_progress_color")
        val miniFillStrength = floatPreferencesKey("mini_fill_strength")
        val playRing = booleanPreferencesKey("play_ring")
        val miniPlayButton = booleanPreferencesKey("mini_play_button")
        val playingInPageColor = booleanPreferencesKey("playing_page_color")
        val playerBackground = stringPreferencesKey("player_background")
        val playerBackdropShow = floatPreferencesKey("player_backdrop_show")
        val playerArtCorners = intPreferencesKey("player_art_corners")
        val transportStyle = stringPreferencesKey("transport_style")
        val coverSize = stringPreferencesKey("cover_size")
        val scrubberStyle = stringPreferencesKey("scrubber_style")
        val artShrinkOnPause = booleanPreferencesKey("art_shrink_on_pause")
        val showOutputDevice = booleanPreferencesKey("show_output_device")
        val lyricsAlign = stringPreferencesKey("lyrics_align")
        val playerLayout = stringPreferencesKey("player_layout")
        val vinylTonearm = booleanPreferencesKey("vinyl_tonearm")
        val playerDeck = stringPreferencesKey("player_deck")
        val playerButtons = stringPreferencesKey("player_buttons")
        val sessionButtons = stringPreferencesKey("session_buttons")
        val scrubberColor = stringPreferencesKey("scrubber_color")
        val playerTitleAlign = stringPreferencesKey("player_title_align")
        val playerTitleSize = stringPreferencesKey("player_title_size")
        val playerTitleScroll = booleanPreferencesKey("player_title_scroll")
        val playerTimes = stringPreferencesKey("player_times")
        val meshSpeed = floatPreferencesKey("mesh_speed")
        val meshIntensity = floatPreferencesKey("mesh_intensity")
        val playerBlur = floatPreferencesKey("player_blur")
        val canvasFillsBackground = booleanPreferencesKey("canvas_fills_background")
        val scrubberThickness = floatPreferencesKey("scrubber_thickness")
        val scrubberThumb = stringPreferencesKey("scrubber_thumb")
        val volumeMatchesProgress = booleanPreferencesKey("volume_matches_progress")
        val playerHandle = booleanPreferencesKey("player_handle")
        val hideStatusBarPlayer = booleanPreferencesKey("hide_status_bar_player")
        val playerNavBand = booleanPreferencesKey("player_nav_band")
        val visualizer = stringPreferencesKey("visualizer")
        val visualizerColor = stringPreferencesKey("visualizer_color")
        val lyricsStyle = stringPreferencesKey("lyrics_style")
        val lyricsBlurAmount = floatPreferencesKey("lyrics_blur_amount")
        val lyricsSpacing = floatPreferencesKey("lyrics_spacing")
        val lyricsOthersAlpha = floatPreferencesKey("lyrics_others_alpha")
        val lyricsSungColor = stringPreferencesKey("lyrics_sung_color")
        val lyricsSungCustom = intPreferencesKey("lyrics_sung_custom")
        val lyricsOtherColor = stringPreferencesKey("lyrics_other_color")
        val lyricsOtherCustom = intPreferencesKey("lyrics_other_custom")
        val lyricsWeight = stringPreferencesKey("lyrics_weight")
        val lyricsBackingSize = floatPreferencesKey("lyrics_backing_size")
        val lyricsShadow = booleanPreferencesKey("lyrics_shadow")
        val lyricsUppercase = booleanPreferencesKey("lyrics_uppercase")
        val lyricsAhead = stringPreferencesKey("lyrics_ahead")
        val lyricsPosition = stringPreferencesKey("lyrics_position")
        val lyricsExtraSize = floatPreferencesKey("lyrics_extra_size")
        val lyricsExtraColor = stringPreferencesKey("lyrics_extra_color")
        val lyricsExtraItalic = booleanPreferencesKey("lyrics_extra_italic")
        val lyricsExtraPlace = stringPreferencesKey("lyrics_extra_place")
        val lyricsBackground = stringPreferencesKey("lyrics_background")
        val immersiveBackground = stringPreferencesKey("immersive_background")
        val lyricsBgColor = intPreferencesKey("lyrics_bg_color")
        val lyricsBgColor2 = intPreferencesKey("lyrics_bg_color2")
        val lyricsEverywhere = booleanPreferencesKey("lyrics_everywhere")
        val lyricsGapMark = stringPreferencesKey("lyrics_gap_mark")
        val lyricsStripAlign = stringPreferencesKey("lyrics_strip_align")
        val lyricsStripTwoLines = booleanPreferencesKey("lyrics_strip_two_lines")
        // 1.8.0 lyrics look (#253): the parts of [LyricsLookPrefs] a Style used to fix; unset follows the Style.
        val lyricsMotion = stringPreferencesKey("lyrics_motion")
        val lyricsWordByWord = booleanPreferencesKey("lyrics_word_by_word")
        val lyricsCard = booleanPreferencesKey("lyrics_card")
        val lyricsHalo = booleanPreferencesKey("lyrics_halo")
        val lyricsBoldLines = booleanPreferencesKey("lyrics_bold_lines")
        val lyricsParts: List<Pair<Preferences.Key<*>, Char>> by lazy {
            listOf(lyricsMotion to 's', lyricsWordByWord to 'b', lyricsCard to 'b', lyricsHalo to 'b', lyricsBoldLines to 'b')
        }
        val mergeLikedVideos = booleanPreferencesKey("merge_liked_videos")
        val hideStatusBar = booleanPreferencesKey("hide_status_bar")
        val volumeControl = stringPreferencesKey("volume_control")
        val appVolume = floatPreferencesKey("app_volume")

        /** Lyrics, your way (1.7.5): in Reset look, a Look, and the Lyrics page's own reset. */
        val lyricsLook: List<Pair<Preferences.Key<*>, Char>> by lazy {
            listOf(
                lyricsStyle to 's', lyricsBlurAmount to 'f', lyricsSpacing to 'f', lyricsOthersAlpha to 'f', lyricsSungColor to 's',
                lyricsSungCustom to 'i', lyricsOtherColor to 's', lyricsOtherCustom to 'i', lyricsWeight to 's', lyricsBackingSize to 'f',
                lyricsShadow to 'b', lyricsUppercase to 'b', lyricsAhead to 's', lyricsPosition to 's', lyricsExtraSize to 'f',
                lyricsExtraColor to 's', lyricsExtraItalic to 'b', lyricsExtraPlace to 's', lyricsBackground to 's', immersiveBackground to 's',
                lyricsBgColor to 'i', lyricsBgColor2 to 'i', lyricsEverywhere to 'b', lyricsStripAlign to 's', lyricsStripTwoLines to 'b',
                lyricsGapMark to 's',
            ) + lyricsParts
        }
        val coverSwipeLeft = stringPreferencesKey("cover_swipe_left")
        val coverSwipeRight = stringPreferencesKey("cover_swipe_right")
        val coverSwipeUp = stringPreferencesKey("cover_swipe_up")
        val coverDoubleTap = stringPreferencesKey("cover_double_tap")
        val coverLongPress = stringPreferencesKey("cover_long_press")
        val miniSwipeLeft = stringPreferencesKey("mini_swipe_left")
        val miniSwipeRight = stringPreferencesKey("mini_swipe_right")
        val miniSwipeDown = stringPreferencesKey("mini_swipe_down")
        val miniSwipeUp = stringPreferencesKey("mini_swipe_up")
        val libraryView = stringPreferencesKey("library_view")
        val libraryViews = stringPreferencesKey("library_views")
        val libraryColumns = stringPreferencesKey("library_columns")
        val librarySubtitles = booleanPreferencesKey("library_subtitles")
        val libraryStartTab = stringPreferencesKey("library_start_tab")
        val libraryTiles = stringPreferencesKey("library_tiles")
        val libraryPlaylistsFirst = booleanPreferencesKey("library_playlists_first")
        val playlistHeroCover = booleanPreferencesKey("playlist_hero_cover")
        val frostedPages = booleanPreferencesKey("frosted_pages")
        val replayThemeColors = booleanPreferencesKey("replay_theme_colors")
        // 1.7.8: the player's look ([ViewLook]) and Prism's behaviour ([BehaviorPrefs]).
        val panelPlayer = stringPreferencesKey("panel_player")
        val queueHeadings = booleanPreferencesKey("queue_headings")
        val queueDjChip = booleanPreferencesKey("queue_dj_chip")
        val castMark = booleanPreferencesKey("cast_mark")
        val immersiveVideo = booleanPreferencesKey("immersive_video")
        val bleedFit = stringPreferencesKey("bleed_fit")
        val coverFlight = booleanPreferencesKey("cover_flight")
        val videoControlsHide = booleanPreferencesKey("video_controls_hide")
        val lockPortrait = booleanPreferencesKey("lock_portrait")
        val gesturesEverywhere = booleanPreferencesKey("player_gestures_everywhere")
        val pageSlide = booleanPreferencesKey("page_slide")
        val tabBarPosition = stringPreferencesKey("tab_bar_position")
        // 1.8.0 ([TvPrefs]).
        val tvAmbientMinutes = intPreferencesKey("tv_ambient_minutes")
        val deskMode = booleanPreferencesKey("desk_mode")
        val tvLyricsScale = floatPreferencesKey("tv_lyrics_scale")
    val tvWelcomed = booleanPreferencesKey("tv_welcomed")
        val tvHomeLayout = stringPreferencesKey("tv_home_layout")
        val tvBackdropDim = intPreferencesKey("tv_backdrop_dim")
        val pauseOnMute = booleanPreferencesKey("pause_on_mute")
        val pipMode = stringPreferencesKey("pip_mode")
        val panelSwipes = booleanPreferencesKey("panel_swipes")
        val recentShows = stringPreferencesKey("recent_shows")
        val queueLocked = booleanPreferencesKey("queue_locked")
        val scrollShowsControls = booleanPreferencesKey("scroll_shows_controls")
        val playlistGenres = booleanPreferencesKey("playlist_genres")
        val screenFades = booleanPreferencesKey("screen_fades")
        val crossfadeMark = booleanPreferencesKey("crossfade_mark")
        val blendCovers = booleanPreferencesKey("blend_covers")
        val buttonsPill = booleanPreferencesKey("player_buttons_pill")
        // 1.8.1 ([ViewLook.crossfadeShape]).
        val crossfadeShape = booleanPreferencesKey("crossfade_shape")
        // 1.8.1 requests ([ViewLook.downloadsAlbums], [ViewLook.artistLinks], [ViewLook.backdropFade]).
        val downloadsAlbums = booleanPreferencesKey("downloads_albums")
        val artistLinks = booleanPreferencesKey("player_artist_links")
        val backdropFade = booleanPreferencesKey("backdrop_fade")
        /** 1.8.1 (#216, [BehaviorPrefs.explicitContent]). */
        val explicitContent = stringPreferencesKey("explicit_content")
        // 1.8.1 ([ViewLook.replayGraph], [ViewLook.replayStoryFont]; [BehaviorPrefs]' history choices).
        val replayGraph = stringPreferencesKey("replay_card_graph")
        val replayStoryFont = booleanPreferencesKey("replay_story_font")
        val historyPausedUntil = longPreferencesKey("history_paused_until")
        val playCountSeconds = intPreferencesKey("play_count_seconds")
        val playCountOnAccount = booleanPreferencesKey("play_count_account")
        // 1.8.0 player editor ([PlayerParts]): the hidden parts are part of a Look, the menu's choices aren't.
        val playerHiddenParts = stringPreferencesKey("player_hidden_parts")
        val playerMenuHidden = stringPreferencesKey("player_menu_hidden")
        val playerMenuTiles = stringPreferencesKey("player_menu_tiles")
        // 1.7.8 ([SoundPrefs]).
        val crossfadeCurve = stringPreferencesKey("crossfade_curve")
        val normalizeTarget = intPreferencesKey("normalize_target")
        val normalizeAlbums = booleanPreferencesKey("normalize_albums")
        val musicSpeedDefault = floatPreferencesKey("music_speed")
        val musicPitchDefault = intPreferencesKey("music_pitch")
        val skipSilenceEpisodes = booleanPreferencesKey("skip_silence_episodes")
        val sleepFade = intPreferencesKey("sleep_fade")
        val pauseFade = booleanPreferencesKey("pause_fade")
        /** 1.8.1 (#314, [SoundPrefs.ownLossyFiles]). */
        val ownLossyFiles = booleanPreferencesKey("own_lossy_files")
        val performanceMode = booleanPreferencesKey("performance_mode")
        /** The Home card offering Performance mode on a slower phone was answered (either way). */
        val performanceOffered = booleanPreferencesKey("performance_offered")

        /** Everything the "Reset look" button puts back. */
        val look: List<Preferences.Key<*>> by lazy {
            listOf(
                fontChoice, textScale, corners, uiDensity, titleSize, cardSize, reduceMotion, navStyle, navLabels, navHideOnScroll,
                navTabs, startTab, miniPlayerStyle, miniSkipButtons, miniPrevButton, miniPlayButton, miniProgress, miniProgressColor, miniFillStrength, playRing, playingInPageColor, playerBackground, playerBackdropShow, playerArtCorners, transportStyle,
                coverSize, scrubberStyle, artShrinkOnPause, showOutputDevice, lyricsAlign, libraryView, playlistHeroCover, frostedPages, replayThemeColors,
                immersiveArt, immersiveLyrics, immersiveControls, playerShuffleRepeat, lightStyle,
                playerLayout, vinylTonearm, playerDeck, playerButtons, scrubberColor, playerTitleAlign, playerTitleSize, playerTitleScroll,
                playerTimes, meshSpeed, meshIntensity, playerBlur, canvasFillsBackground,
                scrubberThickness, scrubberThumb, volumeMatchesProgress, playerHandle, playerNavBand, visualizer, visualizerColor,
                lyricsBlur, lyricsScale, lyricsProgressLine, lyricsSourceButton, hideStatusBar, hideStatusBarPlayer,
                tabNames, navBarSize, navBarFill, miniPlayerJoined, libraryViews, libraryColumns, librarySubtitles,
                homeHeader, homeHeaderHeight,
                cornerScale, songShape, albumShape, artistShape, playerShape, buttonShape, playOutlined,
                typeWeight, typeSpacing, titleFont, tabTint, playerTitleWeight, typeClassic, controlsClassic,
                panelPlayer, queueHeadings, queueDjChip, castMark, immersiveVideo, bleedFit, coverFlight, videoControlsHide,
                crossfadeMark, blendCovers, buttonsPill, playerHiddenParts, crossfadeShape,
                downloadsAlbums, artistLinks, backdropFade,
                crossfadeMark, blendCovers, buttonsPill, playerHiddenParts, crossfadeShape, replayGraph, replayStoryFont,
            ) + lyricsLook.map { it.first }
        }

        /**
         * What a Look holds (1.7.3): every key above and the colors, plus theme, glass, backgrounds and the lyrics' look,
         * each with the kind of value it stores (b, i, f, s), so a Look shared by someone else can only ever set these, and
         * only with the right kind of value. `LookTest` fails when a setting is in neither this list nor [notLook].
         */
        val lookSpec: List<Pair<Preferences.Key<*>, Char>> by lazy {
            listOf(
                themeMode to 's', pureBlack to 'b',
                colorSource to 's', accent to 'i', lightStyle to 's', accentExact to 'b', accentLight to 'i', spectrumPreset to 's',
                spectrumStops to 's', spectrumBands to 'b', surfaceTint to 's', surfaceTintStrength to 'f', surfaceColor to 'i', contrast to 's',
                artworkSwatch to 's', artworkVivid to 'b', artworkChange to 's', pageColors to 'b',
                liquidGlass to 'b', glassKind to 's', frostedGlass to 'b', frostedPages to 'b', backdrops to 's', backdropDim to 'f',
                backdropMotion to 'b', reduceMotion to 'b',
                fontChoice to 's', appFontName to 's', lyricsFont to 's', textScale to 'f', corners to 's', uiDensity to 's', titleSize to 's', cardSize to 's',
                navStyle to 's', navLabels to 's', navHideOnScroll to 'b', navTabs to 's', startTab to 's',
                tabNames to 's', navBarSize to 's', navBarFill to 's', miniPlayerJoined to 'b',
                miniPlayerStyle to 's', miniSkipButtons to 'b', miniPrevButton to 'b', miniPlayButton to 'b', miniProgress to 's', miniProgressColor to 's', miniFillStrength to 'f', playRing to 'b', playingInPageColor to 'b',
                playerBackground to 's', playerBackdropShow to 'f', playerArtCorners to 'i', transportStyle to 's', coverSize to 's',
                scrubberStyle to 's', artShrinkOnPause to 'b', showOutputDevice to 'b', fullBleedArtwork to 'b', videoFullBleed to 'b', hideVolumeBar to 'b',
                showPlayingFrom to 'b', playerShuffleRepeat to 'b', immersiveArt to 's', immersiveLyrics to 's', immersiveControls to 'b',
                playerLayout to 's', vinylTonearm to 'b', playerDeck to 's', playerButtons to 's', scrubberColor to 's', playerTitleAlign to 's',
                playerTitleSize to 's', playerTitleScroll to 'b', playerTimes to 's', meshSpeed to 'f', meshIntensity to 'f', playerBlur to 'f',
                canvasFillsBackground to 'b', scrubberThickness to 'f', scrubberThumb to 's', visualizer to 's', visualizerColor to 's',
                volumeMatchesProgress to 'b', playerHandle to 'b', hideStatusBarPlayer to 'b', playerNavBand to 'b',
                lyricsAlign to 's', lyricsBlur to 'b', lyricsProgressLine to 'b', lyricsSourceButton to 'b', lyricsScale to 'f',
                libraryView to 's', libraryViews to 's', libraryColumns to 's', librarySubtitles to 'b', playlistHeroCover to 'b',
                homeHeader to 's', homeHeaderHeight to 's', replayThemeColors to 'b', replayCard to 's', hideStatusBar to 'b',
                cornerScale to 'f', songShape to 's', albumShape to 's', artistShape to 's', playerShape to 's', buttonShape to 's', playOutlined to 'b',
                typeWeight to 's', typeSpacing to 's', titleFont to 's', tabTint to 's', playerTitleWeight to 's', typeClassic to 'b', controlsClassic to 'b',
                panelPlayer to 's', queueHeadings to 'b', queueDjChip to 'b', castMark to 'b', immersiveVideo to 'b', bleedFit to 's', coverFlight to 'b',
                videoControlsHide to 'b', crossfadeMark to 'b', blendCovers to 'b', buttonsPill to 'b', playerHiddenParts to 's',
                crossfadeShape to 'b',
                downloadsAlbums to 'b', artistLinks to 'b', backdropFade to 'b',
                crossfadeShape to 'b', replayGraph to 's', replayStoryFont to 'b',
            ) + lyricsLook
        }

        /** Settings that aren't part of a Look: what you listen to and how, your account, Home's contents. */
        val notLook: Set<String> by lazy {
            setOf(
                "performance_mode", "performance_offered", "share_youtube_links", "mix_keep_albums",
                "player_menu_hidden", "player_menu_tiles",
                "lyrics_file_words", "lyrics_file_translation",
                "merge_liked_videos", "volume_control", "app_volume", "speed_dial_youtube_only", "swipe_tabs",
                "onboarded", "cookie", "visitor_data", "data_sync_id", "auth_user", "discord_presence", "account_name", "account_email",
                "account_avatar", "haptics", "immersive_player", "preload", "preload_cellular", "canvas_enabled", "canvas_on_cellular",
                "soft_spatial", "soft_spatial_amount", "lyrics_on_player", "lyrics_order", "lyrics_off", "prefer_synced", "skip_censored",
                "lyrics_translate", "lyrics_translate_to", "romanize_lyrics", "romanize_all", "sing_cellular", "sing_always_ready",
                "sing_cache_mb", "dj_flow", "release_notifications", "rewind_notification", "web_remote", "handoff_available",
                "car_lyrics", "car_lyrics_style", "car_lyrics_on_phone", "car_lyrics_keep_title", "auto_grid", "nerd_stats",
                "show_quality", "square_video_covers", "lyrics_on_mobile_data", "best_part_button", "show_video_switch",
                "hold_seek_seconds", "canvas_with_downloads", "canvas_with_downloads_cellular", "audio_quality", "cellular_quality",
                "premium_stream", "autoplay", "autoplay_source", "radio_songs_only", "songs_everywhere", "show_track_length",
                "audio_diagnostics", "search_keyboard", "double_tap_seek_seconds", "stop_on_swipe_away", "data_saver",
                "video_continues", "lossless", "lossless_local", "normalize", "skip_silence", "mix", "mix_seconds", "mix_auto",
                "mix_beat_match", "mix_tempo_cellular", "content_country", "video_max_height", "cache_size_mb", "download_wifi_only",
                "download_likes", "smart_downloads", "smart_download_gb", "offline_gb", "smart_downloads_cellular", "eq",
                "home_layout", "auto_add_sections", "home_hide_kinds", "home_header_picture", "search_sections", "search_chips", "replay_hours", "greeting_text", "greeting_show_name", "home_shortcuts",
                "home_recent_tiles", "home_playlists", "home_customize_button", "home_continue", "quick_picks_radio",
                "home_customize_top_button", "home_podcasts_button", "font_stamp", "lyrics_font_name", "local_sections", "local_sort",
                "library_start_tab", "library_tiles", "library_playlists_first",
                // 1.7.4: what buttons and gestures do is how you use Prism, not how it looks.
                "session_buttons", "cover_swipe_left", "cover_swipe_right", "cover_swipe_up", "cover_double_tap", "cover_long_press",
                "mini_swipe_left", "mini_swipe_right", "mini_swipe_down", "mini_swipe_up",
                // 1.7.8 ([BehaviorPrefs]).
                "lock_portrait", "pause_on_mute", "pip_mode", "panel_swipes", "recent_shows", "queue_locked", "scroll_shows_controls", "playlist_genres", "screen_fades", "player_gestures_everywhere", "page_slide", "tab_bar_position",
                // 1.8.0 ([TvPrefs]): how Prism behaves on a TV and in desk mode.
                "tv_ambient_minutes", "desk_mode", "tv_lyrics_scale", "tv_welcomed", "tv_home_layout", "tv_backdrop_dim",
                // 1.7.8 ([SoundPrefs]).
                "crossfade_curve", "normalize_target", "normalize_albums", "music_speed", "music_pitch",
                "skip_silence_episodes", "sleep_fade", "pause_fade",
                // 1.8.1 (#314): your own song files in place of the stream.
                "own_lossy_files",
                // 1.8.1 (#216): what plays and shows, not a look.
                "explicit_content",
                // 1.8.1 (#296, #263): what Prism keeps of your listening.
                "history_paused_until", "play_count_seconds", "play_count_account",
            )
        }
    }

    /** Every setting's stored name (for `LookTest`: each is either in a Look or listed as not). */
    val allKeyNames: Set<String> by lazy {
        K::class.java.declaredFields.filter { Preferences.Key::class.java.isAssignableFrom(it.type) }
            .mapNotNull { f -> f.isAccessible = true; (f.get(K) as? Preferences.Key<*>)?.name }.toSet()
    }

    /** Settings listed as not part of a Look ([K.notLook]). */
    val notLookNames: Set<String> get() = K.notLook

    /** What Reset look and Reset colors put back (every one of them is in a Look too). */
    val resetNames: Set<String> get() = (K.look + K.colors).map { it.name }.toSet()

    /** What a Look holds, by stored name, with each one's kind of value. */
    val lookKinds: Map<String, Char> by lazy { K.lookSpec.associate { (k, t) -> k.name to t } }

    private val lookKeysByName: Map<String, Preferences.Key<*>> by lazy { K.lookSpec.associate { (k, _) -> k.name to k } }

    /** The look settings that are set in [p], as tagged text ("b:1", "f:0.35", "s:SHARP"…); missing ones are at their defaults. */
    private fun lookOf(p: Preferences): Map<String, String> =
        K.lookSpec.mapNotNull { (k, _) -> p[k]?.let { v -> LookValue.tag(v)?.let { k.name to it } } }.toMap()

    /** Puts a Look's [values] in place of every look setting in [p] (those it doesn't set go back to their defaults). */
    @Suppress("UNCHECKED_CAST")
    private fun putLook(p: MutablePreferences, values: Map<String, String>) {
        K.lookSpec.forEach { (k, _) -> p.remove(k as Preferences.Key<Any>) }
        values.forEach { (name, tagged) ->
            val key = lookKeysByName[name] ?: return@forEach
            val v = LookValue.untag(tagged, lookKinds[name] ?: return@forEach) ?: return@forEach
            p[key as Preferences.Key<Any>] = v
        }
    }

    private inline fun <reified E : Enum<E>> Preferences.enum(key: Preferences.Key<String>, def: E): E =
        this[key]?.let { v -> enumValues<E>().firstOrNull { it.name == v } } ?: def

    /** A comma-separated list of enum names (unknown ones skipped); [def] when unset or nothing in it is known. */
    private inline fun <reified E : Enum<E>> Preferences.enumList(key: Preferences.Key<String>, def: List<E>): List<E> =
        this[key]?.split(',')?.mapNotNull { n -> enumValues<E>().firstOrNull { it.name == n } }?.distinct()?.ifEmpty { null } ?: def

    private fun read(p: Preferences): AppSettings {
        val d = AppSettings()
        return AppSettings(
            onboarded = p[K.onboarded] ?: d.onboarded,
            cookie = p[K.cookie] ?: "",
            visitorData = p[K.visitorData] ?: "",
            dataSyncId = p[K.dataSyncId] ?: "",
            authUser = p[K.authUser] ?: 0,
            discordPresence = p[K.discordPresence] ?: d.discordPresence,
            accountName = p[K.accountName] ?: "",
            accountEmail = p[K.accountEmail] ?: "",
            accountAvatar = p[K.accountAvatar] ?: "",
            themeMode = p.enum(K.themeMode, d.themeMode),
            pureBlack = p[K.pureBlack] ?: d.pureBlack,
            colorSource = p.enum(K.colorSource, d.colorSource),
            accent = p[K.accent] ?: d.accent,
            liquidGlass = p[K.liquidGlass] ?: d.liquidGlass,
            glassKind = p.enum(K.glassKind, d.glassKind),
            lightStyle = p.enum(K.lightStyle, d.lightStyle),
            accentExact = p[K.accentExact] ?: d.accentExact,
            accentLight = p[K.accentLight] ?: d.accentLight,
            spectrumPreset = p[K.spectrumPreset] ?: d.spectrumPreset,
            spectrumStops = p[K.spectrumStops]?.split(',')?.mapNotNull { it.trim().toLongOrNull(16)?.toInt() } ?: d.spectrumStops,
            spectrumBands = p[K.spectrumBands] ?: d.spectrumBands,
            surfaceTint = p.enum(K.surfaceTint, d.surfaceTint),
            surfaceTintStrength = p[K.surfaceTintStrength] ?: d.surfaceTintStrength,
            surfaceColor = p[K.surfaceColor] ?: d.surfaceColor,
            contrast = p.enum(K.contrast, d.contrast),
            artworkSwatch = p.enum(K.artworkSwatch, d.artworkSwatch),
            artworkVivid = p[K.artworkVivid] ?: d.artworkVivid,
            artworkChange = p.enum(K.artworkChange, d.artworkChange),
            pageColors = p[K.pageColors] ?: d.pageColors,
            frostedGlass = p[K.frostedGlass] ?: d.frostedGlass,
            haptics = p[K.haptics] ?: d.haptics,
            // Two switches again (1.8.1, owner): 1.8.0 made the cover and the video one, so the video's own button turned
            // the cover's edge-to-edge look on and off too. Each key is its own setting, as before 1.8.0.
            fullBleedArtwork = p[K.fullBleedArtwork] ?: d.fullBleedArtwork,
            videoFullBleed = p[K.videoFullBleed] ?: d.videoFullBleed,
            immersivePlayer = p[K.immersivePlayer] ?: d.immersivePlayer,
            immersiveArt = p.enum(K.immersiveArt, d.immersiveArt),
            immersiveLyrics = p.enum(K.immersiveLyrics, d.immersiveLyrics),
            immersiveControls = p[K.immersiveControls] ?: d.immersiveControls,
            playerShuffleRepeat = p[K.playerShuffleRepeat] ?: d.playerShuffleRepeat,
            preload = p.enum(K.preload, d.preload),
            preloadCellular = p[K.preloadCellular] ?: d.preloadCellular,
            canvasEnabled = p[K.canvasEnabled] ?: d.canvasEnabled,
            canvasOnCellular = p[K.canvasOnCellular] ?: d.canvasOnCellular,
            hideVolumeBar = p[K.hideVolumeBar] ?: d.hideVolumeBar,
            showPlayingFrom = p[K.showPlayingFrom] ?: d.showPlayingFrom,
            spatial = p[K.spatial] ?: d.spatial,
            spatialAmount = p[K.spatialAmount] ?: d.spatialAmount,
            lyricsBlur = p[K.lyricsBlur] ?: d.lyricsBlur,
            lyricsProgressLine = p[K.lyricsProgressLine] ?: d.lyricsProgressLine,
            lyricsSourceButton = p[K.lyricsSourceButton] ?: d.lyricsSourceButton,
            backdrops = p[K.backdrops]?.let { runCatching { json.decodeFromString<Map<String, String>>(it) }.getOrNull() } ?: d.backdrops,
            backdropDim = p[K.backdropDim] ?: d.backdropDim,
            backdropMotion = p[K.backdropMotion] ?: d.backdropMotion,
            lyricsOnPlayer = p[K.lyricsOnPlayer] ?: d.lyricsOnPlayer,
            lyricsOrder = p[K.lyricsOrder]?.split(",")?.mapNotNull { n ->
                LyricsSource.entries.firstOrNull { it.name == n }
            }?.let { list ->
                // Sources added in an update slot in at their default position.
                val merged = list.toMutableList()
                LyricsSource.entries.forEachIndexed { i, src -> if (src !in merged) merged.add(i.coerceAtMost(merged.size), src) }
                merged
            } ?: d.lyricsOrder,
            lyricsOff = p[K.lyricsOff]?.split(",")?.mapNotNull { n -> LyricsSource.entries.firstOrNull { it.name == n } }?.toSet() ?: d.lyricsOff,
            preferSynced = p[K.preferSynced] ?: d.preferSynced,
            skipCensored = p[K.skipCensored] ?: d.skipCensored,
            lyricsTranslate = p[K.lyricsTranslate] ?: d.lyricsTranslate,
            lyricsTranslateTo = p[K.lyricsTranslateTo] ?: d.lyricsTranslateTo,
            lyricsFileWords = p[K.lyricsFileWords] ?: d.lyricsFileWords,
            lyricsFileTranslation = p[K.lyricsFileTranslation] ?: d.lyricsFileTranslation,
            romanize = p.enum(K.romanize, d.romanize),
            romanizeAll = p[K.romanizeAll] ?: d.romanizeAll,
            singOnCellular = p[K.singOnCellular] ?: d.singOnCellular,
            singAlwaysReady = p[K.singAlwaysReady] ?: d.singAlwaysReady,
            singCacheMb = p[K.singCacheMb] ?: d.singCacheMb,
            djFlow = p.enum(K.djFlow, d.djFlow),
            releaseNotifications = p[K.releaseNotifications] ?: d.releaseNotifications,
            rewindNotification = p[K.rewindNotification] ?: d.rewindNotification,
            webRemote = p[K.webRemote] ?: d.webRemote,
            handoffAvailable = p[K.handoffAvailable] ?: d.handoffAvailable,
            lyricsScale = p[K.lyricsScale] ?: d.lyricsScale,
            carLyrics = p[K.carLyrics] ?: d.carLyrics,
            carLyricsStyle = p.enum(K.carLyricsStyle, d.carLyricsStyle),
            carLyricsOnPhone = p[K.carLyricsOnPhone] ?: d.carLyricsOnPhone,
            carLyricsKeepTitle = p[K.carLyricsKeepTitle] ?: d.carLyricsKeepTitle,
            autoGrid = p[K.autoGrid] ?: d.autoGrid,
            shareYoutubeLinks = p[K.shareYoutubeLinks] ?: d.shareYoutubeLinks,
            showNerdStats = p[K.showNerdStats] ?: d.showNerdStats,
            showQuality = p[K.showQuality] ?: d.showQuality,
            squareVideoCovers = p[K.squareVideoCovers] ?: d.squareVideoCovers,
            lyricsOnMobileData = p[K.lyricsOnMobileData] ?: d.lyricsOnMobileData,
            bestPartButton = p[K.bestPartButton] ?: d.bestPartButton,
            showVideoSwitch = p[K.showVideoSwitch] ?: d.showVideoSwitch,
            holdSeekSeconds = p[K.holdSeekSeconds] ?: d.holdSeekSeconds,
            canvasWithDownloads = p[K.canvasWithDownloads] ?: d.canvasWithDownloads,
            canvasWithDownloadsCellular = p[K.canvasWithDownloadsCellular] ?: d.canvasWithDownloadsCellular,
            audioQuality = p.enum(K.audioQuality, d.audioQuality),
            cellularQuality = p.enum(K.cellularQuality, d.cellularQuality),
            premiumStream = p[K.premiumStream] ?: d.premiumStream,
            autoplay = p[K.autoplay] ?: d.autoplay,
            autoplaySource = p[K.autoplaySource]?.let { n -> AutoplaySource.entries.firstOrNull { it.name == n } } ?: d.autoplaySource,
            replayCard = p[K.replayCard] ?: d.replayCard,
            radioSongsOnly = p[K.radioSongsOnly] ?: d.radioSongsOnly,
            songsEverywhere = p[K.songsEverywhere] ?: d.songsEverywhere,
            showTrackLength = p[K.showTrackLength] ?: d.showTrackLength,
            audioDiagnostics = p[K.audioDiagnostics] ?: d.audioDiagnostics,
            searchKeyboard = p[K.searchKeyboard] ?: d.searchKeyboard,
            doubleTapSeekSeconds = p[K.doubleTapSeekSeconds] ?: d.doubleTapSeekSeconds,
            stopOnSwipeAway = p[K.stopOnSwipeAway] ?: d.stopOnSwipeAway,
            dataSaver = p[K.dataSaver] ?: d.dataSaver,
            videoContinues = p[K.videoContinues] ?: d.videoContinues,
            lossless = p[K.lossless] ?: d.lossless,
            losslessLocal = p[K.losslessLocal] ?: d.losslessLocal,
            normalize = p[K.normalize] ?: d.normalize,
            mix = p[K.mix] ?: d.mix,
            mixSeconds = (p[K.mixSeconds] ?: d.mixSeconds).coerceIn(1, 12),
            mixAuto = p[K.mixAuto] ?: d.mixAuto,
            mixBeatMatch = p[K.mixBeatMatch] ?: d.mixBeatMatch,
            mixKeepAlbums = p[K.mixKeepAlbums] ?: d.mixKeepAlbums,
            mixTempoOnCellular = p[K.mixTempoOnCellular] ?: d.mixTempoOnCellular,
            contentCountry = p[K.contentCountry] ?: d.contentCountry,
            skipSilence = p[K.skipSilence] ?: d.skipSilence,
            videoMaxHeight = p[K.videoMaxHeight] ?: d.videoMaxHeight,
            cacheSizeMb = p[K.cacheSizeMb] ?: d.cacheSizeMb,
            downloadWifiOnly = p[K.downloadWifiOnly] ?: d.downloadWifiOnly,
            downloadLikes = p[K.downloadLikes] ?: d.downloadLikes,
            smartDownloads = p[K.smartDownloads] ?: d.smartDownloads,
            smartDownloadGb = p[K.smartDownloadGb] ?: d.smartDownloadGb,
            offlineGb = p[K.offlineGb] ?: run {
                // The old smart downloads space and song cache add up to the new one (an unlimited cache counts as 20 GB).
                val cacheMb = p[K.cacheSizeMb] ?: d.cacheSizeMb
                (p[K.smartDownloadGb] ?: d.smartDownloadGb) + if (cacheMb <= 0) 20f else cacheMb / 1024f
            },
            smartDownloadsCellular = p[K.smartDownloadsCellular] ?: d.smartDownloadsCellular,
            eq = p[K.eq]?.let { runCatching { json.decodeFromString<EqSettings>(it) }.getOrNull() } ?: d.eq,
            homeLayout = p[K.homeLayout]?.let {
                runCatching { json.decodeFromString<List<HomeSectionConfig>>(it) }.getOrNull()
                    // The tiles section was once called "Greeting & shortcuts"; the greeting is the top bar's.
                    ?.map { s -> if (s.key == HomeSections.GREETING) s.copy(title = HomeSections.TILES_TITLE) else s }
                    // Release Radar's section was "New releases", the same name as YouTube's own shelf beside it.
                    ?.map { s -> if (s.key == HomeSections.NEW_RELEASES && s.title == "New releases") s.copy(title = HomeSections.NEW_RELEASES_TITLE) else s }
            }.let { it ?: d.homeLayout }.map(HomeSections::inYourLanguage),
            autoAddSections = p[K.autoAddSections] ?: d.autoAddSections,
            homeHideKinds = p[K.homeHideKinds]?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: d.homeHideKinds,
            searchSections = p[K.searchSections]?.let { SearchSection.parse(it) } ?: d.searchSections,
            searchChips = p[K.searchChips]?.split(',')?.filter { it.isNotBlank() } ?: d.searchChips,
            homeHeader = p.enum(K.homeHeader, d.homeHeader),
            homeHeaderHeight = p.enum(K.homeHeaderHeight, d.homeHeaderHeight),
            homeHeaderPicture = p[K.homeHeaderPicture] ?: d.homeHeaderPicture,
            replayHours = p[K.replayHours] ?: d.replayHours,
            greetingText = p[K.greetingText] ?: d.greetingText,
            greetingShowName = p[K.greetingShowName] ?: d.greetingShowName,
            homeShortcuts = p[K.homeShortcuts]?.let { v ->
                v.split(",").mapNotNull { n -> HomeShortcut.entries.firstOrNull { it.name == n } }
            } ?: d.homeShortcuts,
            homeRecentTiles = p[K.homeRecentTiles] ?: d.homeRecentTiles,
            homePlaylists = p[K.homePlaylists]?.let { runCatching { json.decodeFromString<List<PinnedPlaylist>>(it) }.getOrNull() } ?: d.homePlaylists,
            homeCustomizeButton = p[K.homeCustomizeButton] ?: d.homeCustomizeButton,
            homeContinue = p[K.homeContinue] ?: d.homeContinue,
            quickPicksRadio = p[K.quickPicksRadio] ?: d.quickPicksRadio,
            speedDialYouTubeOnly = p[K.speedDialYouTubeOnly] ?: d.speedDialYouTubeOnly,
            homeCustomizeTopButton = p[K.homeCustomizeTopButton] ?: d.homeCustomizeTopButton,
            homePodcastsButton = p[K.homePodcastsButton] ?: d.homePodcastsButton,
            fontChoice = p[K.fontChoice]?.let { n -> FontChoice.entries.firstOrNull { it.name == FontChoice.upgrade(n) } } ?: d.fontChoice,
            appFontName = p[K.appFontName] ?: d.appFontName,
            fontStamp = p[K.fontStamp] ?: d.fontStamp,
            lyricsFont = p[K.lyricsFont]?.let(FontChoice::upgrade) ?: d.lyricsFont,
            lyricsFontName = p[K.lyricsFontName] ?: d.lyricsFontName,
            textScale = p[K.textScale] ?: d.textScale,
            corners = p.enum(K.corners, d.corners),
            shapes = ShapeLook(
                cornerScale = p[K.cornerScale],
                songs = p.enum(K.songShape, d.shapes.songs),
                albums = p.enum(K.albumShape, d.shapes.albums),
                artists = p.enum(K.artistShape, d.shapes.artists),
                player = p.enum(K.playerShape, d.shapes.player),
                button = p.enum(K.buttonShape, d.shapes.button),
                playOutlined = p[K.playOutlined] ?: d.shapes.playOutlined,
                classicControls = p[K.controlsClassic] ?: d.shapes.classicControls,
                tabTint = p.enum(K.tabTint, d.shapes.tabTint),
            ),
            type = TypeLook(
                weight = p.enum(K.typeWeight, d.type.weight),
                spacing = p.enum(K.typeSpacing, d.type.spacing),
                titleFont = p[K.titleFont]?.let { n -> FontChoice.entries.firstOrNull { it.name == FontChoice.upgrade(n) } },
                playerTitleWeight = p.enum(K.playerTitleWeight, d.type.playerTitleWeight),
                classic = p[K.typeClassic] ?: d.type.classic,
            ),
            view = ViewLook(
                panelPlayer = p.enum(K.panelPlayer, d.view.panelPlayer),
                queueHeadings = p[K.queueHeadings] ?: d.view.queueHeadings,
                queueDjChip = p[K.queueDjChip] ?: d.view.queueDjChip,
                castMark = p[K.castMark] ?: d.view.castMark,
                immersiveVideo = p[K.immersiveVideo] ?: d.view.immersiveVideo,
                bleedFit = p.enum(K.bleedFit, d.view.bleedFit),
                coverFlight = p[K.coverFlight] ?: d.view.coverFlight,
                videoControlsHide = p[K.videoControlsHide] ?: d.view.videoControlsHide,
                crossfadeMark = p[K.crossfadeMark] ?: d.view.crossfadeMark,
                blendCovers = p[K.blendCovers] ?: d.view.blendCovers,
                buttonsPill = p[K.buttonsPill] ?: d.view.buttonsPill,
                miniProgressColor = p.enum(K.miniProgressColor, d.view.miniProgressColor),
                miniFillStrength = (p[K.miniFillStrength] ?: d.view.miniFillStrength).coerceIn(0.1f, 0.9f),
                playRing = p[K.playRing] ?: d.view.playRing,
                miniPlayButton = p[K.miniPlayButton] ?: d.view.miniPlayButton,
                playingInPageColor = p[K.playingInPageColor] ?: d.view.playingInPageColor,
                parts = readParts(p),
                crossfadeShape = p[K.crossfadeShape] ?: d.view.crossfadeShape,
                downloadsAlbums = p[K.downloadsAlbums] ?: d.view.downloadsAlbums,
                artistLinks = p[K.artistLinks] ?: d.view.artistLinks,
                backdropFade = p[K.backdropFade] ?: d.view.backdropFade,
                replayGraph = p.enum(K.replayGraph, d.view.replayGraph),
                replayStoryFont = p[K.replayStoryFont] ?: d.view.replayStoryFont,
            ),
            sound = SoundPrefs(
                crossfadeCurve = p.enum(K.crossfadeCurve, d.sound.crossfadeCurve),
                normalizeTarget = (p[K.normalizeTarget] ?: d.sound.normalizeTarget).coerceIn(-30, -5),
                normalizeAlbums = p[K.normalizeAlbums] ?: d.sound.normalizeAlbums,
                musicSpeed = (p[K.musicSpeedDefault] ?: d.sound.musicSpeed).coerceIn(0.5f, 2f),
                musicPitch = (p[K.musicPitchDefault] ?: d.sound.musicPitch).coerceIn(-12, 12),
                // Skip silence used to be one switch for everything: episodes keep what it was until set apart.
                skipSilenceEpisodes = p[K.skipSilenceEpisodes] ?: p[K.skipSilence] ?: d.sound.skipSilenceEpisodes,
                sleepFadeSeconds = (p[K.sleepFade] ?: d.sound.sleepFadeSeconds).coerceIn(0, 60),
                pauseFade = p[K.pauseFade] ?: d.sound.pauseFade,
                ownLossyFiles = p[K.ownLossyFiles] ?: d.sound.ownLossyFiles,
            ),
            behavior = BehaviorPrefs(
                lockPortrait = p[K.lockPortrait] ?: d.behavior.lockPortrait,
                pauseOnMute = p[K.pauseOnMute] ?: d.behavior.pauseOnMute,
                pip = p.enum(K.pipMode, d.behavior.pip),
                panelSwipes = p[K.panelSwipes] ?: d.behavior.panelSwipes,
                recentShows = p.enum(K.recentShows, d.behavior.recentShows),
                queueLocked = p[K.queueLocked] ?: d.behavior.queueLocked,
                scrollShowsControls = p[K.scrollShowsControls] ?: d.behavior.scrollShowsControls,
                playlistGenres = p[K.playlistGenres] ?: d.behavior.playlistGenres,
                screenFades = p[K.screenFades] ?: d.behavior.screenFades,
                gesturesEverywhere = p[K.gesturesEverywhere] ?: d.behavior.gesturesEverywhere,
                pageSlide = p[K.pageSlide] ?: d.behavior.pageSlide,
                tabBarPosition = p.enum(K.tabBarPosition, d.behavior.tabBarPosition),
                explicitContent = p.enum(K.explicitContent, d.behavior.explicitContent),
                historyPausedUntil = p[K.historyPausedUntil] ?: d.behavior.historyPausedUntil,
                playCountSeconds = (p[K.playCountSeconds] ?: d.behavior.playCountSeconds).coerceIn(0, 600),
                playCountOnAccount = p[K.playCountOnAccount] ?: d.behavior.playCountOnAccount,
            ),
            tv = TvPrefs(
                ambientMinutes = (p[K.tvAmbientMinutes] ?: d.tv.ambientMinutes).coerceIn(0, 60),
                deskMode = p[K.deskMode] ?: d.tv.deskMode,
                lyricsScale = (p[K.tvLyricsScale] ?: d.tv.lyricsScale).coerceIn(0.75f, 1.5f),
            welcomed = p[K.tvWelcomed] ?: d.tv.welcomed,
                homeLayout = p[K.tvHomeLayout]?.let { runCatching { json.decodeFromString<List<HomeSectionConfig>>(it) }.getOrNull() } ?: d.tv.homeLayout,
                backdropDim = (p[K.tvBackdropDim] ?: d.tv.backdropDim).coerceIn(TvPrefs.DIM_LIGHTER, TvPrefs.DIM_DARKEST),
            ),
            uiDensity = p.enum(K.uiDensity, d.uiDensity),
            titleSize = p.enum(K.titleSize, d.titleSize),
            cardSize = p.enum(K.cardSize, d.cardSize),
            reduceMotion = p[K.reduceMotion] ?: d.reduceMotion,
            navStyle = p.enum(K.navStyle, d.navStyle),
            navLabels = p.enum(K.navLabels, d.navLabels),
            navHideOnScroll = p[K.navHideOnScroll] ?: d.navHideOnScroll,
            navTabs = p[K.navTabs]?.split(",")?.mapNotNull { n -> NavTab.entries.firstOrNull { it.name == n } }?.takeIf { it.isNotEmpty() } ?: d.navTabs,
            tabNames = p[K.tabNames]?.let(::parseTabNames) ?: d.tabNames,
            navBarSize = p.enum(K.navBarSize, d.navBarSize),
            navBarFill = p.enum(K.navBarFill, d.navBarFill),
            swipeTabs = p[K.swipeTabs] ?: d.swipeTabs,
            miniPlayerJoined = p[K.miniPlayerJoined] ?: d.miniPlayerJoined,
            localSections = p[K.localSections]?.split(",")?.mapNotNull { n -> LocalSection.entries.firstOrNull { it.name == n } }?.takeIf { it.isNotEmpty() } ?: d.localSections,
            localSort = p[K.localSort] ?: d.localSort,
            startTab = p.enum(K.startTab, d.startTab),
            miniPlayerStyle = p.enum(K.miniPlayerStyle, d.miniPlayerStyle),
            miniSkipButtons = p[K.miniSkipButtons] ?: d.miniSkipButtons,
            miniPrevButton = p[K.miniPrevButton] ?: d.miniPrevButton,
            miniProgress = p.enum(K.miniProgress, d.miniProgress),
            playerBackground = p.enum(K.playerBackground, d.playerBackground),
            // #309 (1.8.1): down to a fully dark player.
            playerBackdropShow = (p[K.playerBackdropShow] ?: d.playerBackdropShow).coerceIn(com.prism.music.ui.player.BACKDROP_SHOW_MIN, 1f),
            playerArtCorners = p[K.playerArtCorners] ?: d.playerArtCorners,
            transportStyle = p.enum(K.transportStyle, d.transportStyle),
            coverSize = p.enum(K.coverSize, d.coverSize),
            scrubberStyle = p.enum(K.scrubberStyle, d.scrubberStyle),
            artShrinkOnPause = p[K.artShrinkOnPause] ?: d.artShrinkOnPause,
            showOutputDevice = p[K.showOutputDevice] ?: d.showOutputDevice,
            lyricsAlign = p.enum(K.lyricsAlign, d.lyricsAlign),
            playerLayout = p.enum(K.playerLayout, d.playerLayout),
            vinylTonearm = p[K.vinylTonearm] ?: d.vinylTonearm,
            // Every part is in the order, a part added later at the end.
            playerDeck = p.enumList(K.playerDeck, d.playerDeck).let { it + (DeckPart.entries - it.toSet()) },
            playerButtons = p.enumList(K.playerButtons, d.playerButtons),
            sessionButtons = p.enumList(K.sessionButtons, d.sessionButtons),
            scrubberColor = p.enum(K.scrubberColor, d.scrubberColor),
            playerTitleAlign = p.enum(K.playerTitleAlign, d.playerTitleAlign),
            playerTitleSize = p.enum(K.playerTitleSize, d.playerTitleSize),
            playerTitleScroll = p[K.playerTitleScroll] ?: d.playerTitleScroll,
            playerTimes = p.enum(K.playerTimes, d.playerTimes),
            meshSpeed = p[K.meshSpeed] ?: d.meshSpeed,
            meshIntensity = p[K.meshIntensity] ?: d.meshIntensity,
            playerBlur = p[K.playerBlur] ?: d.playerBlur,
            canvasFillsBackground = p[K.canvasFillsBackground] ?: d.canvasFillsBackground,
            scrubberThickness = (p[K.scrubberThickness] ?: d.scrubberThickness).coerceIn(0.6f, 2f),
            scrubberThumb = p.enum(K.scrubberThumb, d.scrubberThumb),
            volumeMatchesProgress = p[K.volumeMatchesProgress] ?: d.volumeMatchesProgress,
            playerHandle = p[K.playerHandle] ?: d.playerHandle,
            hideStatusBarPlayer = p[K.hideStatusBarPlayer] ?: d.hideStatusBarPlayer,
            playerNavBand = p[K.playerNavBand] ?: d.playerNavBand,
            visualizer = p.enum(K.visualizer, d.visualizer),
            visualizerColor = p.enum(K.visualizerColor, d.visualizerColor),
            lyricsLook = LyricsLookPrefs(
                style = p.enum(K.lyricsStyle, d.lyricsStyle),
                // Until it's set, the strength follows the old switch: on is 1.7's blur, off none.
                blurAmount = (p[K.lyricsBlurAmount] ?: if (p[K.lyricsBlur] ?: d.lyricsBlur) 1f else 0f).coerceIn(0f, 2f),
                spacing = (p[K.lyricsSpacing] ?: d.lyricsSpacing).coerceIn(0.6f, 1.8f),
                othersAlpha = (p[K.lyricsOthersAlpha] ?: d.lyricsOthersAlpha).coerceIn(0.2f, 1f),
                sungColor = p.enum(K.lyricsSungColor, d.lyricsSungColor),
                sungCustom = p[K.lyricsSungCustom] ?: d.lyricsSungCustom,
                otherColor = p.enum(K.lyricsOtherColor, d.lyricsOtherColor),
                otherCustom = p[K.lyricsOtherCustom] ?: d.lyricsOtherCustom,
                weight = p.enum(K.lyricsWeight, d.lyricsWeight),
                backingSize = (p[K.lyricsBackingSize] ?: d.lyricsBackingSize).coerceIn(0.4f, 0.9f),
                shadow = p[K.lyricsShadow] ?: d.lyricsShadow,
                uppercase = p[K.lyricsUppercase] ?: d.lyricsUppercase,
                ahead = p.enum(K.lyricsAhead, d.lyricsAhead),
                position = p.enum(K.lyricsPosition, d.lyricsPosition),
                extraSize = (p[K.lyricsExtraSize] ?: d.lyricsExtraSize).coerceIn(0.7f, 1.4f),
                extraColor = p.enum(K.lyricsExtraColor, d.lyricsExtraColor),
                extraItalic = p[K.lyricsExtraItalic] ?: d.lyricsExtraItalic,
                extraPlace = p.enum(K.lyricsExtraPlace, d.lyricsExtraPlace),
                background = p.enum(K.lyricsBackground, d.lyricsBackground),
                immersiveBackground = p.enum(K.immersiveBackground, d.immersiveBackground),
                bgColor = p[K.lyricsBgColor] ?: d.lyricsBgColor,
                bgColor2 = p[K.lyricsBgColor2] ?: d.lyricsBgColor2,
                everywhere = p[K.lyricsEverywhere] ?: d.lyricsEverywhere,
                gapMark = p.enum(K.lyricsGapMark, d.lyricsGapMark),
                // 1.8.0 lyrics look: unset, each part follows the Style.
                motion = p[K.lyricsMotion]?.let { v -> LyricsMotion.entries.firstOrNull { it.name == v } },
                wordByWord = p[K.lyricsWordByWord],
                card = p[K.lyricsCard],
                halo = p[K.lyricsHalo],
                boldLines = p[K.lyricsBoldLines],
            ),
            lyricsStripAlign = p.enum(K.lyricsStripAlign, d.lyricsStripAlign),
            lyricsStripTwoLines = p[K.lyricsStripTwoLines] ?: d.lyricsStripTwoLines,
            mergeLikedVideos = p[K.mergeLikedVideos] ?: d.mergeLikedVideos,
            hideStatusBar = p[K.hideStatusBar] ?: d.hideStatusBar,
            volumeControl = p.enum(K.volumeControl, d.volumeControl),
            appVolume = (p[K.appVolume] ?: d.appVolume).coerceIn(0f, 1f),
            coverSwipeLeft = p.enum(K.coverSwipeLeft, d.coverSwipeLeft),
            coverSwipeRight = p.enum(K.coverSwipeRight, d.coverSwipeRight),
            coverSwipeUp = p.enum(K.coverSwipeUp, d.coverSwipeUp),
            coverDoubleTap = p.enum(K.coverDoubleTap, d.coverDoubleTap),
            coverLongPress = p.enum(K.coverLongPress, d.coverLongPress),
            miniSwipeLeft = p.enum(K.miniSwipeLeft, d.miniSwipeLeft),
            miniSwipeRight = p.enum(K.miniSwipeRight, d.miniSwipeRight),
            miniSwipeDown = p.enum(K.miniSwipeDown, d.miniSwipeDown),
            miniSwipeUp = p.enum(K.miniSwipeUp, d.miniSwipeUp),
            libraryView = p.enum(K.libraryView, d.libraryView),
            libraryViews = p[K.libraryViews]?.let(::parseLibraryViews) ?: d.libraryViews,
            libraryColumns = p.enum(K.libraryColumns, d.libraryColumns),
            librarySubtitles = p[K.librarySubtitles] ?: d.librarySubtitles,
            libraryStartTab = p[K.libraryStartTab] ?: d.libraryStartTab,
            libraryPlaylistsFirst = p[K.libraryPlaylistsFirst] ?: d.libraryPlaylistsFirst,
            libraryTiles = p[K.libraryTiles]?.let { v -> if (v.isEmpty()) emptyList() else v.split(',').mapNotNull { n -> LibraryTile.entries.firstOrNull { it.name == n } } } ?: d.libraryTiles,
            playlistHeroCover = p[K.playlistHeroCover] ?: d.playlistHeroCover,
            frostedPages = p[K.frostedPages] ?: d.frostedPages,
            replayThemeColors = p[K.replayThemeColors] ?: d.replayThemeColors,
            performanceMode = p[K.performanceMode] ?: d.performanceMode,
            performanceOffered = p[K.performanceOffered] ?: d.performanceOffered,
        )
    }

    /** The stored settings as they last read (what [previewLook] starts from). */
    @Volatile private var raw: Preferences = runBlocking { context.dataStore.data.first() }

    /**
     * The settings as Prism uses them: with Performance mode on, its lighter look in place of the listener's heavier
     * choices ([AppSettings.effective]). The choices themselves are [stored], unchanged.
     */
    val flow: StateFlow<AppSettings> = context.dataStore.data.map { raw = it; read(it).effective() }.stateIn(
        scope, SharingStarted.Eagerly, read(raw).effective()
    )

    val current: AppSettings get() = flow.value

    /** The settings as the listener set them (Settings shows these: Performance mode doesn't change a switch). */
    val stored: StateFlow<AppSettings> = context.dataStore.data.map { read(it) }.stateIn(scope, SharingStarted.Eagerly, read(raw))

    /** The look settings now set ([lookOf]): what Looks are saved from and what undo steps back through. */
    val lookValues: StateFlow<Map<String, String>> = context.dataStore.data.map(::lookOf)
        .distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, lookOf(raw))

    /** The look settings as [lookValues] will read them once [values] is the Look (what a switch is expected to land on). */
    fun lookAfter(values: Map<String, String>): Map<String, String> = lookOf(raw.toMutablePreferences().also { putLook(it, values) })

    /** The settings as they'd be with [values] as the Look, without changing anything (Look cards draw from it). */
    fun previewLook(values: Map<String, String>): AppSettings = read(raw.toMutablePreferences().also { putLook(it, values) })

    /**
     * Makes [values] the Look in one edit, so the app redraws once. [fontChanged]: an imported font file was swapped in
     * for it, so text using it is drawn again.
     */
    suspend fun applyLook(values: Map<String, String>, fontChanged: Boolean = false) = editNow { p ->
        putLook(p, values)
        if (fontChanged) p[K.fontStamp] = System.currentTimeMillis()
    }

    /** Puts the look settings named [names] back to how Prism ships ("Reset this page"). */
    @Suppress("UNCHECKED_CAST")
    fun resetLookKeys(names: Collection<String>) = edit { p ->
        names.forEach { n -> lookKeysByName[n]?.let { p.remove(it as Preferences.Key<Any>) } }
    }

    // One write at a time, in the order they were asked for (1.8.0): a merged choice writes two or three keys with
    // separate edits, and two quick taps could interleave (Everywhere then Play read back as Radios). A one-thread
    // dispatcher wasn't enough: an edit suspends inside DataStore and lets the next one start, so they could still land
    // out of order. One reader takes them off a queue and finishes each before the next (what's waiting by then goes in
    // the same edit, still in order).
    private val writes = kotlinx.coroutines.channels.Channel<(MutablePreferences) -> Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)

    init {
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            for (first in writes) {
                val batch = mutableListOf(first)
                while (true) batch += writes.tryReceive().getOrNull() ?: break
                runCatching { context.dataStore.edit { p -> batch.forEach { b -> runCatching { b(p) } } } }
                    .onFailure { android.util.Log.w("PrismSettings", "a settings write failed", it) }
            }
        }
    }

    private fun edit(block: (MutablePreferences) -> Unit) {
        writes.trySend(block)
    }

    suspend fun editNow(block: (MutablePreferences) -> Unit) {
        context.dataStore.edit { block(it) }
    }

    // ---- Backups that hold only some parts (1.7.8, #149): settings and the sign-in share this store.

    /** The YouTube Music sign-in's own settings (a backup's "Accounts"). */
    private val accountKeys = setOf("cookie", "visitor_data", "data_sync_id", "auth_user", "account_name", "account_email", "account_avatar")

    /** Every setting now, as tagged text by name: the settings ([settings]) and/or the sign-in ([accounts]). */
    suspend fun forBackup(settings: Boolean, accounts: Boolean): Map<String, String> =
        context.dataStore.data.first().asMap().mapNotNull { (k, v) ->
            val account = k.name in accountKeys
            if (if (account) !accounts else !settings) return@mapNotNull null
            BackupValue.tag(v)?.let { k.name to it }
        }.toMap()

    /**
     * Puts a backup's settings in place: those of the parts it holds replace what's here ([settings]: every setting but
     * the sign-in; [accounts]: the sign-in), the rest stay as they are.
     */
    suspend fun fromBackup(values: Map<String, String>, settings: Boolean, accounts: Boolean) = editNow { p ->
        p.asMap().keys.filter { k -> if (k.name in accountKeys) accounts else settings }.forEach { k ->
            @Suppress("UNCHECKED_CAST") p.remove(k as Preferences.Key<Any>)
        }
        values.forEach { (name, tagged) ->
            if (if (name in accountKeys) !accounts else !settings) return@forEach
            BackupValue.put(p, name, tagged)
        }
    }

    fun setOnboarded(v: Boolean) = edit { it[K.onboarded] = v }

    /** Once (1.7): Home layouts saved before "Made for you" and "New releases" existed get them, after Quick picks. */
    fun addNewHomeSections() = edit { p ->
        fun once(flag: String, change: (List<HomeSectionConfig>) -> List<HomeSectionConfig>) {
            val key = booleanPreferencesKey(flag)
            if (p[key] == true) return
            p[K.homeLayout]?.let { stored ->
                runCatching { json.decodeFromString<List<HomeSectionConfig>>(stored) }.getOrNull()?.let { layout ->
                    p[K.homeLayout] = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(HomeSectionConfig.serializer()), change(layout))
                }
            }
            p[key] = true
        }
        once("home_sections_1_7") { HomeSections.withNewSections(it) }
        // Made on the phone (1.7): On repeat and Mixes from your music, after Made for you.
        once("home_sections_local") {
            HomeSections.withNewSections(
                it, listOf(HomeSectionConfig(HomeSections.ON_REPEAT, "On repeat")),
                after = HomeSections.MADE_FOR_YOU,
            )
        }
        // Mixes from your music joined Made for you (1.7): one section.
        once("home_mixes_merged") { HomeSections.mergeYourMixes(it) }
        // The Replay card heads Home (1.7).
        once("home_replay_top") { HomeSections.replayOnTop(it) }
        // The holiday mixes' section (1.8.0), after Quick picks: it only shows in November and December.
        once("home_holiday_1_8") { HomeSections.withNewSections(it, listOf(HomeSectionConfig(HomeSections.SEASONAL, "Holiday music"))) }
        keepSmartDownloads(p)
    }

    companion object {
        private val smartDownloadsKept = booleanPreferencesKey("smart_downloads_kept_1811")

        /**
         * Once (#339, 1.8.1.1): Smart Downloads became off by default for new installs. Someone already using Prism
         * (set up or signed in) who never touched the switch had it on by the old default, so it's written down as on.
         */
        internal fun keepSmartDownloads(p: MutablePreferences) {
            if (p[smartDownloadsKept] == true) return
            if (p[K.smartDownloads] == null && (p[K.onboarded] == true || !p[K.cookie].isNullOrBlank())) p[K.smartDownloads] = true
            p[smartDownloadsKept] = true
        }
    }

    /** Whether the listener has an answer for Smart Downloads (#339): Home offers it once until they do. */
    suspend fun smartDownloadsChosen(): Boolean = context.dataStore.data.first()[K.smartDownloads] != null

    suspend fun saveAccount(cookie: String, visitorData: String, dataSyncId: String) = editNow {
        it[K.cookie] = cookie
        it[K.visitorData] = visitorData
        it[K.dataSyncId] = dataSyncId
        // A fresh sign-in is the session's first Google account.
        it[K.authUser] = 0
    }

    fun saveAccountInfo(name: String, email: String?, avatar: String?) = edit {
        it[K.accountName] = name
        it[K.accountEmail] = email ?: ""
        it[K.accountAvatar] = avatar ?: ""
    }

    /** Switches to another account or channel: [authUser] picks the Google account, [dataSyncId] the channel. */
    suspend fun saveIdentity(authUser: Int, dataSyncId: String, cookie: String? = null) = editNow {
        it[K.authUser] = authUser
        it[K.dataSyncId] = dataSyncId
        if (cookie != null) it[K.cookie] = cookie
    }

    fun setVisitorData(v: String) = edit { it[K.visitorData] = v }
    fun setDiscordPresence(v: Boolean) = edit { it[K.discordPresence] = v }

    fun logout() = edit {
        it.remove(K.cookie); it.remove(K.dataSyncId); it.remove(K.authUser)
        it.remove(K.accountName); it.remove(K.accountEmail); it.remove(K.accountAvatar)
    }

    fun setThemeMode(v: ThemeMode) = edit { it[K.themeMode] = v.name }
    fun setPureBlack(v: Boolean) = edit { it[K.pureBlack] = v }
    fun setColorSource(v: ColorSource) = edit { it[K.colorSource] = v.name }
    fun setAccent(v: Int) = edit { it[K.accent] = v }
    fun setLiquidGlass(v: Boolean) = edit { it[K.liquidGlass] = v; if (v) it[K.frostedGlass] = false }
    fun setFrostedGlass(v: Boolean) = edit { it[K.frostedGlass] = v; if (v) it[K.liquidGlass] = false }
    fun setHaptics(v: Boolean) = edit { it[K.haptics] = v }
    fun setGlassKind(v: GlassKind) = edit { it[K.glassKind] = v.name }
    fun setLightStyle(v: LightStyle) = edit { it[K.lightStyle] = v.name }
    fun setAccentExact(v: Boolean) = edit { it[K.accentExact] = v }
    fun setAccentLight(v: Int) = edit { it[K.accentLight] = v }
    fun setSpectrumPreset(v: String) = edit { it[K.spectrumPreset] = v }
    /** Your own split light: its colors (2–7, in order) and, with them, the Custom preset. */
    fun setSpectrumStops(v: List<Int>) = edit {
        it[K.spectrumStops] = v.take(7).joinToString(",") { c -> "%08X".format(c) }
        it[K.spectrumPreset] = "CUSTOM"
    }
    fun setSpectrumBands(v: Boolean) = edit { it[K.spectrumBands] = v }
    fun setSurfaceTint(v: SurfaceTint) = edit { it[K.surfaceTint] = v.name }
    fun setSurfaceTintStrength(v: Float) = edit { it[K.surfaceTintStrength] = v }
    fun setSurfaceColor(v: Int) = edit { it[K.surfaceColor] = v; it[K.surfaceTint] = SurfaceTint.CUSTOM.name }
    fun setContrast(v: ContrastLevel) = edit { it[K.contrast] = v.name }
    fun setArtworkSwatch(v: ArtworkSwatch) = edit { it[K.artworkSwatch] = v.name }
    fun setArtworkVivid(v: Boolean) = edit { it[K.artworkVivid] = v }
    fun setArtworkChange(v: ArtworkChange) = edit { it[K.artworkChange] = v.name }
    fun setPageColors(v: Boolean) = edit { it[K.pageColors] = v }
    /** Appearance → Color → Reset colors: style, accent, split light, background, contrast and cover color as Prism ships. */
    @Suppress("UNCHECKED_CAST")
    fun resetColors() = edit { p -> K.colors.forEach { p.remove(it as Preferences.Key<Any>) } }
    // 1.8.0 player editor: the cover and the video are one switch (see [fullBleed]).
    fun setFullBleedArtwork(v: Boolean) = edit { it[K.fullBleedArtwork] = v }
    fun setVideoFullBleed(v: Boolean) = edit { it[K.videoFullBleed] = v }
    fun setImmersivePlayer(v: Boolean) = edit { it[K.immersivePlayer] = v }
    fun setImmersiveArt(v: ImmersiveArt) = edit { it[K.immersiveArt] = v.name }
    fun setImmersiveLyrics(v: ImmersiveLyrics) = edit { it[K.immersiveLyrics] = v.name }
    fun setImmersiveControls(v: Boolean) = edit { it[K.immersiveControls] = v }
    fun setPlayerShuffleRepeat(v: Boolean) = edit { it[K.playerShuffleRepeat] = v }
    fun setPreload(v: Preload) = edit { it[K.preload] = v.name }
    fun setPreloadCellular(v: Boolean) = edit { it[K.preloadCellular] = v }
    fun setCanvasEnabled(v: Boolean) = edit { it[K.canvasEnabled] = v }
    fun setCanvasOnCellular(v: Boolean) = edit { it[K.canvasOnCellular] = v }
    fun setHideVolumeBar(v: Boolean) = edit { it[K.hideVolumeBar] = v }
    fun setShowPlayingFrom(v: Boolean) = edit { it[K.showPlayingFrom] = v }
    fun setSpatial(v: Boolean) = edit { it[K.spatial] = v }
    fun setSpatialAmount(v: Float) = edit { it[K.spatialAmount] = v }
    fun setLyricsBlur(v: Boolean) = edit { it[K.lyricsBlur] = v }
    fun setLyricsProgressLine(v: Boolean) = edit { it[K.lyricsProgressLine] = v }
    fun setLyricsSourceButton(v: Boolean) = edit { it[K.lyricsSourceButton] = v }
    fun setBackdrops(v: Map<String, String>) = edit { it[K.backdrops] = json.encodeToString<Map<String, String>>(v) }
    fun setBackdropDim(v: Float) = edit { it[K.backdropDim] = v }
    fun setBackdropMotion(v: Boolean) = edit { it[K.backdropMotion] = v }
    fun setLyricsOnPlayer(v: Boolean) = edit { it[K.lyricsOnPlayer] = v }
    fun setLyricsOrder(v: List<LyricsSource>) = edit { it[K.lyricsOrder] = v.joinToString(",") { s -> s.name } }
    fun setPreferSynced(v: Boolean) = edit { it[K.preferSynced] = v }
    fun setLyricsOff(v: Set<LyricsSource>) = edit { it[K.lyricsOff] = v.joinToString(",") { s -> s.name } }
    fun setSkipCensored(v: Boolean) = edit { it[K.skipCensored] = v }
    fun setLyricsTranslate(v: Boolean) = edit { it[K.lyricsTranslate] = v }
    fun setLyricsTranslateTo(v: String) = edit { it[K.lyricsTranslateTo] = v }
    fun setLyricsFileWords(v: Boolean) = edit { it[K.lyricsFileWords] = v }
    fun setLyricsFileTranslation(v: Boolean) = edit { it[K.lyricsFileTranslation] = v }
    fun setRomanize(v: RomanizeMode) = edit { it[K.romanize] = v.name }
    fun setRomanizeAll(v: Boolean) = edit { it[K.romanizeAll] = v }
    fun setSingOnCellular(v: Boolean) = edit { it[K.singOnCellular] = v }
    fun setSingAlwaysReady(v: Boolean) = edit { it[K.singAlwaysReady] = v }
    fun setSingCacheMb(v: Int) = edit { it[K.singCacheMb] = v }
    fun setDjFlow(v: com.prism.music.playback.DjFlow) = edit { it[K.djFlow] = v.name }
    fun setReleaseNotifications(v: Boolean) = edit { it[K.releaseNotifications] = v }
    fun setRewindNotification(v: Boolean) = edit { it[K.rewindNotification] = v }
    fun setWebRemote(v: Boolean) = edit { it[K.webRemote] = v }
    fun setHandoffAvailable(v: Boolean) = edit { it[K.handoffAvailable] = v }
    fun setLyricsScale(v: Float) = edit { it[K.lyricsScale] = v }
    fun setCarLyrics(v: Boolean) = edit { it[K.carLyrics] = v }
    fun setCarLyricsStyle(v: CarLyricsStyle) = edit { it[K.carLyricsStyle] = v.name }
    fun setCarLyricsOnPhone(v: Boolean) = edit { it[K.carLyricsOnPhone] = v }
    fun setCarLyricsKeepTitle(v: Boolean) = edit { it[K.carLyricsKeepTitle] = v }
    fun setAutoGrid(v: Boolean) = edit { it[K.autoGrid] = v }
    fun setShareYoutubeLinks(v: Boolean) = edit { it[K.shareYoutubeLinks] = v }
    fun setShowNerdStats(v: Boolean) = edit { it[K.showNerdStats] = v }
    fun setShowQuality(v: Boolean) = edit { it[K.showQuality] = v }
    fun setSquareVideoCovers(v: Boolean) = edit { it[K.squareVideoCovers] = v }
    fun setLyricsOnMobileData(v: Boolean) = edit { it[K.lyricsOnMobileData] = v }
    fun setBestPartButton(v: Boolean) = edit { it[K.bestPartButton] = v }
    fun setShowVideoSwitch(v: Boolean) = edit { it[K.showVideoSwitch] = v }
    fun setHoldSeekSeconds(v: Int) = edit { it[K.holdSeekSeconds] = v.coerceIn(0, 20) }
    fun setCanvasWithDownloads(v: Boolean) = edit { it[K.canvasWithDownloads] = v }
    fun setCanvasWithDownloadsCellular(v: Boolean) = edit { it[K.canvasWithDownloadsCellular] = v }
    fun setAudioQuality(v: AudioQuality) = edit { it[K.audioQuality] = v.name }
    fun setCellularQuality(v: AudioQuality) = edit { it[K.cellularQuality] = v.name }
    fun setPremiumStream(v: Boolean) = edit { it[K.premiumStream] = v }
    fun setAutoplay(v: Boolean) = edit { it[K.autoplay] = v }
    fun setAutoplaySource(v: AutoplaySource) = edit { it[K.autoplaySource] = v.name }
    fun setReplayCard(name: String) = edit { it[K.replayCard] = name }
    fun setRadioSongsOnly(v: Boolean) = edit { it[K.radioSongsOnly] = v }
    fun setSongsEverywhere(v: Boolean) = edit { it[K.songsEverywhere] = v }
    fun setShowTrackLength(v: Boolean) = edit { it[K.showTrackLength] = v }
    fun setAudioDiagnostics(v: Boolean) = edit { it[K.audioDiagnostics] = v }
    fun setSearchKeyboard(v: Boolean) = edit { it[K.searchKeyboard] = v }
    fun setDoubleTapSeekSeconds(v: Int) = edit { it[K.doubleTapSeekSeconds] = v }
    fun setStopOnSwipeAway(v: Boolean) = edit { it[K.stopOnSwipeAway] = v }
    fun setDataSaver(v: Boolean) = edit { it[K.dataSaver] = v }
    fun setVideoContinues(v: Boolean) = edit { it[K.videoContinues] = v }
    fun setLossless(v: Boolean) = edit { it[K.lossless] = v }
    fun setLosslessLocal(v: Boolean) = edit { it[K.losslessLocal] = v }
    fun setNormalize(v: Boolean) = edit { it[K.normalize] = v }
    fun setMix(v: Boolean) = edit { it[K.mix] = v }
    fun setMixSeconds(v: Int) = edit { it[K.mixSeconds] = v.coerceIn(1, 12) }
    fun setMixAuto(v: Boolean) = edit { it[K.mixAuto] = v }
    fun setMixBeatMatch(v: Boolean) = edit { it[K.mixBeatMatch] = v }
    fun setMixKeepAlbums(v: Boolean) = edit { it[K.mixKeepAlbums] = v }
    fun setMixTempoOnCellular(v: Boolean) = edit { it[K.mixTempoOnCellular] = v }
    fun setContentCountry(v: String) = edit { it[K.contentCountry] = v }
    fun setSkipSilence(v: Boolean) = edit { it[K.skipSilence] = v }
    fun setVideoMaxHeight(v: Int) = edit { it[K.videoMaxHeight] = v }
    fun setCacheSizeMb(v: Int) = edit { it[K.cacheSizeMb] = v }
    fun setDownloadWifiOnly(v: Boolean) = edit { it[K.downloadWifiOnly] = v }
    fun setDownloadLikes(v: Boolean) = edit { it[K.downloadLikes] = v }
    fun setSmartDownloads(v: Boolean) = edit { it[K.smartDownloads] = v }
    fun setSmartDownloadGb(v: Float) = edit { it[K.smartDownloadGb] = v }
    fun setOfflineGb(v: Float) = edit { it[K.offlineGb] = v }
    fun setSmartDownloadsCellular(v: Boolean) = edit { it[K.smartDownloadsCellular] = v }
    fun setEq(v: EqSettings) = edit { it[K.eq] = json.encodeToString(EqSettings.serializer(), v) }
    fun setHomeLayout(v: List<HomeSectionConfig>) = edit {
        it[K.homeLayout] = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(HomeSectionConfig.serializer()), v)
    }
    fun setAutoAddSections(v: Boolean) = edit { it[K.autoAddSections] = v }
    fun setSearchSections(v: List<SearchSection>) = edit { it[K.searchSections] = v.joinToString(",") { s -> s.name } }
    fun setSearchChips(v: List<String>) = edit { it[K.searchChips] = v.joinToString(",") }
    fun setHomeHeader(v: HomeHeader) = edit { it[K.homeHeader] = v.name }
    fun setHomeHeaderHeight(v: HomeHeaderHeight) = edit { it[K.homeHeaderHeight] = v.name }
    fun setHomeHeaderPicture(v: String) = edit { it[K.homeHeaderPicture] = v }
    fun setHomeHideKind(kind: String, hide: Boolean) = edit {
        val now = (it[K.homeHideKinds]?.split(',')?.filter { k -> k.isNotBlank() }?.toSet() ?: emptySet()).let { s -> if (hide) s + kind else s - kind }
        it[K.homeHideKinds] = now.joinToString(",")
    }
    fun setReplayHours(v: Boolean) = edit { it[K.replayHours] = v }
    fun setGreetingText(v: String) = edit { it[K.greetingText] = v }
    fun setGreetingShowName(v: Boolean) = edit { it[K.greetingShowName] = v }
    fun setHomeShortcuts(v: List<HomeShortcut>) = edit { it[K.homeShortcuts] = v.joinToString(",") { s -> s.name } }
    fun setHomeRecentTiles(v: Int) = edit { it[K.homeRecentTiles] = v }
    fun setHomePlaylists(v: List<PinnedPlaylist>) = edit { it[K.homePlaylists] = json.encodeToString<List<PinnedPlaylist>>(v) }
    fun setHomeCustomizeButton(v: Boolean) = edit { it[K.homeCustomizeButton] = v }
    fun setHomeContinue(v: Boolean) = edit { it[K.homeContinue] = v }
    fun setQuickPicksRadio(v: Boolean) = edit { it[K.quickPicksRadio] = v }
    fun setSpeedDialYouTubeOnly(v: Boolean) = edit { it[K.speedDialYouTubeOnly] = v }
    fun setHomeCustomizeTopButton(v: Boolean) = edit { it[K.homeCustomizeTopButton] = v }
    fun setHomePodcastsButton(v: Boolean) = edit { it[K.homePodcastsButton] = v }
    fun setFontChoice(v: FontChoice) = edit { it[K.fontChoice] = v.name }
    /** After importing (or removing, [name] blank) the app's own font. */
    fun setAppFont(name: String) = edit { it[K.appFontName] = name; it[K.fontStamp] = System.currentTimeMillis() }
    fun setLyricsFont(v: String) = edit { it[K.lyricsFont] = v }
    fun setLyricsFontFile(name: String) = edit { it[K.lyricsFontName] = name; it[K.fontStamp] = System.currentTimeMillis() }
    fun setTextScale(v: Float) = edit { it[K.textScale] = v }
    /** A corners step: the slider goes back to following it. */
    fun setCorners(v: Corners) = edit { it[K.corners] = v.name; it.remove(K.cornerScale) }
    fun setCornerScale(v: Float) = edit { it[K.cornerScale] = v.coerceIn(0f, 2f) }
    fun setCoverShape(which: String, v: CoverShape) = edit { p ->
        val key = when (which) { "songs" -> K.songShape; "albums" -> K.albumShape; "artists" -> K.artistShape; "player" -> K.playerShape; else -> return@edit }
        p[key] = v.name
    }
    fun setButtonShape(v: ButtonShape) = edit { it[K.buttonShape] = v.name }
    fun setTabTint(v: TabTint) = edit { it[K.tabTint] = v.name }
    fun setTypeWeight(v: TypeWeight) = edit { it[K.typeWeight] = v.name }
    fun setTypeSpacing(v: TypeSpacing) = edit { it[K.typeSpacing] = v.name }
    fun setPlayerTitleWeight(v: PlayerTitleWeight) = edit { it[K.playerTitleWeight] = v.name }
    /** Null: titles in the app's font. */
    fun setTitleFont(v: FontChoice?) = edit { if (v == null) it.remove(K.titleFont) else it[K.titleFont] = v.name }
    fun setPlayOutlined(v: Boolean) = edit { it[K.playOutlined] = v }
    fun setTypeClassic(v: Boolean) = edit { it[K.typeClassic] = v }
    fun setControlsClassic(v: Boolean) = edit { it[K.controlsClassic] = v }
    fun setUiDensity(v: UiDensity) = edit { it[K.uiDensity] = v.name }
    fun setTitleSize(v: TitleSize) = edit { it[K.titleSize] = v.name }
    fun setCardSize(v: CardSize) = edit { it[K.cardSize] = v.name }
    fun setReduceMotion(v: Boolean) = edit { it[K.reduceMotion] = v }
    fun setNavStyle(v: NavBarStyle) = edit { it[K.navStyle] = v.name }
    fun setNavLabels(v: NavLabels) = edit { it[K.navLabels] = v.name }
    fun setNavHideOnScroll(v: Boolean) = edit { it[K.navHideOnScroll] = v }
    fun setNavTabs(v: List<NavTab>) = edit { it[K.navTabs] = v.joinToString(",") { t -> t.name } }
    /** A tab's own name; blank gives it back its own. */
    fun setTabName(tab: NavTab, name: String) = edit {
        val names = (it[K.tabNames]?.let(::parseTabNames) ?: emptyMap()).toMutableMap()
        val clean = cleanTabName(name)
        if (clean.isEmpty() || clean == tab.label) names.remove(tab) else names[tab] = clean
        it[K.tabNames] = names.entries.joinToString("|") { (t, n) -> "${t.name}=$n" }
    }
    fun setNavBarSize(v: NavBarSize) = edit { it[K.navBarSize] = v.name }
    fun setNavBarFill(v: NavBarFill) = edit { it[K.navBarFill] = v.name }
    fun setSwipeTabs(v: Boolean) = edit { it[K.swipeTabs] = v }
    fun setMiniPlayerJoined(v: Boolean) = edit { it[K.miniPlayerJoined] = v }
    fun setLocalSections(v: List<LocalSection>) = edit { it[K.localSections] = v.joinToString(",") { t -> t.name } }
    fun setLocalSort(v: String) = edit { it[K.localSort] = v }
    fun setStartTab(v: NavTab) = edit { it[K.startTab] = v.name }
    fun setMiniPlayerStyle(v: MiniPlayerStyle) = edit { it[K.miniPlayerStyle] = v.name }
    fun setMiniSkipButtons(v: Boolean) = edit { it[K.miniSkipButtons] = v }
    fun setMiniPrevButton(v: Boolean) = edit { it[K.miniPrevButton] = v }
    fun setMiniPlayButton(v: Boolean) = edit { it[K.miniPlayButton] = v }
    fun setMiniProgress(v: MiniProgress) = edit { it[K.miniProgress] = v.name }
    fun setMiniProgressColor(v: ScrubberColor) = edit { it[K.miniProgressColor] = v.name }
    fun setMiniFillStrength(v: Float) = edit { it[K.miniFillStrength] = v }
    fun setPlayRing(v: Boolean) = edit { it[K.playRing] = v }
    fun setPlayingInPageColor(v: Boolean) = edit { it[K.playingInPageColor] = v }
    fun setPlayerBackground(v: PlayerBackground) = edit { it[K.playerBackground] = v.name }
    fun setPlayerBackdropShow(v: Float) = edit { it[K.playerBackdropShow] = v }
    fun setPlayerArtCorners(v: Int) = edit { it[K.playerArtCorners] = v }
    fun setTransportStyle(v: TransportStyle) = edit { it[K.transportStyle] = v.name }
    fun setCoverSize(v: CoverSize) = edit { it[K.coverSize] = v.name }
    fun setScrubberStyle(v: ScrubberStyle) = edit { it[K.scrubberStyle] = v.name }
    fun setPlayerLayout(v: PlayerLayout) = edit { it[K.playerLayout] = v.name }
    fun setVinylTonearm(v: Boolean) = edit { it[K.vinylTonearm] = v }
    fun setPlayerDeck(v: List<DeckPart>) = edit { it[K.playerDeck] = v.joinToString(",") { p -> p.name } }
    fun setPlayerButtons(v: List<PlayerButton>) = edit { it[K.playerButtons] = v.joinToString(",") { b -> b.name } }
    fun setSessionButtons(v: List<SessionButton>) = edit { it[K.sessionButtons] = v.joinToString(",") { b -> b.name } }
    fun setScrubberColor(v: ScrubberColor) = edit { it[K.scrubberColor] = v.name }
    fun setPlayerTitleAlign(v: PlayerTitleAlign) = edit { it[K.playerTitleAlign] = v.name }
    fun setPlayerTitleSize(v: PlayerTitleSize) = edit { it[K.playerTitleSize] = v.name }
    fun setPlayerTitleScroll(v: Boolean) = edit { it[K.playerTitleScroll] = v }
    fun setPlayerTimes(v: PlayerTimes) = edit { it[K.playerTimes] = v.name }
    fun setMeshSpeed(v: Float) = edit { it[K.meshSpeed] = v }
    fun setMeshIntensity(v: Float) = edit { it[K.meshIntensity] = v }
    fun setPlayerBlur(v: Float) = edit { it[K.playerBlur] = v }
    fun setCanvasFillsBackground(v: Boolean) = edit { it[K.canvasFillsBackground] = v }
    fun setScrubberThickness(v: Float) = edit { it[K.scrubberThickness] = v.coerceIn(0.6f, 2f) }
    fun setScrubberThumb(v: ScrubberThumb) = edit { it[K.scrubberThumb] = v.name }
    fun setVolumeMatchesProgress(v: Boolean) = edit { it[K.volumeMatchesProgress] = v }
    fun setPlayerHandle(v: Boolean) = edit { it[K.playerHandle] = v }
    fun setHideStatusBarPlayer(v: Boolean) = edit { it[K.hideStatusBarPlayer] = v }
    fun setPlayerNavBand(v: Boolean) = edit { it[K.playerNavBand] = v }
    fun setVisualizer(v: VisualizerStyle) = edit { it[K.visualizer] = v.name }
    fun setVisualizerColor(v: ScrubberColor) = edit { it[K.visualizerColor] = v.name }
    fun setLyricsStyle(v: LyricsStyle) = edit { it[K.lyricsStyle] = v.name }
    /** The blur's strength; the old switch follows (on above none), so older code paths and Looks agree. */
    fun setLyricsBlurAmount(v: Float) = edit { it[K.lyricsBlurAmount] = v; it[K.lyricsBlur] = v > 0f }
    fun setLyricsSpacing(v: Float) = edit { it[K.lyricsSpacing] = v }
    fun setLyricsOthersAlpha(v: Float) = edit { it[K.lyricsOthersAlpha] = v }
    fun setLyricsSungColor(v: LyricsColor) = edit { it[K.lyricsSungColor] = v.name }
    fun setLyricsSungCustom(v: Int) = edit { it[K.lyricsSungCustom] = v; it[K.lyricsSungColor] = LyricsColor.CUSTOM.name }
    fun setLyricsOtherColor(v: LyricsColor) = edit { it[K.lyricsOtherColor] = v.name }
    fun setLyricsOtherCustom(v: Int) = edit { it[K.lyricsOtherCustom] = v; it[K.lyricsOtherColor] = LyricsColor.CUSTOM.name }
    /** The lines' weight, as picked (1.8.0: Bold card's own Bold gives way to it). */
    fun setLyricsWeight(v: LyricsWeight) = edit { it[K.lyricsWeight] = v.name; it[K.lyricsBoldLines] = false }
    fun setLyricsBackingSize(v: Float) = edit { it[K.lyricsBackingSize] = v }
    fun setLyricsShadow(v: Boolean) = edit { it[K.lyricsShadow] = v }
    fun setLyricsUppercase(v: Boolean) = edit { it[K.lyricsUppercase] = v }
    fun setLyricsAhead(v: LyricsAhead) = edit { it[K.lyricsAhead] = v.name }
    fun setLyricsPosition(v: LyricsPosition) = edit { it[K.lyricsPosition] = v.name }
    fun setLyricsExtraSize(v: Float) = edit { it[K.lyricsExtraSize] = v }
    fun setLyricsExtraColor(v: LyricsColor) = edit { it[K.lyricsExtraColor] = v.name }
    fun setLyricsExtraItalic(v: Boolean) = edit { it[K.lyricsExtraItalic] = v }
    fun setLyricsExtraPlace(v: LyricsExtraPlace) = edit { it[K.lyricsExtraPlace] = v.name }
    fun setLyricsBackground(v: LyricsBackground) = edit { it[K.lyricsBackground] = v.name }
    fun setImmersiveBackground(v: LyricsBackground) = edit { it[K.immersiveBackground] = v.name }
    fun setLyricsBgColors(first: Int, second: Int) = edit { it[K.lyricsBgColor] = first; it[K.lyricsBgColor2] = second }
    fun setLyricsEverywhere(v: Boolean) = edit { it[K.lyricsEverywhere] = v }
    fun setLyricsGapMark(v: LyricsGapMark) = edit { it[K.lyricsGapMark] = v.name }
    // 1.8.0 lyrics look (#253)
    /** "Start from" [v]: as [LyricsLookPrefs.startFrom], every part back to what [v] sets. */
    fun setLyricsStartFrom(v: LyricsStyle) = edit { p ->
        p[K.lyricsStyle] = v.name
        K.lyricsParts.forEach { (k, _) -> @Suppress("UNCHECKED_CAST") p.remove(k as Preferences.Key<Any>) }
        if (v == LyricsStyle.CARD && p[K.lyricsBackground] != LyricsBackground.COVER_COLOR.name) p[K.lyricsBackground] = LyricsBackground.PLAYER.name
    }
    fun setLyricsMotion(v: LyricsMotion) = edit { it[K.lyricsMotion] = v.name }
    fun setLyricsWordByWord(v: Boolean) = edit { it[K.lyricsWordByWord] = v }
    fun setLyricsHalo(v: Boolean) = edit { it[K.lyricsHalo] = v }
    /** What's behind the lyrics page; [card]: Bold card, in place of the player's background. */
    fun setLyricsBehind(v: LyricsBackground, card: Boolean) = edit { it[K.lyricsBackground] = v.name; it[K.lyricsCard] = card }
    // 1.7.8
    fun setPanelPlayer(v: PanelPlayer) = edit { it[K.panelPlayer] = v.name }
    fun setQueueHeadings(v: Boolean) = edit { it[K.queueHeadings] = v }
    fun setQueueDjChip(v: Boolean) = edit { it[K.queueDjChip] = v }
    fun setCastMark(v: Boolean) = edit { it[K.castMark] = v }
    fun setImmersiveVideo(v: Boolean) = edit { it[K.immersiveVideo] = v }
    fun setBleedFit(v: BleedFit) = edit { it[K.bleedFit] = v.name }
    fun setCoverFlight(v: Boolean) = edit { it[K.coverFlight] = v }
    fun setVideoControlsHide(v: Boolean) = edit { it[K.videoControlsHide] = v }
    fun setLockPortrait(v: Boolean) = edit { it[K.lockPortrait] = v }
    fun setGesturesEverywhere(v: Boolean) = edit { it[K.gesturesEverywhere] = v }
    fun setPageSlide(v: Boolean) = edit { it[K.pageSlide] = v }
    fun setTabBarPosition(v: TabBarPosition) = edit { it[K.tabBarPosition] = v.name }
    fun setTvAmbientMinutes(v: Int) = edit { it[K.tvAmbientMinutes] = v.coerceIn(0, 60) }
    fun setDeskMode(v: Boolean) = edit { it[K.deskMode] = v }
    fun setTvLyricsScale(v: Float) = edit { it[K.tvLyricsScale] = v.coerceIn(0.75f, 1.5f) }
    fun setTvBackdropDim(v: Int) = edit { it[K.tvBackdropDim] = v.coerceIn(TvPrefs.DIM_LIGHTER, TvPrefs.DIM_DARKEST) }
    fun setTvWelcomed() = edit { it[K.tvWelcomed] = true }
    /** The TV Home's rows ([TvPrefs.homeLayout]); an empty list is Home as it comes (Back to default). */
    fun setTvHomeLayout(v: List<HomeSectionConfig>) = edit {
        if (v.isEmpty()) it.remove(K.tvHomeLayout)
        else it[K.tvHomeLayout] = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(HomeSectionConfig.serializer()), v)
    }
    fun setPauseOnMute(v: Boolean) = edit { it[K.pauseOnMute] = v }
    fun setPipMode(v: PipMode) = edit { it[K.pipMode] = v.name }
    fun setPanelSwipes(v: Boolean) = edit { it[K.panelSwipes] = v }
    fun setRecentShows(v: RecentShows) = edit { it[K.recentShows] = v.name }
    fun setQueueLocked(v: Boolean) = edit { it[K.queueLocked] = v }
    fun setScrollShowsControls(v: Boolean) = edit { it[K.scrollShowsControls] = v }
    fun setPlaylistGenres(v: Boolean) = edit { it[K.playlistGenres] = v }
    fun setScreenFades(v: Boolean) = edit { it[K.screenFades] = v }
    fun setCrossfadeMark(v: Boolean) = edit { it[K.crossfadeMark] = v }
    fun setBlendCovers(v: Boolean) = edit { it[K.blendCovers] = v }
    fun setButtonsPill(v: Boolean) = edit { it[K.buttonsPill] = v }
    fun setCrossfadeShape(v: Boolean) = edit { it[K.crossfadeShape] = v }
    fun setDownloadsAlbums(v: Boolean) = edit { it[K.downloadsAlbums] = v }
    fun setArtistLinks(v: Boolean) = edit { it[K.artistLinks] = v }
    fun setBackdropFade(v: Boolean) = edit { it[K.backdropFade] = v }
    fun setExplicitContent(v: ExplicitContent) = edit { it[K.explicitContent] = v.name }
    fun setReplayGraph(v: ReplayGraph) = edit { it[K.replayGraph] = v.name }
    fun setReplayStoryFont(v: Boolean) = edit { it[K.replayStoryFont] = v }
    /** #296: history paused until [until] (0: on again). */
    fun setHistoryPausedUntil(until: Long) = edit { if (until > 0) it[K.historyPausedUntil] = until else it.remove(K.historyPausedUntil) }
    fun setPlayCountSeconds(v: Int) = edit { it[K.playCountSeconds] = v.coerceIn(0, 600) }
    fun setPlayCountOnAccount(v: Boolean) = edit { it[K.playCountOnAccount] = v }

    // 1.8.0 player editor
    /** A comma-separated set of names (unknown ones skipped): a Look or backup from a later Prism still opens. */
    private inline fun <reified E : Enum<E>> namesOf(v: String?): List<E> =
        v.orEmpty().split(',').mapNotNull { n -> enumValues<E>().firstOrNull { it.name == n } }.distinct()

    private fun readParts(p: Preferences): PlayerParts {
        val d = PlayerParts()
        return PlayerParts(
            hidden = namesOf<PlayerPart>(p[K.playerHiddenParts]).filter { it in PlayerPart.NEW }.toSet(),
            menuHidden = namesOf<MenuAction>(p[K.playerMenuHidden]).filter { it.foldable }.toSet(),
            // Set (even to none): as picked; never set: the menu's own four.
            menuTiles = p[K.playerMenuTiles]?.let { v -> namesOf<MenuAction>(v).filter { !it.fromPlayer }.take(MenuAction.MAX_TILES) } ?: d.menuTiles,
        )
    }

    /** Shows or hides [part] on the player, in its own switch where it has one ("What's on the player"). */
    fun setPartShown(part: PlayerPart, on: Boolean) = edit {
        when (part) {
            PlayerPart.HANDLE -> it[K.playerHandle] = on
            PlayerPart.FROM_ALBUM -> it[K.showPlayingFrom] = on
            PlayerPart.LYRIC_LINE -> it[K.lyricsOnPlayer] = on
            // Times back: both, as the player first had them (left / elapsed only are picked in Title and times).
            PlayerPart.TIMES -> it[K.playerTimes] = if (on) PlayerTimes.BOTH.name else PlayerTimes.NONE.name
            PlayerPart.QUALITY -> it[K.showQuality] = on
            PlayerPart.BEST_PART -> it[K.bestPartButton] = on
            PlayerPart.SHUFFLE_REPEAT -> it[K.playerShuffleRepeat] = on
            PlayerPart.VOLUME -> it[K.hideVolumeBar] = !on
            PlayerPart.OUTPUT -> it[K.showOutputDevice] = on
            PlayerPart.NAV_BAND -> it[K.playerNavBand] = on
            PlayerPart.CROSSFADE_MARK -> it[K.crossfadeMark] = on
            PlayerPart.BLEND_COVERS -> it[K.blendCovers] = on
            else -> {
                val hidden = namesOf<PlayerPart>(it[K.playerHiddenParts]).toSet().let { h -> if (on) h - part else h + part }
                if (hidden.isEmpty()) it.remove(K.playerHiddenParts) else it[K.playerHiddenParts] = hidden.joinToString(",") { h -> h.name }
            }
        }
    }

    /** Shows [action] in the ⋯ menu's groups, or folds it under "More" ("Edit this menu"). */
    fun setMenuShown(action: MenuAction, on: Boolean) = edit {
        val hidden = namesOf<MenuAction>(it[K.playerMenuHidden]).toSet().let { h -> if (on) h - action else h + action }
        if (hidden.isEmpty()) it.remove(K.playerMenuHidden) else it[K.playerMenuHidden] = hidden.joinToString(",") { h -> h.name }
    }

    /** The ⋯ menu's quick tiles, in order (up to four); the menu's own four when they're those again. */
    fun setMenuTiles(v: List<MenuAction>) = edit {
        val tiles = v.filter { a -> !a.fromPlayer }.distinct().take(MenuAction.MAX_TILES)
        if (tiles == MenuAction.DEFAULT_TILES) it.remove(K.playerMenuTiles) else it[K.playerMenuTiles] = tiles.joinToString(",") { t -> t.name }
    }

    /** Edit this menu → "Back to Prism's menu". */
    fun resetMenu() = edit { it.remove(K.playerMenuHidden); it.remove(K.playerMenuTiles) }

    fun setCrossfadeCurve(v: CrossfadeCurve) = edit { it[K.crossfadeCurve] = v.name }
    fun setNormalizeTarget(v: Int) = edit { it[K.normalizeTarget] = v.coerceIn(-30, -5) }
    fun setNormalizeAlbums(v: Boolean) = edit { it[K.normalizeAlbums] = v }
    fun setMusicDefaults(speed: Float, pitch: Int) = edit {
        it[K.musicSpeedDefault] = speed.coerceIn(0.5f, 2f); it[K.musicPitchDefault] = pitch.coerceIn(-12, 12)
    }
    fun setSkipSilenceEpisodes(v: Boolean) = edit { it[K.skipSilenceEpisodes] = v }
    fun setSleepFade(v: Int) = edit { it[K.sleepFade] = v.coerceIn(0, 60) }
    fun setPauseFade(v: Boolean) = edit { it[K.pauseFade] = v }
    fun setOwnLossyFiles(v: Boolean) = edit { it[K.ownLossyFiles] = v }
    fun setLyricsStripAlign(v: LyricsAlign) = edit { it[K.lyricsStripAlign] = v.name }
    fun setLyricsStripTwoLines(v: Boolean) = edit { it[K.lyricsStripTwoLines] = v }
    fun setMergeLikedVideos(v: Boolean) = edit { it[K.mergeLikedVideos] = v }
    fun setHideStatusBar(v: Boolean) = edit { it[K.hideStatusBar] = v }
    fun setVolumeControl(v: VolumeControl) = edit { it[K.volumeControl] = v.name }
    fun setAppVolume(v: Float) = edit { it[K.appVolume] = v.coerceIn(0f, 1f) }
    /** One of the gestures ([which]: "cover_swipe_left" … "mini_swipe_down", the setting's stored name). */
    fun setGesture(which: String, v: PlayerGesture) = edit { p ->
        val key = gestureKeys[which] ?: return@edit
        p[key] = v.name
    }
    private val gestureKeys = listOf(
        K.coverSwipeLeft, K.coverSwipeRight, K.coverSwipeUp, K.coverDoubleTap, K.coverLongPress, K.miniSwipeLeft, K.miniSwipeRight, K.miniSwipeDown, K.miniSwipeUp,
    ).associateBy { it.name }
    fun setArtShrinkOnPause(v: Boolean) = edit { it[K.artShrinkOnPause] = v }
    fun setShowOutputDevice(v: Boolean) = edit { it[K.showOutputDevice] = v }
    fun setLyricsAlign(v: LyricsAlign) = edit { it[K.lyricsAlign] = v.name }
    fun setLibraryView(v: LibraryView) = edit { it[K.libraryView] = v.name; it.remove(K.libraryViews) }
    /** One Library tab's view ("PLAYLISTS", "ALBUMS", "ARTISTS"). */
    fun setLibraryTabView(tab: String, v: LibraryView) = edit {
        val views = (it[K.libraryViews]?.let(::parseLibraryViews) ?: emptyMap()) + (tab to v)
        it[K.libraryViews] = views.entries.joinToString("|") { (t, view) -> "$t=${view.name}" }
    }
    fun setLibraryColumns(v: LibraryColumns) = edit { it[K.libraryColumns] = v.name }
    fun setLibrarySubtitles(v: Boolean) = edit { it[K.librarySubtitles] = v }
    fun setLibraryStartTab(v: String) = edit { it[K.libraryStartTab] = v }
    fun setLibraryTiles(v: List<LibraryTile>) = edit { it[K.libraryTiles] = v.joinToString(",") { t -> t.name } }
    fun setLibraryPlaylistsFirst(v: Boolean) = edit { it[K.libraryPlaylistsFirst] = v }
    fun setPlaylistHeroCover(v: Boolean) = edit { it[K.playlistHeroCover] = v }
    fun setFrostedPages(v: Boolean) = edit { it[K.frostedPages] = v }
    fun setReplayThemeColors(v: Boolean) = edit { it[K.replayThemeColors] = v }
    fun setPerformanceMode(v: Boolean) = edit { it[K.performanceMode] = v; it[K.performanceOffered] = true }
    fun setPerformanceOffered() = edit { it[K.performanceOffered] = true }

    /** Puts every look & layout option back to how Prism ships (colors and backgrounds are left alone). */
    @Suppress("UNCHECKED_CAST")
    fun resetLook() = edit { p -> K.look.forEach { p.remove(it as Preferences.Key<Any>) } }
}
