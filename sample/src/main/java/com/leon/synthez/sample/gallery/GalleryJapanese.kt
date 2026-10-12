package com.leon.synthez.sample.gallery

import com.leon.synthez.core.SZAlignment
import com.leon.synthez.core.SZContext
import com.leon.synthez.core.SZPoint
import com.leon.synthez.core.SZSize
import com.leon.synthez.widgets.SZMenuItem
import com.leon.synthez.widgets.button
import com.leon.synthez.widgets.card
import com.leon.synthez.widgets.checkbox
import com.leon.synthez.widgets.dropdown
import com.leon.synthez.widgets.label
import com.leon.synthez.widgets.list
import com.leon.synthez.widgets.paragraph
import com.leon.synthez.widgets.segmented
import com.leon.synthez.widgets.szOptions
import com.leon.synthez.widgets.theme

private val CAPTION_SIZES = listOf(SZMenuItem("小"), SZMenuItem("中"), SZMenuItem("大"))

// Made-up stations: the page shows kana, kanji and mixed Latin side by side.
private val STATIONS = listOf(
    "さくらラジオ" to "954kHz",
    "みなと放送" to "1134kHz",
    "ひかりFM" to "80.5MHz",
    "Radio SYNTH第1" to "3.925MHz",
    "WaveFM897" to "89.7MHz",
    "やまなみ放送" to "1242kHz",
    "FMこもれび" to "81.3MHz",
    "うみかぜラジオ" to "1422kHz",
)

internal fun SZGallery.drawJapaneseLeft(ctx: SZContext) {
    with(ctx) {
        val theme = theme
        if (japaneseTheme == null) {
            card("JAPANESE") { paragraph("NO CJK FONT LOADED.", theme.textDim) }
            return
        }
        card("放送局 - STATIONS") {
            gap = 4
            val rowHeight = theme.barHeight
            list(STATIONS.size, rowHeight, station, fade = 0, key = "jp-stations", rect = place(SZSize(0, rowHeight * 5 + 2))) { info ->
                val (name, frequency) = STATIONS[info.index]
                text(name, SZPoint(6, 2), theme.font, if (info.selected) theme.paper else theme.text)
                val w = theme.font.measure(frequency)
                text(frequency, SZPoint(bounds.width - w - 8, 2), theme.font, if (info.selected) theme.paper else theme.textDim)
            }?.let {
                station = it
                post("STATION ${it + 1}")
            }
            paragraph("Kana and kanji come from k8x12; Latin stays 5×7, now with lowercase. 漢字・かなと英数字はベースラインを揃えて混植しています。", theme.textDim)
        }
    }
}

internal fun SZGallery.drawJapaneseRight(ctx: SZContext) {
    if (japaneseTheme == null) return
    with(ctx) {
        val theme = theme
        card("番組 - NOW ON AIR") {
            gap = 5
            row(height = theme.barHeight, gap = 4, align = SZAlignment.CENTER) {
                if (button(if (japanesePlaying) "停止" else "再生", on = japanesePlaying, hint = "PLAY / STOP").clicked) {
                    japanesePlaying = !japanesePlaying
                    post(if (japanesePlaying) "PLAYING" else "STOPPED")
                }
                segmented(szOptions("ライブ", "タイムフリー"), japaneseMode, key = "jp-mode", rect = place(SZSize(140, theme.barHeight)))?.let { japaneseMode = it }
            }
            label(STATIONS[station ?: 0].first, theme.accentBright)
            label("25:00-27:00  深夜の音楽番組", theme.textDim)
            paragraph(
                "1967年に始まった深夜放送の流れをくむ番組です。「ラジオの深夜放送」として、半世紀以上にわたって多くのリスナーに親しまれてきました。聴き逃した放送も、タイムフリーでいつでも楽しめます。",
                theme.text,
            )
        }

        card("AI字幕 - CAPTIONS") {
            gap = 3
            row(height = theme.barHeight, gap = 6, align = SZAlignment.CENTER) {
                if (checkbox("字幕を表示", captionsOn, hint = "SHOW CAPTIONS").clicked) captionsOn = !captionsOn
                dropdown(CAPTION_SIZES, captionSize, width = 70, hint = "CAPTION SIZE")?.let { captionSize = it }
            }
            paragraph("こんばんは！今夜も始まりました。ラジオの前のみなさん、最後までお付き合いください。", theme.paper)
            paragraph("（字幕はAIによる自動生成です）", theme.textDim)
        }
    }
}
