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

class SupabaseCloud(context:Context,sessionNamespace:String="",
    private val requestOverride:((String,JSONObject,String?,String)->String)?=null):ExplorationCloud,ExistingAccountAuth {
    private val appContext=context.applicationContext
    private val vault=SessionVault(context,sessionNamespace)
    private val endpoint=BuildConfig.SUPABASE_URL.trimEnd('/')
    private val key=BuildConfig.SUPABASE_PUBLISHABLE_KEY
    private fun request(path:String,body:JSONObject,token:String?=null,method:String="POST"):String {
        requestOverride?.let { return it(path,body,token,method) }
        require(endpoint.startsWith("https://"))
        val connection=URL(endpoint+path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod=method;connection.connectTimeout=10000;connection.readTimeout=15000
            connection.instanceFollowRedirects=false;connection.doOutput=method!="GET"
            connection.setRequestProperty("apikey",key)
            connection.setRequestProperty("Content-Type","application/json")
            if(token!=null) connection.setRequestProperty("Authorization","Bearer $token")
            if(method!="GET") connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            check(connection.responseCode in 200..299) { "Cloud HTTP ${connection.responseCode}" }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally { connection.disconnect() }
    }
    private fun session():JSONObject {
        var value=vault.read()
        if(value==null) {
            value=JSONObject(request("/auth/v1/signup",JSONObject().put("data",JSONObject())))
            save(value)
        } else if(value.getLong("expires_at")<=System.currentTimeMillis()/1000+60) {
            value=JSONObject(request("/auth/v1/token?grant_type=refresh_token",JSONObject().put("refresh_token",value.getString("refresh_token"))))
            save(value)
        }
        return value
    }
    private fun save(value:JSONObject,accountReplacement:Boolean=false) {
        val previous=vault.read()
        val previousGeneration=previous?.optLong("_account_generation",0) ?: 0
        val changed=accountReplacement || previous==null ||
            previous.getJSONObject("user").getString("id")!=value.getJSONObject("user").getString("id")
        val generation=if(changed) Math.addExact(previousGeneration,1) else previousGeneration
        value.put("_account_generation",generation)
        value.put("expires_at",System.currentTimeMillis()/1000+value.getLong("expires_in"))
        decodeStoredSession(value) // Validate before replacing the previously usable session.
        vault.write(value)
    }

    private fun storedSession():StoredAuthSession? = vault.read()?.let(::decodeStoredSession)

    private fun decodeStoredSession(value:JSONObject):StoredAuthSession {
        require(value.getString("access_token").isNotBlank() && value.getString("refresh_token").isNotBlank())
        return StoredAuthSession(
            AccountBinding(value.getJSONObject("user").getString("id"),value.optLong("_account_generation",0)),
            value.getLong("expires_at"),value.getString("access_token"),value.getString("refresh_token"))
    }

    private val existingAuth=ExistingAccountBoundary(lock,::storedSession,{ current ->
        val value=JSONObject(request("/auth/v1/token?grant_type=refresh_token",
            JSONObject().put("refresh_token",current.refreshToken)))
        check(value.getJSONObject("user").getString("id")==current.binding.authUserId)
        save(value)
        checkNotNull(storedSession())
    },{ name,body,current ->
        require(name.matches(Regex("[a-z0-9_]+")))
        request("/rest/v1/rpc/$name",JSONObject(body),current.accessToken)
    })

    override fun existingAccount():AccountAuthResult<Unit> = existingAuth.existingAccount()

    override fun rpcForAccount(expected:AccountBinding,name:String,body:String):AccountAuthResult<String> =
        existingAuth.rpcForAccount(expected,name,body)

    override fun <T> withCurrentAccount(expected:AccountBinding,apply:()->T):AccountAuthResult<T> =
        existingAuth.withCurrentAccount(expected,apply)

    fun rpc(name:String,body:JSONObject):String=synchronized(lock) {
        require(name.matches(Regex("[a-z0-9_]+")))
        request("/rest/v1/rpc/$name",body,session().getString("access_token"))
    }
    override fun synchronize(partition:String,samples:String):String=rpc("sync_exploration_v1",
        JSONObject().put("p_partition",partition).put("p_samples",org.json.JSONArray(samples)))
    fun account():JSONObject=synchronized(lock) {
        JSONObject(request("/auth/v1/user",JSONObject(),session().getString("access_token"),"GET"))
    }
    fun linkEmail(email:String):Unit=synchronized(lock) {
        require(email.contains('@') && email.length<=254)
        request("/auth/v1/user",JSONObject().put("email",email.trim()),session().getString("access_token"),"PUT")
        Unit
    }
    fun setPassword(password:String):Unit=synchronized(lock) {
        require(password.length>=8)
        check(!account().isNull("email_confirmed_at")) { "이메일 인증을 먼저 완료해 주세요" }
        request("/auth/v1/user",JSONObject().put("password",password),session().getString("access_token"),"PUT")
        Unit
    }
    fun restore(email:String,password:String,localOwner:String):Unit=synchronized(lock) {
        for(prefix in listOf("pleos","pleos-emulator")) for(kind in listOf("discoveries","cells","landmarks")) {
            val file=java.io.File(appContext.filesDir,"exploration/$prefix-$kind.bin")
            if(file.exists()) java.io.DataInputStream(java.io.FileInputStream(file)).use {
                it.readUTF();it.readUTF();it.readUTF()
                check(it.readInt()==0) { "현재 기기의 기록은 이메일 연결로 보관해 주세요" }
            }
        }
        val cache=CloudCache(appContext)
        try { check(listOf("pleos","pleos-emulator").all { cache.count(localOwner,it)==0 }) { "미전송 기록을 먼저 동기화해 주세요" } }
        finally { cache.close() }
        com.daehyundai.map.feature.conquest.data.ConquestAccountRestore.restoreWhenEmpty(java.io.File(appContext.filesDir,"conquest")) {
            val value=JSONObject(request("/auth/v1/token?grant_type=password",JSONObject().put("email",email.trim()).put("password",password)))
            check(!value.getJSONObject("user").isNull("email_confirmed_at"))
            save(value,accountReplacement=true)
        }
    }
    companion object {
        private val lock=Any()
        val configured get()=BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_PUBLISHABLE_KEY.isNotBlank()
    }
}
