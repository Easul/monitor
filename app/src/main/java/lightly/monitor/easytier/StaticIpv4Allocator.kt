package lightly.monitor.easytier

import kotlin.random.Random

object StaticIpv4Allocator {
    fun choose(used: Set<String>): String {
        return CANDIDATES.shuffled(Random(System.currentTimeMillis())).firstOrNull { it !in used }
            ?: CANDIDATES.random(Random(System.currentTimeMillis()))
    }

    private val CANDIDATES = (100..150).map { "10.126.126.$it" }
}
