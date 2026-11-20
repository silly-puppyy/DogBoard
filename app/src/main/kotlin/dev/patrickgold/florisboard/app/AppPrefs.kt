/*
 * Copyright (C) 2021-2026 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import dev.patrickgold.florisboard.app.settings.theme.ColorPreferenceSerializer
import dev.patrickgold.florisboard.app.settings.theme.DisplayKbdAfterDialogs
import dev.patrickgold.florisboard.app.settings.theme.SnyggLevel
import dev.patrickgold.florisboard.ime.clipboard.CLIPBOARD_HISTORY_NUM_GRID_COLUMNS_AUTO
import dev.patrickgold.florisboard.ime.clipboard.ClipboardSyncBehavior
import dev.patrickgold.florisboard.ime.core.DisplayLanguageNamesIn
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.extension.ExtensionComponentName
import dev.patrickgold.florisboard.ime.extension.extDogBoardTheme
import dev.patrickgold.florisboard.ime.keyboard.IncognitoMode
import dev.patrickgold.florisboard.ime.keyboard.SpaceBarMode
import dev.patrickgold.florisboard.ime.keyboard3.ImeActions
import dev.patrickgold.florisboard.ime.keyboard3.interaction.InteractionTimingOptions
import dev.patrickgold.florisboard.ime.keyboard3.touch.FnKeyAction
import dev.patrickgold.florisboard.ime.keyboard3.touch.FnKeyArrangement
import dev.patrickgold.florisboard.ime.keyboard3.touch.ShiftKeyBehavior
import dev.patrickgold.florisboard.ime.keyboard3.touch.TouchModelOptions
import dev.patrickgold.florisboard.ime.landscapeinput.LandscapeInputUiMode
import dev.patrickgold.florisboard.ime.media.emoji.EmojiHairStyle
import dev.patrickgold.florisboard.ime.media.emoji.EmojiHistory
import dev.patrickgold.florisboard.ime.media.emoji.EmojiSkinTone
import dev.patrickgold.florisboard.ime.media.emoji.EmojiSuggestionType
import dev.patrickgold.florisboard.ime.nlp.SpellingLanguageMode
import dev.patrickgold.florisboard.ime.smartbar.CandidatesDisplayMode
import dev.patrickgold.florisboard.ime.smartbar.ExtendedActionsPlacement
import dev.patrickgold.florisboard.ime.smartbar.IncognitoDisplayMode
import dev.patrickgold.florisboard.ime.smartbar.SmartbarLayout
import dev.patrickgold.florisboard.ime.smartbar.quickaction.QuickActionArrangement
import dev.patrickgold.florisboard.ime.smartbar.quickaction.QuickActionJsonConfig
import dev.patrickgold.florisboard.ime.text.gestures.SwipeAction
import dev.patrickgold.florisboard.ime.theme.PreferredThemeMode
import dev.patrickgold.florisboard.ime.window.ImeWindowConfig
import dev.patrickgold.jetpref.datastore.annotations.Preferences
import dev.patrickgold.jetpref.datastore.jetprefDataStoreOf
import dev.patrickgold.jetpref.datastore.model.LocalTime
import dev.patrickgold.jetpref.datastore.model.PreferenceData
import dev.patrickgold.jetpref.datastore.model.PreferenceMigrationEntry
import dev.patrickgold.jetpref.datastore.model.PreferenceModel
import dev.patrickgold.jetpref.datastore.model.PreferenceType
import dev.patrickgold.jetpref.material.ui.ColorRepresentation
import kotlinx.serialization.json.Json
import org.florisboard.lib.android.isOrientationPortrait

val FlorisPreferenceStore = jetprefDataStoreOf(FlorisPreferenceModel::class)

@Preferences
abstract class FlorisPreferenceModel : PreferenceModel() {
    companion object {
        const val NAME = "florisboard-app-prefs"
    }

    val clipboard = Clipboard()
    inner class Clipboard {
        val useInternalClipboard = boolean(
            key = "clipboard__use_internal_clipboard",
            default = false,
        )
        val syncToFloris = enum(
            key = "clipboard__sync_to_floris",
            default = ClipboardSyncBehavior.ALL_EVENTS,
        )
        val syncToSystem = enum(
            key = "clipboard__sync_to_system",
            default = ClipboardSyncBehavior.NO_EVENTS,
        )
        val suggestionEnabled = boolean(
            key = "clipboard__suggestion_enabled",
            default = true,
        )
        val suggestionTimeout = int(
            key = "clipboard__suggestion_timeout",
            default = 60,
        )
        val historyEnabled = boolean(
            key = "clipboard__history_enabled",
            default = false,
        )
        val historyNumGridColumnsPortrait = int(
            key = "clipboard__history_num_grid_columns_portrait",
            default = CLIPBOARD_HISTORY_NUM_GRID_COLUMNS_AUTO,
        )
        val historyNumGridColumnsLandscape = int(
            key = "clipboard__history_num_grid_columns_landscape",
            default = CLIPBOARD_HISTORY_NUM_GRID_COLUMNS_AUTO,
        )
        @Composable
        fun historyNumGridColumns(): PreferenceData<Int> {
            val configuration = LocalConfiguration.current
            return if (configuration.isOrientationPortrait()) {
                historyNumGridColumnsPortrait
            } else {
                historyNumGridColumnsLandscape
            }
        }
        val historyAutoCleanOldEnabled = boolean(
            key = "clipboard__history_auto_clean_old_enabled",
            default = false,
        )
        val historyAutoCleanOldAfter = int(
            key = "clipboard__history_auto_clean_old_after",
            default = 20,
        )
        val historyAutoCleanSensitiveEnabled = boolean(
            key = "clipboard__history_auto_clean_sensitive_enabled",
            default = false,
        )
        val historyAutoCleanSensitiveAfter = int(
            key = "clipboard__history_auto_clean_sensitive_after",
            // DogBoard: a minute (the designer's password tile counts down from it; user: off by default)
            default = 60,
        )
        val historySizeLimitEnabled = boolean(
            key = "clipboard__history_size_limit_enabled",
            default = true,
        )
        val historySizeLimit = int(
            key = "clipboard__history_size_limit",
            default = 20,
        )
        val historyHideOnPaste = boolean(
            key = "clipboard__history_hide_on_paste",
            default = false,
        )
        val historyHideOnNextTextField = boolean(
            key = "clipboard__history_hide_on_next_text_field",
            default = true,
        )
        // DogBoard (user, 2026-11-17): Gboard's smart clipboard chips (phone, link, email, address in a
        // copied text) on a switch, and opt-in removal of tracking tags from pasted links (LinkCleaner)
        val smartChips = boolean(key = "clipboard__smart_chips", default = true)
        val stripTrackers = boolean(key = "clipboard__strip_trackers", default = false)
        // with it: short links (vm.tiktok.com, pin.it...) asked once for their target (ShortLinks; network)
        val expandShortLinks = boolean(key = "clipboard__expand_short_links", default = false)
        /** DogBoard, the clipboard's hold menu: "Paste as file" where the app takes that file type (user 2026-11-20). */
        val pasteAsFile = boolean(key = "clipboard__paste_as_file", default = true)
        /** DogBoard, the clipboard's hold menu: "Share as file" through the share sheet (user 2026-11-20). */
        val shareAsFile = boolean(key = "clipboard__share_as_file", default = true)
        val clearPrimaryClipAffectsHistoryIfUnpinned = boolean(
            key = "clipboard__clear_primary_clip_affects_history_if_unpinned",
            default = true,
        )
    }

    val correction = Correction()
    inner class Correction {
        val autoSpacePunctuation = boolean(
            key = "correction__auto_space_punctuation",
            default = false,
        )
        val doubleSpacePeriod = boolean(
            key = "correction__double_space_period",
            default = true,
        )
        // DogBoard: replace a mistyped word when it is finished; backspace right after restores it
        val autocorrect = boolean(
            key = "correction__autocorrect",
            default = true,
        )
        // DogBoard: how readily words are changed (design: Gentle / Standard / Strong; Standard is iOS)
        val autocorrectLevel = enum(
            key = "correction__autocorrect_level",
            default = io.github.sillypuppyy.dogboard.engine.AutocorrectLevel.STANDARD,
        )
        // DogBoard: backspace right after an autocorrection puts the typed word back (iOS); a feature
        // with its own switch (user, 2026-11-17)
        val undoOnBackspace = boolean(key = "correction__undo_on_backspace", default = true)
        // DogBoard: Advanced > Neural model (only phones with room run it: EngineLoader.neuralTier)
        val neuralModel = boolean(key = "correction__neural_model", default = true)
        // DogBoard, below the common rows on the Autocorrect page (user, 2026-11-17): iOS's look back (the previous
        // word changes when the next one reads much better) and rejoining a word split by a stray space
        val fixPreviousWord = boolean(key = "correction__fix_previous_word", default = true)
        val joinSplitWords = boolean(key = "correction__join_split_words", default = true)
        // DogBoard: autocorrect adapts to what the user keeps and undoes (AutocorrectStrength; user, 2026-11-15: on)
        val adaptiveStrength = boolean(
            key = "correction__adaptive_strength",
            default = true,
        )
        // DogBoard (experimental, user 2026-11-17): the neural model also scores the look back at the
        // previous word (iOS: its transformer is part of the model that scores retrocorrections)
        val neuralRetro = boolean(
            key = "correction__neural_retro",
            default = false,
        )
        // DogBoard: a notification each time the adaptive strength changes step (user, 2026-11-16)
        val adaptiveNotify = boolean(
            key = "correction__adaptive_notify",
            default = true,
        )
        /** The notification permission was asked for once (the typing settings ask again only when the switch is turned on). */
        val adaptiveNotifyAsked = boolean(
            key = "correction__adaptive_notify_asked",
            default = false,
        )
    }

    val devtools = Devtools()
    inner class Devtools {
        val enabled = boolean(
            key = "devtools__enabled",
            default = false,
        )
        val showPrimaryClip = boolean(
            key = "devtools__show_primary_clip",
            default = false,
        )
        val showInputStateOverlay = boolean(
            key = "devtools__show_input_state_overlay",
            default = false,
        )
        val showSpellingOverlay = boolean(
            key = "devtools__show_spelling_overlay",
            default = false,
        )
        val showInlineAutofillOverlay = boolean(
            key = "devtools__show_inline_autofill_overlay",
            default = false,
        )
        val showKeyTouchBoundaries = boolean(
            key = "devtools__show_touch_boundaries",
            default = false,
        )
        val showDragAndDropHelpers = boolean(
            key = "devtools__show_drag_and_drop_helpers",
            default = false,
        )
        val showWindowResizeHandleBoundaries = boolean(
            key = "devtools__show_window_resize_handle_boundaries",
            default = false,
        )
    }

    val dictionary = Dictionary()
    inner class Dictionary {
        val enableSystemUserDictionary = boolean(
            key = "suggestion__enable_system_user_dictionary",
            default = true,
        )
        val enableFlorisUserDictionary = boolean(
            key = "suggestion__enable_floris_user_dictionary",
            default = true,
        )
    }

    val emoji = Emoji()
    inner class Emoji {
        val preferredSkinTone = enum(
            key = "emoji__preferred_skin_tone",
            default = EmojiSkinTone.DEFAULT,
        )
        // DogBoard: the variant last picked by long press, per emoji (as Gboard: no tone selector in
        // the panel, the choice stays with each emoji): lines "emoji in the default tone<TAB>picked"
        val toneChoices = string(key = "emoji__tone_choices", default = "")
        val preferredHairStyle = enum(
            key = "emoji__preferred_hair_style",
            default = EmojiHairStyle.DEFAULT,
        )
        val historyEnabled = boolean(
            key = "emoji__history_enabled",
            default = true,
        )
        val historyData = custom(
            key = "emoji__history_data",
            default = EmojiHistory.Empty,
            serializer = EmojiHistory.Serializer,
        )
        val historyPinnedUpdateStrategy = enum(
            key = "emoji__history_pinned_update_strategy",
            default = EmojiHistory.UpdateStrategy.MANUAL_SORT_PREPEND,
        )
        val historyPinnedMaxSize = int(
            key = "emoji__history_pinned_max_size",
            default = EmojiHistory.MaxSizeUnlimited,
        )
        val historyRecentUpdateStrategy = enum(
            key = "emoji__history_recent_update_strategy",
            default = EmojiHistory.UpdateStrategy.AUTO_SORT_PREPEND,
        )
        val historyRecentMaxSize = int(
            key = "emoji__history_recent_max_size",
            default = 90,
        )
        val suggestionEnabled = boolean(
            key = "emoji__suggestion_enabled",
            default = true,
        )
        val suggestionType = enum(
            key = "emoji__suggestion_type",
            default = EmojiSuggestionType.LEADING_COLON,
        )
        val suggestionUpdateHistory = boolean(
            key = "emoji__suggestion_update_history",
            default = true,
        )
        val suggestionCandidateShowName = boolean(
            key = "emoji__suggestion_candidate_show_name",
            default = false,
        )
        val suggestionQueryMinLength = int(
            key = "emoji__suggestion_query_min_length",
            default = 3,
        )
        val suggestionCandidateMaxCount = int(
            key = "emoji__suggestion_candidate_max_count",
            default = 5,
        )
        // TODO remove with emoji search PR!
        val experimentalEmojiSearchEnabled = boolean(
            key = "emoji__experimental_emoji_search_enabled",
            default = false,
        )
    }

    val gestures = Gestures()
    inner class Gestures {
        val swipeUp = enum(
            key = "gestures__swipe_up",
            default = SwipeAction.SHIFT,
        )
        val swipeDown = enum(
            key = "gestures__swipe_down",
            default = SwipeAction.HIDE_KEYBOARD,
        )
        val swipeLeft = enum(
            key = "gestures__swipe_left",
            default = SwipeAction.SWITCH_TO_NEXT_SUBTYPE,
        )
        val swipeRight = enum(
            key = "gestures__swipe_right",
            default = SwipeAction.SWITCH_TO_PREV_SUBTYPE,
        )
        val spaceBarSwipeUp = enum(
            key = "gestures__space_bar_swipe_up",
            default = SwipeAction.NO_ACTION,
        )
        val spaceBarSwipeLeft = enum(
            key = "gestures__space_bar_swipe_left",
            default = SwipeAction.MOVE_CURSOR_LEFT,
        )
        val spaceBarSwipeRight = enum(
            key = "gestures__space_bar_swipe_right",
            default = SwipeAction.MOVE_CURSOR_RIGHT,
        )
        val spaceBarLongPress = enum(
            key = "gestures__space_bar_long_press",
            default = SwipeAction.SHOW_INPUT_METHOD_PICKER,
        )
        val deleteKeySwipeLeft = enum(
            key = "gestures__delete_key_swipe_left",
            default = SwipeAction.DELETE_CHARACTERS_PRECISELY,
        )
        val deleteKeyLongPress = enum(
            key = "gestures__delete_key_long_press",
            default = SwipeAction.DELETE_CHARACTER,
        )
        val swipeDistanceThreshold = int(
            key = "gestures__swipe_distance_threshold",
            default = 32,
        )
        val swipeVelocityThreshold = int(
            key = "gestures__swipe_velocity_threshold",
            default = 1900,
        )
    }

    val glide = Glide()
    inner class Glide {
        val enabled = boolean(
            key = "glide__enabled",
            default = false,
        )
        val showTrail = boolean(
            key = "glide__show_trail",
            default = true,
        )
        val trailDuration = int(
            key = "glide__trail_fade_duration",
            default = 200,
        )
        val showPreview = boolean(
            key = "glide__show_preview",
            default = true,
        )
        val previewRefreshDelay = int(
            key = "glide__preview_refresh_delay",
            default = 150,
        )
        val immediateBackspaceDeletesWord = boolean(
            key = "glide__immediate_backspace_deletes_word",
            default = true,
        )
    }

    val inputFeedback = InputFeedback()
    inner class InputFeedback {
        val audioEnabled = boolean(
            key = "input_feedback__audio_enabled",
            default = true,
        )
        val audioVolume = int(
            key = "input_feedback__audio_volume",
            default = 50,
        )
        val audioFeatKeyPress = boolean(
            key = "input_feedback__audio_feat_key_press",
            default = true,
        )
        val audioFeatKeyLongPress = boolean(
            key = "input_feedback__audio_feat_key_long_press",
            default = false,
        )
        val audioFeatKeyRepeatedAction = boolean(
            key = "input_feedback__audio_feat_key_repeated_action",
            default = false,
        )
        val audioFeatGestureSwipe = boolean(
            key = "input_feedback__audio_feat_gesture_swipe",
            default = false,
        )
        val audioFeatGestureMovingSwipe = boolean(
            key = "input_feedback__audio_feat_gesture_moving_swipe",
            default = false,
        )

        val hapticEnabled = boolean(
            key = "input_feedback__haptic_enabled",
            default = true,
        )
        // DogBoard: the phone's key click by default (user, 2026-11-14: the soft tap felt weak and flat)
        val hapticStyle = enum(
            key = "input_feedback__haptic_style",
            // DogBoard: Rich (iPhone-like) by default; Plain = Gboard's (user 2026-11-17)
            default = io.github.sillypuppyy.dogboard.feel.HapticStyle.CRISP,
        )
        val hapticFeatKeyPress = boolean(
            key = "input_feedback__haptic_feat_key_press",
            default = true,
        )
        val hapticFeatKeyLongPress = boolean(
            key = "input_feedback__haptic_feat_key_long_press",
            default = false,
        )
        val hapticFeatKeyRepeatedAction = boolean(
            key = "input_feedback__haptic_feat_key_repeated_action",
            default = true,
        )
        val hapticFeatGestureSwipe = boolean(
            key = "input_feedback__haptic_feat_gesture_swipe",
            default = false,
        )
        val hapticFeatGestureMovingSwipe = boolean(
            key = "input_feedback__haptic_feat_gesture_moving_swipe",
            default = true,
        )
    }

    val keyboard = Keyboard()
    inner class Keyboard {
        val windowConfig = custom(
            key = "keyboard__window_config",
            default = emptyMap(),
            serializer = ImeWindowConfig.ByTypeSerializer,
        )
        val numberRow = boolean(
            key = "keyboard__number_row",
            default = false,
        )
        // DogBoard: the action key as a word ("Send") instead of its icon (designer: Icon is the default)
        val actionKeyWords = boolean(
            key = "keyboard__action_key_words",
            default = false,
        )
        // DogBoard: the designer's other Layout elements (Settings › Layout, mix and match)
        /** Number row "on long-press": q-p offer their digit first (numberRow stays the "On" choice). */
        val numberRowLongPress = boolean(
            key = "keyboard__number_row_long_press",
            default = false,
        )
        /** Long-press hints in the key corner: off / accents / symbols. */
        val hintKind = string(
            key = "keyboard__hint_kind",
            default = "off",
        )
        /** Space-bar label: auto (the language name with 2+ languages) / lang / space / blank. */
        val spaceLabel = string(
            key = "keyboard__space_label",
            default = "auto",
        )
        /** Bottom row: simple (123, emoji, space, action) or classic (?123, comma, emoji, space, period, action). */
        val bottomRow = string(
            key = "keyboard__bottom_row",
            default = "simple",
        )
        // DogBoard Layout elements (user 2026-11-17, mix and match): the symbol pages ("iphone" / "android")
        // and ?123 / action keys drawn as pills
        val symbolPages = string(key = "keyboard__symbol_pages", default = "iphone")
        val pillKeys = boolean(key = "keyboard__pill_keys", default = false)
        // DogBoard: the letter layout (tools/layouts/gen_layouts.py ids); autocorrection and
        // suggestions are English-only until the internationalisation pass
        val layoutId = string(
            key = "keyboard__layout_id",
            default = "qwerty-en",
        )
        /** Letters on the keys always in capitals (else they follow shift). */
        val capsLabels = boolean(
            key = "keyboard__caps_labels",
            default = false,
        )
        // DogBoard: keyboard height as a percentage of the design's key area (settings layout v2)
        val heightPercent = int(
            key = "keyboard__height_percent",
            default = 100,
        )
        val spaceBarMode = enum(
            key = "keyboard__space_bar_display_mode",
            default = SpaceBarMode.CURRENT_LANGUAGE,
        )
        val fontSizeMultiplierPortrait = int(
            key = "keyboard__font_size_multiplier_portrait",
            default = 100,
        )
        val fontSizeMultiplierLandscape = int(
            key = "keyboard__font_size_multiplier_landscape",
            default = 100,
        )
        val landscapeInputUiMode = enum(
            key = "keyboard__landscape_input_ui_mode",
            default = LandscapeInputUiMode.DYNAMICALLY_SHOW,
        )
        val keySpacingVertical = int(
            key = "keyboard__key_spacing_vertical",
            default = 100,
        )
        val keySpacingHorizontal = int(
            key = "keyboard__key_spacing_horizontal",
            default = 100,
        )
        val popupEnabled = boolean(
            key = "keyboard__popup_enabled",
            default = true,
        )
        val spaceBarSwitchesToCharacters = boolean(
            key = "keyboard__space_bar_switches_to_characters",
            default = true,
        )
        val incognitoDisplayMode = enum(
            key = "keyboard__incognito_indicator",
            default = IncognitoDisplayMode.DISPLAY_BEHIND_KEYBOARD,
        )

        val fnKeyEnabled = boolean(
            key = "keyboard__fn_key_enabled",
            default = TouchModelOptions.Default.fnKeyEnabled,
        )
        val fnKeyArrangement = custom(
            key = "keyboard__fn_key_arrangement",
            default = TouchModelOptions.Default.fnKeyArrangement,
            serializer = FnKeyArrangement.Serializer,
        )

        val longPressTimeoutUseSystem = boolean(
            key = "keyboard__long_press_timeout_use_system",
            default = true,
        )
        val longPressTimeout = int(
            key = "keyboard__long_press_timeout",
            default = InteractionTimingOptions.Default.longPressTimeout.inWholeMilliseconds.toInt(),
        )
        // DogBoard: the Glass theme: a photo behind the keys (design Kb:18-23, Themes.dc.html);
        // dim, blur and key opacity as in the design (percent; blur in dp), the photo's measured
        // luminance (per mille) for the label contrast, and a stamp that changes with the photo
        val glassEnabled = boolean(key = "keyboard__glass_enabled", default = false)
        // DogBoard: the Themes page (SetThemes.dc.html): day and night themes, following system dark mode
        val themeDay = string(key = "keyboard__theme_day", default = "dogboard")
        val themeNight = string(key = "keyboard__theme_night", default = "dogboard")
        val themeFollow = boolean(key = "keyboard__theme_follow", default = true)
        // Dalmatian: "open" = new spots each time the keyboard opens, "keep" = until shuffled; the kept coat
        val spotReroll = string(key = "keyboard__spot_reroll", default = "open")
        // DogBoard: Themes › Return key per theme (user 2026-11-19), "id=always;id=actions" (ThemeChoice.retAlways)
        val retColour = string(key = "keyboard__ret_colour", default = "")
        // DogBoard: the saved photo theme the photo slot holds (UserThemes; "" = none)
        val userTheme = string(key = "keyboard__user_theme", default = "")
        val spotSeed = int(key = "keyboard__spot_seed", default = 3)
        // theme packs hidden from the Themes page (section ids, comma separated)
        val hiddenThemePacks = string(key = "keyboard__hidden_theme_packs", default = "")
        // the photo theme's page (SetPhoto.dc.html): labels auto / white / dark, label shadow, which of the
        // photo's colours the return key takes, and where the photo sits (top / centre / bottom)
        val glassLabels = string(key = "keyboard__glass_labels", default = "auto")
        val glassHalo = boolean(key = "keyboard__glass_halo", default = true)
        val glassAccent = int(key = "keyboard__glass_accent", default = 0)
        val glassPhotoPos = string(key = "keyboard__glass_photo_pos", default = "center")
        val glassDim = int(key = "keyboard__glass_dim", default = 35)
        val glassBlur = int(key = "keyboard__glass_blur", default = 12)
        val glassKeyOpacity = int(key = "keyboard__glass_key_opacity", default = 30)
        val glassPhotoLuminance = int(key = "keyboard__glass_photo_luminance", default = 300)
        val glassPhotoStamp = long(key = "keyboard__glass_photo_stamp", default = 0L)
        // DogBoard: the photo theme's versions (bright, dark or both, each its own look; PhotoVersions JSON). Empty:
        // a photo set before versions, read from the glass_* prefs above
        val glassLooks = string(key = "keyboard__glass_looks", default = "")
        // DogBoard: draw the keys with the ported design (stage 2); off = FlorisBoard's drawing
        val dogboardDesign = boolean(
            key = "keyboard__dogboard_design",
            default = true,
        )
        val longPressKeyHintEnabled = boolean(
            key = "keyboard__long_press_key_hint_enabled",
            default = false, // DogBoard: clean caps like the design (and iOS); the hints stay a setting
        )
        val longPressKeyHintPlacement = enum(
            key = "keyboard__long_press_key_hint_placement",
            default = TouchModelOptions.Default.longPressKeyHintPlacement,
        )

        val multiTapTimeoutUseSystem = boolean(
            key = "keyboard__multi_tap_timeout_use_system",
            default = true,
        )
        val multiTapTimeout = int(
            key = "keyboard__multi_tap_timeout",
            default = InteractionTimingOptions.Default.multiTapTimeout.inWholeMilliseconds.toInt(),
        )
        val multiTapHighlightEnabled = boolean(
            key = "keyboard__multi_tap_highlight_enabled",
            default = TouchModelOptions.Default.multiTapHighlightEnabled,
        )

        val flickKeyHintEnabled = boolean(
            key = "keyboard__flick_key_hint_enabled",
            default = TouchModelOptions.Default.flickKeyHintEnabled,
        )
        val flickKeyHintPlacement = enum(
            key = "keyboard__flick_key_hint_placement",
            default = TouchModelOptions.Default.flickKeyHintPlacement,
        )
    }

    val localization = Localization()
    inner class Localization {
        val displayLanguageNamesIn = enum(
            key = "localization__display_language_names_in",
            default = DisplayLanguageNamesIn.SYSTEM_LOCALE,
        )
        val displayKeyboardLabelsInSubtypeLanguage = boolean(
            key = "localization__display_keyboard_labels_in_subtype_language",
            default = false,
        )
        val activeSubtypeId = long(
            key = "localization__active_subtype_id",
            default = Subtype.DEFAULT.id,
        )
        val subtypes = string(
            key = "localization__subtypes",
            default = "[]",
        )
    }

    val other = Other()
    inner class Other {
        // DogBoard: Developer options appear in Advanced after tapping the version in About 7 times
        val devOptionsUnlocked = boolean(key = "other__dev_options_unlocked", default = false)
        // DogBoard (user 2026-11-19): the settings take the keyboard theme's light or dark (Dalmatian in dark mode
        // -> bright settings), else the system's; colours stay Material either way
        val settingsFollowKeyboard = boolean(key = "other__settings_follow_keyboard", default = true)
        // DogBoard: the settings app's colours (user 2026-11-19): "keyboard" (light or dark and colours from the
        // keyboard theme, the default), "wallpaper" (Material You), "none" (DogBoard's default colours)
        val settingsColours = string(key = "other__settings_colours", default = "keyboard")
        val settingsTheme = enum(
            key = "other__settings_theme",
            default = AppTheme.AUTO,
        )
        val accentColor = custom(
            key = "other__accent_color",
            default = Color.Unspecified,
            serializer = ColorPreferenceSerializer,
        )
        val settingsLanguage = string(
            key = "other__settings_language",
            default = "auto",
        )
        val showAppIcon = boolean(
            key = "other__show_app_icon",
            default = true,
        )
    }

    val physicalKeyboard = PhysicalKeyboard()
    inner class PhysicalKeyboard {
        val showOnScreenKeyboard = boolean(
            key = "physical_keyboard__show_on_screen_keyboard",
            default = false,
        )
    }

    val smartbar = Smartbar()
    inner class Smartbar {
        val enabled = boolean(
            key = "smartbar__enabled",
            default = true,
        )
        val layout = enum(
            key = "smartbar__layout",
            default = SmartbarLayout.SUGGESTIONS_ACTIONS_SHARED,
        )
        val actionArrangement = custom(
            key = "smartbar__action_arrangement",
            default = QuickActionArrangement.Default,
            serializer = QuickActionArrangement.Serializer,
        )
        val flipToggles = boolean(
            key = "smartbar__flip_toggles",
            default = false,
        )
        val sharedActionsExpanded = boolean(
            key = "smartbar__shared_actions_expanded",
            default = false,
        )
        @Deprecated("Always enabled due to UX issues")
        val sharedActionsAutoExpandCollapse = boolean(
            key = "smartbar__shared_actions_auto_expand_collapse",
            default = true,
        )
        val sharedActionsExpandWithAnimation = boolean(
            key = "smartbar__shared_actions_expand_with_animation",
            default = true,
        )
        val extendedActionsExpanded = boolean(
            key = "smartbar__extended_actions_expanded",
            default = false,
        )
        val extendedActionsPlacement = enum(
            key = "smartbar__extended_actions_placement",
            default = ExtendedActionsPlacement.ABOVE_CANDIDATES,
        )
    }

    val spelling = Spelling()
    inner class Spelling {
        val languageMode = enum(
            key = "spelling__language_mode",
            default = SpellingLanguageMode.USE_KEYBOARD_SUBTYPES,
        )
        // DogBoard: also contact names as suggestions; opt-in (off until the user turns it on in onboarding)
        val useContacts = boolean(
            key = "spelling__use_contacts",
            default = false,
        )
        val useUdmEntries = boolean(
            key = "spelling__use_udm_entries",
            default = true,
        )
    }

    val suggestion = Suggestion()
    inner class Suggestion {
        // DogBoard (user 2026-11-19): what the bar shows in an empty field: "suggestions" (iOS: predicted first
        // words) or "tools" (Gboard: the tool row until you type)
        val beforeTyping = string(key = "suggestion__before_typing", default = "suggestions")
        val api30InlineSuggestionsEnabled = boolean(
            key = "suggestion__api30_inline_suggestions_enabled",
            default = true,
        )
        // DogBoard: suggestions on, three fixed slots like iOS
        val enabled = boolean(
            key = "suggestion__enabled",
            default = true,
        )
        val displayMode = enum(
            key = "suggestion__display_mode",
            default = CandidatesDisplayMode.CLASSIC,
        )
        val blockPossiblyOffensive = boolean(
            key = "suggestion__block_possibly_offensive",
            default = true,
        )
        // DogBoard: when the bar offers emoji (iOS: only after the user has used emoji, also in this app)
        val emojiMode = enum(
            key = "suggestion__emoji_mode",
            default = io.github.sillypuppyy.dogboard.engine.EmojiSuggestionMode.AFTER_USE,
        )
        val incognitoMode = enum(
            key = "suggestion__incognito_mode",
            default = IncognitoMode.DYNAMIC_ON_OFF,
        )
        // Internal pref
        val forceIncognitoModeFromDynamic = boolean(
            key = "suggestion__force_incognito_mode_from_dynamic",
            default = false,
        )
    }

    val theme = Theme()
    inner class Theme {
        val mode = enum(
            key = "theme__mode",
            default = PreferredThemeMode.FOLLOW_SYSTEM,
        )
        val dayThemeId = custom(
            key = "theme__day_theme_id",
            default = extDogBoardTheme("dogboard_light"),
            serializer = ExtensionComponentName.Serializer,
        )
        val nightThemeId = custom(
            key = "theme__night_theme_id",
            default = extDogBoardTheme("dogboard_dark"),
            serializer = ExtensionComponentName.Serializer,
        )
        val accentColor = custom(
            key = "theme__accent_color",
            default = Color.Unspecified,
            serializer = ColorPreferenceSerializer,
        )
        val sunriseTime = localTime(
            key = "theme__sunrise_time",
            default = LocalTime(6, 0),
        )
        val sunsetTime = localTime(
            key = "theme__sunset_time",
            default = LocalTime(18, 0),
        )
        val editorColorRepresentation = enum(
            key = "theme__editor_color_representation",
            default = ColorRepresentation.HEX,
        )
        val editorDisplayKbdAfterDialogs = enum(
            key = "theme__editor_display_kbd_after_dialogs",
            default = DisplayKbdAfterDialogs.REMEMBER,
        )
        val editorLevel = enum(
            key = "theme__editor_level",
            default = SnyggLevel.ADVANCED,
        )
    }

    val typing = Typing()
    inner class Typing {
        val autoCapitalization = boolean(
            key = "typing__auto_capitalization",
            default = true,
        )
        val rememberCapsLockState = boolean(
            key = "typing__remember_caps_lock_state",
            default = false,
        )
        val shiftKeyBehavior = enum(
            key = "typing__shift_key_behavior",
            default = ShiftKeyBehavior.CAPSLOCK_BY_DOUBLE_TAP,
        )
        // DogBoard: iOS Smart Punctuation, two switches as in iOS (smartQuotesEnabled, smartDashesEnabled)
        // default off (user, 2026-11-14), unlike iOS
        val smartQuotes = boolean(
            key = "typing__smart_quotes",
            default = false,
        )
        val smartDashes = boolean(
            key = "typing__smart_dashes",
            default = true,
        )
        // DogBoard: names and "I" take the dictionary's capitals (user, 2026-11-14: separate from sentence starts)
        val dictionaryCapitals = boolean(
            key = "typing__dictionary_capitals",
            default = true,
        )
        // DogBoard: Advanced › Fine-tuning timings (design SetFeel:103-115; defaults = feel/Timings, iOS)
        val doubleSpaceMs = int(key = "feel__double_space_ms", default = io.github.sillypuppyy.dogboard.feel.Timings.DoubleSpacePeriod.inWholeMilliseconds.toInt())
        val deleteFirstMs = int(key = "feel__delete_first_ms", default = io.github.sillypuppyy.dogboard.feel.Timings.DeleteFirstRepeat.inWholeMilliseconds.toInt())
        val deleteCharMs = int(key = "feel__delete_char_ms", default = io.github.sillypuppyy.dogboard.feel.Timings.DeleteCharInterval.inWholeMilliseconds.toInt())
        val deleteWordsAfter = int(key = "feel__delete_words_after", default = io.github.sillypuppyy.dogboard.feel.Timings.DELETE_CHARS_BEFORE_WORDS)
        val deleteWordMs = int(key = "feel__delete_word_ms", default = io.github.sillypuppyy.dogboard.feel.Timings.DeleteWordInterval.inWholeMilliseconds.toInt())
        // DogBoard: Settings › Gestures: hold space, then slide to move the cursor (iOS); on by default
        val trackpadEnabled = boolean(key = "feel__trackpad_enabled", default = true)
        // DogBoard: Settings › Gestures, the Gboard gestures (user 2026-11-19): slide left from delete to delete
        // words; a quick sideways swipe on the space bar moves the cursor without the hold; on by default
        val deleteSlide = boolean(key = "feel__delete_slide", default = true)
        val spaceQuickDrag = boolean(key = "feel__space_quick_drag", default = true)
        // DogBoard: swipe typing and its trail (user 2026-11-17: on by default, draw the trail)
        val swipeEnabled = boolean(key = "feel__swipe_enabled", default = true)
        val swipeTrail = boolean(key = "feel__swipe_trail", default = true)
        // DogBoard: Advanced > Timings: a bar tap this soon after a key is read as a top-row key (BarTapGuard)
        val barTapWindowMs = int(key = "feel__bar_tap_window_ms", default = io.github.sillypuppyy.dogboard.ime.BarTapGuard.WINDOW_MS.toInt())
        val trackpadHoldMs = int(key = "feel__trackpad_hold_ms", default = io.github.sillypuppyy.dogboard.feel.Trackpad.HOLD_MS.toInt())
        val trackpadStepDp = int(key = "feel__trackpad_step_dp", default = io.github.sillypuppyy.dogboard.feel.Trackpad.STEP_DP.toInt())
        // DogBoard: iOS "Enable Caps Lock" (KeyboardCapsLock); off = a second shift tap just toggles
        val capsLockEnabled = boolean(
            key = "typing__caps_lock_enabled",
            default = true,
        )
        // DogBoard: caps-lock double-tap window in ms (SetFeel:82: 150-600, default and iOS 300)
        val capsLockDoubleTapMs = int(
            key = "typing__caps_lock_double_tap_ms",
            default = io.github.sillypuppyy.dogboard.feel.Timings.CapsLockDoubleTap.inWholeMilliseconds.toInt(),
        )
    }

    override fun migrate(entry: PreferenceMigrationEntry): PreferenceMigrationEntry {
        return when (entry.key) {

            // Migrate media prefs to emoji prefs
            // Keep migration rule until: 0.6 dev cycle
            "media__emoji_recently_used" -> {
                val emojiValues = entry.rawValue.split(";")
                val recent = emojiValues.map {
                    dev.patrickgold.florisboard.ime.media.emoji.Emoji(it, "", emptyList())
                }
                val data = EmojiHistory(emptyList(), recent)
                entry.transform(key = "emoji__history_data", rawValue = Json.encodeToString(data))
            }
            "media__emoji_recently_used_max_size" -> {
                entry.transform(key = "emoji__history_recent_max_size")
            }

            // Migrate advanced prefs to other prefs
            // Keep migration rules until: 0.7 dev cycle
            "advanced__settings_theme" -> {
                entry.transform(key = "other__settings_theme")
            }
            "advanced__accent_color" -> {
                entry.transform(key = "other__accent_color")
            }
            "advanced__settings_language" -> {
                entry.transform(key = "other__settings_language")
            }
            "advanced__show_app_icon" -> {
                entry.transform(key = "other__show_app_icon")
            }
            "advanced__incognito_mode" -> {
                entry.transform(key = "suggestion__incognito_mode")
            }
            "advanced__force_incognito_mode_from_dynamic" -> {
                entry.transform(key = "suggestion__force_incognito_mode_from_dynamic")
            }
            // Migrate clipboard suggestion prefs to clipboard
            // Keep migration rules until: 0.7 dev cycle
            "suggestion__clipboard_content_enabled" -> {
                entry.transform(key = "clipboard__suggestion_enabled")
            }
            "suggestion__clipboard_content_timeout" -> {
                entry.transform(key = "clipboard__suggestion_timeout")
            }

            //Migrate one hand mode prefs keep until: 0.7 dev cycle
            "keyboard__one_handed_mode" -> {
                if (entry.rawValue == "OFF") {
                    entry.reset()
                } else {
                    entry.keepAsIs()
                }
            }

            // Migrate quick actions to K3Descriptors and insert missing actions
            // Keep migration rule until: forever
            "smartbar__action_arrangement" -> {
                val arrangement = QuickActionJsonConfig.decodeFromString<QuickActionArrangement>(entry.rawValue)
                var newArrangement = arrangement.migrateToK3Descriptors()
                QuickActionArrangement.Default.forEach { defaultAction ->
                    if (defaultAction !in newArrangement) {
                        newArrangement = newArrangement.copy(
                            dynamicActions = newArrangement.dynamicActions.plus(defaultAction),
                        )
                    }
                }
                val json = QuickActionJsonConfig.encodeToString(newArrangement.distinct())
                entry.transform(rawValue = json)
            }

            // Migrate theme editor fine-tuning
            // Keep migration rule until: 0.6 dev cycle
            "theme__editor_display_colors_as" -> {
                val colorRepresentation = when (entry.rawValue) {
                    "RGBA" -> ColorRepresentation.RGB
                    else -> ColorRepresentation.HEX
                }
                entry.transform(
                    key = "theme__editor_color_representation",
                    rawValue = colorRepresentation.name,
                )
            }

            // Migrate clipboard history pref names
            // Keep migration rules until: 0.7 dev cycle
            "clipboard__sync_to_floris", "clipboard__sync_to_system" -> {
                entry.transform(
                    type = PreferenceType.string(),
                    rawValue = when (entry.rawValue) {
                        "true" -> ClipboardSyncBehavior.ALL_EVENTS.name
                        "false" -> ClipboardSyncBehavior.NO_EVENTS.name
                        else -> entry.rawValue
                    },
                )
            }
            "clipboard__num_history_grid_columns_portrait" -> {
                entry.transform(key = "clipboard__history_num_grid_columns_portrait")
            }
            "clipboard__num_history_grid_columns_landscape" -> {
                entry.transform(key = "clipboard__history_num_grid_columns_landscape")
            }
            "clipboard__clean_up_old" -> {
                entry.transform(key = "clipboard__history_auto_clean_old_enabled")
            }
            "clipboard__clean_up_after" -> {
                entry.transform(key = "clipboard__history_auto_clean_old_after")
            }
            "clipboard__auto_clean_sensitive" -> {
                entry.transform(key = "clipboard__history_auto_clean_sensitive_enabled")
            }
            "clipboard__auto_clean_sensitive_after" -> {
                entry.transform(key = "clipboard__history_auto_clean_sensitive_after")
            }
            "clipboard__limit_history_size" -> {
                entry.transform(key = "clipboard__history_size_limit_enabled")
            }
            "clipboard__max_history_size" -> {
                entry.transform(key = "clipboard__history_size_limit")
            }
            "clipboard__clear_primary_clip_deletes_last_item" -> {
                entry.transform(key = "clipboard__clear_primary_clip_affects_history_if_unpinned")
            }

            // Migrate key spacing rules
            // Keep migration rules until: 0.8 dev cycle
            "keyboard__key_spacing_horizontal" -> {
                if (entry.type.isFloat()) {
                    entry.reset()
                } else {
                    entry.keepAsIs()
                }
            }
            "keyboard__key_spacing_vertical" -> {
                if (entry.type.isFloat()) {
                    entry.reset()
                } else {
                    entry.keepAsIs()
                }
            }

            // Migrate utility key -> fn key
            // Keep migration rules until: 0.8 dev cycle
            "keyboard__utility_key_enabled" -> {
                entry.transform(key = "keyboard__fn_key_enabled")
            }
            "keyboard__utility_key_action" -> {
                entry.transform(
                    key = "keyboard__fn_key_arrangement",
                    rawValue = let {
                        val fnKey = FnKeyArrangement(
                            simpleAction = FnKeyAction(when (entry.rawValue) {
                                "SWITCH_TO_EMOJIS" -> ImeActions.ShowMediaPanel
                                "SWITCH_KEYBOARD_APP" -> ImeActions.SwitchToNextInputMethod
                                else -> ImeActions.SwitchToNextSubtype
                            }),
                            longPressActions = FnKeyArrangement.Default.longPressActions,
                        )
                        Json.encodeToString(fnKey)
                    },
                )
            }

            // Migrate long press delay name
            // Keep migration rules until: 0.8 dev cycle
            "keyboard__long_press_delay" -> {
                entry.transform(key = "keyboard__long_press_timeout")
            }

            // Migrate shift/caps-related prefs to typing
            // Keep migration rules until: 0.8 dev cycle
            "correction__auto_capitalization" -> {
                entry.transform(key = "typing__auto_capitalization")
            }
            "correction__remember_caps_lock_state" -> {
                entry.transform(key = "typing__remember_caps_lock_state")
            }
            "keyboard__capitalization_behavior" -> {
                entry.transform(key = "typing__shift_key_behavior")
            }

            // Default: keep entry
            else -> entry.keepAsIs()
        }
    }
}
