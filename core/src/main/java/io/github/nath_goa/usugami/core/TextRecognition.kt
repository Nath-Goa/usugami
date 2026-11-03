package io.github.nath_goa.usugami.core

/** What a recognition model read on one line, and how sure it is (0..1). */
data class OcrText(val text: String, val confidence: Float)

object TextRecognition {
    /**
     * Greedy CTC decoding, as PaddleOCR's CTCLabelDecode: at each of [steps] steps the most likely of
     * [classes] classes ([probabilities], step by step); repeats are merged and blanks dropped.
     * Class 0 is the blank, classes 1..n are [characters] and class n + 1 is a space. The
     * confidence is the mean probability of the characters kept (0 when there are none).
     */
    fun decode(probabilities: FloatArray, steps: Int, classes: Int, characters: List<String>): OcrText {
        require(classes == characters.size + 2) { "The model has $classes classes for ${characters.size} characters" }
        require(steps >= 0 && probabilities.size == steps * classes) { "Bad output size" }
        val text = StringBuilder()
        var sum = 0.0
        var kept = 0
        var previous = -1
        for (step in 0 until steps) {
            val offset = step * classes
            var best = 0
            var bestValue = probabilities[offset]
            for (c in 1 until classes) {
                val value = probabilities[offset + c]
                if (value > bestValue) {
                    best = c
                    bestValue = value
                }
            }
            if (best != 0 && best != previous) {
                text.append(if (best == classes - 1) " " else characters[best - 1])
                sum += bestValue
                kept++
            }
            previous = best
        }
        return OcrText(text.toString(), if (kept == 0) 0f else (sum / kept).toFloat())
    }

    /** Where a line picture is cut: [at] is the first column of the next piece. */
    data class Cut(val at: Int, val betweenWords: Boolean)

    /**
     * Where to cut a line picture that is wider than the recognition model reads well into pieces of
     * at most [maxWidth] columns. [ink] is how much ink each column has. Each cut is made in the
     * widest run of empty columns in the second half of the allowed width (the space between two
     * words, most likely), or failing that in the column with the least ink. A run at least
     * [spaceWidth] wide counts as a space between words, so the pieces' texts are joined with a space.
     */
    fun cuts(ink: IntArray, maxWidth: Int, spaceWidth: Int): List<Cut> {
        require(maxWidth >= 2) { "maxWidth too small" }
        val result = ArrayList<Cut>()
        var start = 0
        while (ink.size - start > maxWidth) {
            val from = start + maxWidth / 2
            val to = start + maxWidth // exclusive: the next piece starts at most here
            var bestRun = 0
            var bestCut = -1
            var run = 0
            for (x in from until to) {
                run = if (ink[x] == 0) run + 1 else 0
                // The middle of the run; of two equally wide runs the later one, for longer pieces.
                if (run >= bestRun && run > 0) {
                    bestRun = run
                    bestCut = x - run / 2
                }
            }
            val cut = if (bestRun > 0) {
                Cut(bestCut.coerceIn(from, to - 1).coerceAtLeast(start + 1), bestRun >= spaceWidth)
            } else {
                var least = from
                for (x in from until to) if (ink[x] < ink[least]) least = x
                Cut(least.coerceAtLeast(start + 1), false)
            }
            result += cut
            start = cut.at
        }
        return result
    }
}
