package com.daehyundai.map.feature.exploration.cloud
import com.daehyundai.map.core.model.ExplorationSummary

fun confirmedSummary(user:String,total:Int,snapshot:CloudSnapshot?):ExplorationSummary {
    val grants=snapshot?.grants.orEmpty()
    return ExplorationSummary(user,grants.count { it.kind=="region" },0,grants.count { it.kind=="cell" },total,grants.maxOfOrNull { it.timestamp })
}
