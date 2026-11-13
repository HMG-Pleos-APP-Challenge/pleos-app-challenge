package com.daehyundai.map.feature.profile.data

import android.content.SharedPreferences

/** Persist the pending choice before creating a new installation identity. */
class AccountStartChoice(private val preferences:SharedPreferences) {
    fun prepare(existingInstallation:Boolean):Boolean {
        if(!preferences.contains("chosen")) {
            check(preferences.edit().putBoolean("chosen",existingInstallation).commit())
        }
        return preferences.getBoolean("chosen",false)
    }

    fun complete():Boolean = preferences.edit().putBoolean("chosen",true).commit()
}
