package com.daehyundai.map.feature.exploration.tracking

import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VehicleCapability { CHECKING, AVAILABLE, UNAVAILABLE }

enum class VehicleInteraction { UNKNOWN, UNRESTRICTED, RESTRICTED }
fun shouldRecord(automatic:Boolean,state:VehicleInteraction)=!automatic || state==VehicleInteraction.RESTRICTED

/** AAOS safety policy, never GPS-derived parking. Shared, reference-counted main-thread connection. */
object VehicleSafety {
    private val mutable=MutableStateFlow(VehicleInteraction.UNKNOWN)
    val state=mutable.asStateFlow()
    private val capabilityMutable=MutableStateFlow(VehicleCapability.CHECKING)
    val capability=capabilityMutable.asStateFlow()
    private var clients=0
    private var car:Car?=null
    private var manager:CarUxRestrictionsManager?=null
    fun acquire(context:Context):()->Unit {
        clients++
        if(clients==1) try {
            if(context.packageManager.systemSharedLibraryNames?.contains("android.car")!=true) throw ClassNotFoundException("android.car")
            capabilityMutable.value=VehicleCapability.AVAILABLE
            car=Car.createCar(context.applicationContext,null,Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connected,ready ->
                if(clients<=0) return@createCar
                if(!ready) { manager=null;mutable.value=VehicleInteraction.UNKNOWN }
                else try {
                    val ux=connected.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
                    manager=ux
                    fun update(restricted:Boolean) { if(clients>0 && manager===ux) mutable.value=if(restricted) VehicleInteraction.RESTRICTED else VehicleInteraction.UNRESTRICTED }
                    ux.registerListener { update(it.isRequiresDistractionOptimization) }
                    update(ux.currentCarUxRestrictions.isRequiresDistractionOptimization)
                } catch(_:Exception) { mutable.value=VehicleInteraction.UNKNOWN }
            }
        } catch(_:ClassNotFoundException) {
            capabilityMutable.value=VehicleCapability.UNAVAILABLE;mutable.value=VehicleInteraction.UNKNOWN
        } catch(_:LinkageError) {
            capabilityMutable.value=VehicleCapability.UNAVAILABLE;mutable.value=VehicleInteraction.UNKNOWN
        } catch(_:Exception) { mutable.value=VehicleInteraction.UNKNOWN }
        var released=false
        return {
            if(!released) {
                released=true;clients--
                if(clients==0) {
                    runCatching { manager?.unregisterListener() };manager=null
                    runCatching { car?.disconnect() };car=null;mutable.value=VehicleInteraction.UNKNOWN
                }
            }
        }
    }
}
