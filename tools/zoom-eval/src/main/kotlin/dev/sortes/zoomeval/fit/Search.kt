package dev.sortes.zoomeval.fit

import dev.sortes.app.zoom.CropConfig
import dev.sortes.zoomeval.CONFIG_WEIGHT_NAMES
import dev.sortes.zoomeval.weightValues
import dev.sortes.zoomeval.with
import java.math.BigDecimal
import java.math.MathContext

/**
 * The weights a local search moves by default: the crop model's that matter for
 * [CropConfig.DEFAULT] and the text model's. Left out: alpha (the unit everything else is
 * measured in), epsilon and allowance (off: blocks do their job), maxWindows.
 */
val SEARCH_WEIGHTS = listOf(
    "beta", "gamma", "delta", "delta2", "kappa", "blockGap", "splitInk", "zeta", "autoMargin",
    "comfortable", "power", "floor", "elements", "lines",
)

/** Integer weights: their values are whole numbers. */
private val INTEGER_WEIGHTS = setOf("blockGap", "maxWindows")

/**
 * The values a local search tries for weight [name] around its current [value], not including
 * [value]: a step or two each way, on a scale that suits the weight (ratios for weights that act
 * multiplicatively, pixels for the comfortable glyph height, scale steps for the zoom margin, ±1
 * for integers). Weights that can be off (0) also try 0, and from 0 try a few small values.
 */
fun neighbours(name: String, value: Double): List<Double> {
    require(name in CONFIG_WEIGHT_NAMES) { "Unknown weight $name" }
    fun ratios(vararg r: Double) = r.map { value * it }
    val candidates = when (name) {
        "comfortable" -> listOf(value - 2, value - 1, value + 1, value + 2)
        "autoMargin" -> listOf(value - 0.01, value - 0.005, value + 0.005, value + 0.01)
        "blockGap", "maxWindows" -> listOf(value - 1, value + 1)
        "floor" -> listOf(0.0, value / 2, value * 2, value * 3)
        "allowance" -> listOf(0.0, value / 2, value * 2)
        else -> if (value == 0.0) {
            when (name) {
                "epsilon" -> listOf(30.0, 100.0, 300.0)
                "delta", "delta2" -> listOf(0.002, 0.005, 0.01, 0.02)
                "kappa" -> listOf(0.05, 0.1, 0.2)
                "elements" -> listOf(0.003, 0.01, 0.03)
                "lines" -> listOf(0.03, 0.1, 0.3)
                else -> emptyList()
            }
        } else {
            ratios(0.5, 0.75, 1.33, 2.0) + if (name in OPTIONAL) listOf(0.0) else emptyList()
        }
    }
    return candidates.map { tidy(it) }.filter { it != value && valid(name, it) }.distinct().sorted()
}

/** Weights a search may also turn off. */
private val OPTIONAL = setOf("delta", "delta2", "epsilon", "kappa", "elements", "lines")

private fun valid(name: String, v: Double): Boolean = when (name) {
    "blockGap" -> v >= 0
    "maxWindows" -> v >= 1
    "floor" -> v in 0.0..1.0
    "autoMargin" -> v in -0.5..0.5
    "comfortable", "power", "splitInk" -> v > 0
    else -> v >= 0
}

/** [v] to 4 significant digits, so grids don't drift into 0.30000000000000004. */
private fun tidy(v: Double): Double = if (v == 0.0) 0.0 else BigDecimal(v).round(MathContext(4)).toDouble()

/** [config] with weight [name] at [value]. */
fun CropConfig.withWeight(name: String, value: Double): CropConfig =
    with(mapOf(name to if (name in INTEGER_WEIGHTS) value.toLong().toString() else value.toString()))

/**
 * One coordinate of a local search: one weight, or two searched jointly on their grids
 * (`comfortable+power`).
 */
data class Coordinate(val names: List<String>) {
    init {
        require(names.isNotEmpty() && names.all { it in CONFIG_WEIGHT_NAMES }) { "Bad coordinate $names" }
    }

    override fun toString(): String = names.joinToString("+")

    /** Every config this coordinate tries from [config]: each weight at its value or a neighbour, not all at their values. */
    fun candidates(config: CropConfig): List<CropConfig> {
        val values = config.weightValues()
        var configs = listOf(config)
        for (name in names) {
            val now = values.getValue(name)
            configs = configs.flatMap { c -> listOf(c) + neighbours(name, now).map { c.withWeight(name, it) } }
        }
        return configs.drop(1)
    }

    companion object {
        fun parse(spec: String) = Coordinate(spec.split('+').map { it.trim() })
    }
}

/** One coordinate's step: what was tried from [incumbent], and which the search kept. */
class Step(
    val sweep: Int,
    val coordinate: Coordinate,
    val incumbent: Trial,
    val candidates: List<Trial>,
    /** The trial kept: [incumbent] if no candidate beat it by the search's minimum gain. */
    val kept: Trial,
) {
    val moved: Boolean get() = kept !== incumbent
}

/** A finished local search. */
class Descent(val start: Trial, val steps: List<Step>, val train: List<String>, val minGain: Double) {
    val best: Trial get() = steps.lastOrNull()?.kept ?: start

    /** Every trial scored, in order, the start first. */
    val tried: List<Trial> get() = listOf(start) + steps.flatMap { it.candidates }

    /**
     * The papers whose removal from the training papers would change a step's decision (another
     * candidate kept, or none): moves that hang on one paper. Steps are judged as they were taken,
     * from the same incumbent, so this is a per-step check, not a re-run of the search.
     */
    fun fragile(step: Step): List<String> = train.filter { paper ->
        decide(step.incumbent, step.candidates, train - paper, minGain) !== step.kept
    }
}

/** The units of [trial] on [papers]. */
fun Trial.unitsOn(papers: Collection<String>): Double = scores.filter { it.paper in papers }.sumOf { it.units }

/** Which of [incumbent] and [candidates] a search keeps, judged on [train]: the best candidate if it gains more than [minGain]. */
fun decide(incumbent: Trial, candidates: List<Trial>, train: Collection<String>, minGain: Double): Trial {
    val best = candidates.minByOrNull { it.unitsOn(train) } ?: return incumbent
    return if (incumbent.unitsOn(train) - best.unitsOn(train) > minGain) best else incumbent
}

/**
 * Coordinate descent from [start]: for each of [coordinates] in turn, scores its [Coordinate.candidates]
 * around the current best config and keeps the best if it lowers the units on the [train] papers by
 * more than [minGain]. Repeats for up to [sweeps] sweeps, stopping early after one that keeps nothing.
 * [evaluate] scores a config on every paper (the training papers and any held out).
 */
suspend fun coordinateDescent(
    start: Trial,
    coordinates: List<Coordinate>,
    train: List<String>,
    sweeps: Int,
    minGain: Double,
    evaluate: suspend (CropConfig) -> Trial,
    onStep: (Step) -> Unit = {},
): Descent {
    require(minGain >= 0) { "minGain must not be negative" }
    val steps = mutableListOf<Step>()
    var best = start
    for (sweep in 1..sweeps) {
        var moved = false
        for (coordinate in coordinates) {
            val candidates = coordinate.candidates(best.config).map { evaluate(it) }
            val kept = decide(best, candidates, train, minGain)
            val step = Step(sweep, coordinate, best, candidates, kept)
            steps += step
            onStep(step)
            if (step.moved) {
                moved = true
                best = kept
            }
        }
        if (!moved) break
    }
    return Descent(start, steps, train, minGain)
}
