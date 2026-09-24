package ibytsync.core.audio

/**
 * Retune (440Hz to 432Hz pitch shift) is cut for v1.
 *
 * Decision 2026-09-08: no engine exists (bundled ffmpeg lacks the rubberband
 * filter), no scientific case for the feature, and it adds scope to every
 * batch. All downloads stay at source pitch (440Hz standard).
 *
 * If this repo ever gets popular and someone wants it back, they open a PR
 * with a real engine implementation behind this stub — not a toggle to nowhere.
 */
object RetuneCut {
    const val NOTE = "Retune cut for v1: source pitch only. See ticket 09."
}
