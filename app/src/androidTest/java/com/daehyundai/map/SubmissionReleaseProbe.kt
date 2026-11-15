package com.daehyundai.map

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File

/** Exercises the signed submission APK in a disposable, offline emulator. */
class SubmissionReleaseProbe:Instrumentation() {
    override fun onCreate(arguments:Bundle?) { super.onCreate(arguments);start() }
    private fun nodes(root:AccessibilityNodeInfo?):List<AccessibilityNodeInfo> {
        if(root==null) return emptyList()
        return listOf(root)+(0 until root.childCount).flatMap { nodes(root.getChild(it)) }
    }
    private fun waitFor(text:String):AccessibilityNodeInfo {
        val deadline=SystemClock.uptimeMillis()+20_000
        while(SystemClock.uptimeMillis()<deadline) {
            val found=nodes(uiAutomation.rootInActiveWindow).firstOrNull { it.text?.toString()==text }
            if(found!=null) return found
            SystemClock.sleep(200)
        }
        error("Visible text missing: "+text)
    }
    private fun click(text:String) {
        var node:AccessibilityNodeInfo?=waitFor(text)
        while(node!=null && !node.isClickable) node=node.parent
        check(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true) { "Cannot select "+text }
        waitForIdleSync()
    }
    override fun onStart() {
        val result=runCatching {
            check(targetContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE==0)
            startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            click("새 탐험 시작")
            waitFor("오늘은 어디를 탐험할까요?")
            click("탐험하기")
            waitFor("남양읍 탐험 열기")
            click("남양읍 탐험 열기")
            waitFor("남양읍에 처음 진입하면 지역 도감이 열려요.")
            click("‹ 뒤로");click("‹ 뒤로")
            click("점령 보기")
            waitFor("남양읍 도로 점령")
            click("‹ 뒤로")
            click("내 프로필")
            waitFor("명소 도감")
            val screen=File(targetContext.cacheDir,"submission-release-profile.png")
            screen.outputStream().use { output -> check(uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,output)) }
            "PASS: non-debuggable release installed; first launch, home, exploration lock, conquest and profile navigated; screenshot="+screen.absolutePath
        }
        finish(if(result.isSuccess) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString("stream",result.getOrElse {"FAIL: "+it.message}) })
    }
}
