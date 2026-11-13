package com.daehyundai.map.feature.conquest

import com.daehyundai.map.feature.conquest.domain.*

internal fun conquestRoadLabels(catalog: ConquestCatalog): Map<String, String> {
    val groups = linkedMapOf<List<String>, Pair<Int, String>>()
    return catalog.roads.associate { road ->
        val key = listOf(road.fromNode, road.toNode).sorted()
        val group = groups.getOrPut(key) { (groups.size + 1) to road.id }
        road.id to "구간 ${group.first} · ${if (group.second == road.id) "A → B" else "B → A"}"
    }
}

internal fun filterConquestRoads(catalog: ConquestCatalog, labels: Map<String, String>, query: String,
    filter: String, owners: Map<String, String?>, team: String?): List<DirectedRoad> = catalog.roads.filter { road ->
    val search = query.trim()
    (search.isEmpty() || road.name.contains(search, ignoreCase = true) || labels[road.id].orEmpty().contains(search)) &&
        when (filter) {
            "내 팀" -> team != null && owners[road.id] == team
            "중립" -> owners[road.id] == null
            else -> true
        }
}
