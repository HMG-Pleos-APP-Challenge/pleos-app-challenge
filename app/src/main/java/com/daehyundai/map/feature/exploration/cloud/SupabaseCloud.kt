package com.daehyundai.map.feature.exploration.cloud

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.daehyundai.map.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Tokens are encrypted with an app-owned Android Keystore key; never logged or put in BuildConfig. */
private class SessionVault(context:Context,namespace:String="") {
    private val prefs=context.getSharedPreferences("cloud-session-${BuildConfig.SUPABASE_URL.hashCode()}"+if(namespace.isEmpty()) "" else "-${namespace.hashCode()}",Context.MODE_PRIVATE)
    private fun key():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("exploration-auth",null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("exploration-auth",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun read():JSONObject? {
        val raw=prefs.getString("session",null) ?: return null
        val parts=raw.split(":");val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)))
        return JSONObject(String(cipher.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)),Charsets.UTF_8))
    }
    fun write(session:JSONObject) {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key())
        val raw=Base64.encodeToString(cipher.iv,Base64.NO_WRAP)+":"+Base64.encodeToString(cipher.doFinal(session.toString().toByteArray(Charsets.UTF_8)),Base64.NO_WRAP)
        check(prefs.edit().putString("session",raw).commit())
    }
}

class SupabaseCloud(context:Context,sessionNamespace:String=""):ExplorationCloud {
    private val vault=SessionVault(context,sessionNamespace)
    private val endpoint=BuildConfig.SUPABASE_URL.trimEnd('/')
    private val key=BuildConfig.SUPABASE_PUBLISHABLE_KEY
    private fun request(path:String,body:JSONObject,token:String?=null):String {
        require(endpoint.startsWith("https://"))
        val connection=URL(endpoint+path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod="POST";connection.connectTimeout=10000;connection.readTimeout=15000
            connection.instanceFollowRedirects=false;connection.doOutput=true
            connection.setRequestProperty("apikey",key)
            connection.setRequestProperty("Content-Type","application/json")
            if(token!=null) connection.setRequestProperty("Authorization","Bearer $token")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            check(connection.responseCode in 200..299) { "Cloud HTTP ${connection.responseCode}" }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally { connection.disconnect() }
    }
    override fun synchronize(partition:String,samples:String):String = synchronized(lock) {
        var session=vault.read()
        if(session==null) {
            session=JSONObject(request("/auth/v1/signup",JSONObject().put("data",JSONObject())))
            session.put("expires_at",System.currentTimeMillis()/1000+session.getLong("expires_in"))
            vault.write(session)
        } else if(session.getLong("expires_at")<=System.currentTimeMillis()/1000+60) {
            // A failed refresh must not create a new anonymous account or reassign pending records.
            session=JSONObject(request("/auth/v1/token?grant_type=refresh_token",JSONObject().put("refresh_token",session.getString("refresh_token"))))
            session.put("expires_at",System.currentTimeMillis()/1000+session.getLong("expires_in"))
            vault.write(session)
        }
        request("/rest/v1/rpc/sync_exploration_v1",JSONObject().put("p_partition",partition)
            .put("p_samples",org.json.JSONArray(samples)),session.getString("access_token"))
    }
    companion object {
        private val lock=Any()
        val configured get()=BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_PUBLISHABLE_KEY.isNotBlank()
    }
}
