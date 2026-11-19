package coffee.crema.history

import org.junit.rules.ExternalResource

/**
 * #310 A5: the stage-2 flag for one test. Sets [HistoryStore.stage2Enabled]
 * to [enabled] before each test and puts this build's own value
 * ([HistoryStore.STAGE2_IN_BUILD]) back after, so no class leaves the flag
 * changed for another class in the same JVM. A test may still change it
 * mid-way (an older build's session); the rule restores it.
 */
class Stage2Rule(private val enabled: Boolean = HistoryStore.STAGE2_IN_BUILD) : ExternalResource() {
    override fun before() {
        HistoryStore.stage2Enabled = enabled
    }

    override fun after() {
        HistoryStore.stage2Enabled = HistoryStore.STAGE2_IN_BUILD
    }
}
