package io.github.peerless2012.ass.media.kt

import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.ui.SubtitleView
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.parser.AssSubtitleParserFactory

fun ExtractorsFactory.withAssMkvSupport(
    parserFactory: AssSubtitleParserFactory,
    assHandler: AssHandler,
): ExtractorsFactory = this

fun DefaultRenderersFactory.withAssSupport(assHandler: AssHandler): DefaultRenderersFactory = this

fun SubtitleView.withAssSupport(assHandler: AssHandler) {}
