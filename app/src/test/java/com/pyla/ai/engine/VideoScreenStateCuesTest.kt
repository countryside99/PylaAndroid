package com.pyla.ai.engine
import org.junit.Assert.*
import org.junit.Test

class VideoScreenStateCuesTest {
    private fun state(vararg s:String)=VideoScreenStateCues.classify(s.toSet())
    @Test fun noCueOrSingleCueCannotAuthorizeAScreen(){
        assertNull(state())
        for(c in VideoScreenStateCues.regions.keys)assertNull(state(c))
    }
    @Test fun resultNavigationNeedsRankTrophyAndBothActions(){
        val cues=setOf("rank","result_trophy","result_exit","play_again")
        assertEquals("end_ranked",VideoScreenStateCues.classify(cues))
        for(c in cues)assertNotEquals("end_ranked",VideoScreenStateCues.classify(cues-c))
    }
    @Test fun unfinishedResultIsObservationOnly(){
        assertEquals("result_preview",state("rank","result_trophy"))
        assertEquals("result_preview",state("rank","result_trophy","play_again"))
    }
    @Test fun matchmakingRequiresHeadingAndExit(){
        assertEquals("match_making",state("players_found","search_exit"))
        assertEquals("match_making",state("players_found","search_exit_disabled"))
    }
    @Test fun deathAndIntroAreNeverGameplay(){
        assertEquals("defeated",state("defeated","brawlers_left"))
        assertEquals("match_start",state("showdown","sole_survivor"))
        assertEquals("match_start",state("every_brawler","starts_in"))
        assertEquals("match_start",state("showdown","sole_survivor","every_brawler","starts_in"))
    }
    @Test fun conflictingScreenFamiliesWaitRatherThanChooseAnAction(){
        assertEquals("transition",state("players_found","search_exit","rank","result_trophy","play_again","result_exit"))
    }
    @Test fun noSubsetCanEverGrantMatch(){
        val names=VideoScreenStateCues.regions.keys.toList()
        for(mask in 0 until (1 shl names.size)){
            val matches=names.filterIndexed{i,_->mask and (1 shl i)!=0}.toSet()
            assertNotEquals("match",VideoScreenStateCues.classify(matches))
        }
    }
    @Test fun newWaitScreensAreKnownMenuDenials(){
        for(s in listOf("match_start","defeated","result_preview","end_ranked","transition")){
            assertNotEquals(RuntimeState.UNKNOWN,RuntimeState.fromClassifier(s))
            val authority=GameStateAuthority();authority.gameplay(true,100,100)
            assertNotEquals("match",authority.menu(s,101,101).state)
        }
    }
    @org.junit.Test fun gainedTrophiesHeadingCountsAsRank() {
        org.junit.Assert.assertEquals("end_ranked",
            VideoScreenStateCues.classify(setOf("rank_win", "result_trophy", "result_exit", "play_again")))
        org.junit.Assert.assertEquals("result_preview", VideoScreenStateCues.classify(setOf("rank_win", "result_trophy")))
        org.junit.Assert.assertNull(VideoScreenStateCues.classify(setOf("rank_win")))
    }
}
