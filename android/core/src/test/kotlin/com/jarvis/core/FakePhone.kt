package com.jarvis.core

import java.time.LocalDateTime

class FakePhone : PhoneActions {
    val calls = mutableListOf<String>()
    override fun openUrl(url: String, label: String) = "opened $url".also { calls += "url $url" }
    override fun dial(number: String) = "dialing $number".also { calls += "dial $number" }
    override fun composeSms(number: String, message: String) = "sms".also { calls += "sms $number $message" }
    override fun openApp(name: String) = "opening $name".also { calls += "app $name" }
    override fun findContact(name: String) = "Mom: +923001234567".also { calls += "contact $name" }
    override fun setAlarm(hour: Int, minute: Int, label: String) = "alarm".also { calls += "alarm $hour:$minute $label" }
    override fun setTimer(seconds: Int, label: String) = "timer".also { calls += "timer $seconds" }
    override fun setFlashlight(on: Boolean) = "torch".also { calls += "torch $on" }
    override fun setVolume(percent: Int) = "vol".also { calls += "volume $percent" }
    override fun batteryStatus() = "80%"
    override fun scheduleReminder(id: Long, text: String, at: LocalDateTime) { calls += "remind $id $text $at" }
}
