package com.daehyundai.map.feature.conquest.cloud

import com.daehyundai.map.core.model.*
import com.daehyundai.map.feature.conquest.domain.*

internal data class ConquestCapturedSubmission(val event:RoadVisited,val request:ConquestSubmission?)

/** Create only after binding an account and confirming its server membership; no SDK subscription here. */
internal class ConquestServerCapture(private val installationId:String,catalog:ConquestCatalog,
    private val selectedUtcMs:Long,maxSamples:Int=2048) {
    private val detector=DirectedRoadDetector(catalog,LocationSourceType.PLEOS,captureProof=true,maxProofSamples=maxSamples)

    fun reset()=detector.reset(installationId)

    /** No disk/auth/HTTP. Pass completed results to enqueue on IO outside sample/record locks. */
    fun onSample(sample:LocationSample):List<ConquestCapturedSubmission> {
        if(sample.userId!=installationId || sample.source!=LocationSourceType.PLEOS || sample.timestampUtcMs<selectedUtcMs) {
            reset();return emptyList()
        }
        return detector.onSampleWithProof(sample).map { captured ->
            val request=captured.samples?.let { proof ->
                try {ConquestSubmission.create(captured.event,proof)} catch(_:IllegalArgumentException) {null}
            }
            // null is an explicit missing/oversized proof, never a server-recognized contribution.
            ConquestCapturedSubmission(captured.event,request)
        }
    }
}
