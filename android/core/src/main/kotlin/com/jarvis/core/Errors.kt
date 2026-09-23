package com.jarvis.core

import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import java.io.IOException

/** Turns a failed request into something Jarvis can say out loud. Most specific first. */
fun spokenError(e: Throwable): String = when (e) {
    is GeminiException -> when {
        e.httpStatus == 429 ->
            "You've used up the free Gemini allowance for now. It refills every minute and resets daily, so try again shortly."
        e.httpStatus in 400..403 && (e.message?.contains("key", ignoreCase = true) == true || e.httpStatus != 400) ->
            "Your Gemini API key was rejected. Check it in Jarvis settings."
        e.httpStatus == 404 -> "That Gemini model wasn't found. Clear the model in Jarvis settings to pick one automatically."
        e.httpStatus >= 500 -> "Google's servers are having trouble right now. Try again shortly."
        else -> "Gemini rejected the request: ${e.message?.take(120)}"
    }
    is UnauthorizedException -> "Your Anthropic API key was rejected. Check it in Jarvis settings."
    is PermissionDeniedException -> "Your Anthropic account isn't allowed to do that. Check your plan and credits in the Anthropic console."
    is NotFoundException -> "That model name wasn't found. Check the model in Jarvis settings."
    is RateLimitException -> "I'm being rate limited. Give me a moment and try again."
    is BadRequestException ->
        if (e.message?.contains("credit", ignoreCase = true) == true) {
            "Your Anthropic account is out of credit. Add some in the Anthropic console."
        } else {
            "The request was rejected: ${e.message?.take(120)}"
        }
    is InternalServerException -> "Anthropic's servers are having trouble right now. Try again shortly."
    is AnthropicIoException, is IOException -> "I can't reach the internet right now."
    else -> "Something went wrong: ${e.message?.take(120) ?: e.javaClass.simpleName}"
}
