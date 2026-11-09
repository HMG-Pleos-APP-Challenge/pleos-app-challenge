package com.daehyundai.map.feature.exploration.data

import com.daehyundai.map.core.data.FileProgressRepository
import com.daehyundai.map.core.model.*

/** Development rules v1. Existing earned records are the recoverable source of truth. */
class ExplorationRewards(private val repository:FileProgressRepository,private val partition:String) {
    suspend fun reconcile(feature:LocalExplorationFeature,userId:String):UserProgress {
        val awards=buildList {
            feature.discoveries(userId).forEach { add(award(userId,100,"region",it.eventId,it.timestampUtcMs)) }
            feature.cellExploration?.let { cells -> cells.earnedRecords(userId).forEach {
                add(award(userId,10,"road-cell","cell:${cells.catalog.version}:${it.cellId}:$userId",it.timestampUtcMs))
            } }
            feature.landmarkCollection?.let { collection -> collection.summary(userId).cards.forEach { card ->
                card.unlockedUtcMs?.let { time -> add(award(userId,200,"landmark","landmark:${collection.catalog.version}:${card.id}:$userId",time)) }
            } }
        }
        repository.awardAllOnce(awards)
        return repository.getProgress(userId)
    }
    private fun award(user:String,amount:Long,reason:String,event:String,time:Long)=
        XpAward("$partition:xp-v1:$event",user,amount,reason,event,time)
}
