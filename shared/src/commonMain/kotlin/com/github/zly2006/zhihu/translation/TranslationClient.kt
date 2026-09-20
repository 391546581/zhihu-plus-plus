/*
 * Zhihu++ - Free & Ad-Free Zhihu client for all platforms.
 * Copyright (C) 2024-2026, zly2006 <i@zly2006.me>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation (version 3 only).
 */

package com.github.zly2006.zhihu.translation

import com.github.zly2006.zhihu.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.parameters
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private const val YOUDAO_ENDPOINT = "https://aidemo.youdao.com/trans"
private const val MYMEMORY_ENDPOINT = "https://api.mymemory.translated.net/get"

enum class TranslationEngine(
    val displayName: String,
) {
    Microsoft("MyMemory 智能翻译 (默认稳定)"),
    OpenAICompatible("AI 大模型 (GLM / Qwen / DeepSeek)"),
    Youdao("有道地道翻译 (AI/NMT)"),
}

enum class TranslationMode(
    val displayName: String,
) {
    Bilingual("中英双语对照"),
    TranslationOnly("纯英文译文"),
}

data class TranslatedArticle(
    val title: String,
    val content: String,
    val originalTitle: String,
    val originalContent: String,
    val mode: TranslationMode,
    val engine: TranslationEngine,
) {
    val displayTitle: String
        get() = when (mode) {
            TranslationMode.Bilingual -> if (title != originalTitle) "$originalTitle\n($title)" else originalTitle
            TranslationMode.TranslationOnly -> title
        }

    val displayContent: String
        get() = content
}

class TranslationClient(
    private val httpClient: HttpClient,
) {
    /**
     * 翻译完整文章（包括标题和全部正文段落，支持中英双语对照与纯译文模式）。
     */
    suspend fun translateArticle(
        title: String,
        html: String,
        engine: TranslationEngine = TranslationEngine.Microsoft,
        mode: TranslationMode = TranslationMode.Bilingual,
        targetLanguage: String? = null,
        openAiConfig: OpenAiTranslationConfig? = null,
    ): TranslatedArticle {
        // 1. 翻译标题（智能判定标题自身语言：中文翻英，英文翻中）
        val translatedTitle = if (title.isNotBlank()) {
            val titleTargetLang = targetLanguage ?: if (containsChinese(title)) "en" else "zh-CN"
            try {
                translateSingle(title.trim(), titleTargetLang, engine, openAiConfig)
            } catch (e: Exception) {
                Log.w("TranslationClient", "Failed to translate title with $engine", e)
                title
            }
        } else {
            ""
        }

        // 2. 翻译正文全量内容
        val translatedContent = translateHtmlContent(html, engine, mode, targetLanguage, openAiConfig)

        return TranslatedArticle(
            title = translatedTitle,
            content = translatedContent,
            originalTitle = title,
            originalContent = html,
            mode = mode,
            engine = engine,
        )
    }

    /**
     * 翻译单条评论内容（自动识别语言，中文翻为英文，外文翻为中文）。
     */
    suspend fun translateCommentText(
        htmlOrText: String,
        engine: TranslationEngine = TranslationEngine.Microsoft,
        targetLanguage: String? = null,
        openAiConfig: OpenAiTranslationConfig? = null,
    ): String {
        val plain = unescapeHtml(stripHtmlTags(htmlOrText)).trim()
        if (plain.isBlank() || isPureSymbolOrNumber(plain)) return plain
        val targetLang = targetLanguage ?: if (containsChinese(plain)) "en" else "zh-CN"
        return try {
            translateSingle(plain, targetLang, engine, openAiConfig)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("TranslationClient", "Comment translation failed", e)
            plain
        }
    }

    private suspend fun translateHtmlContent(
        html: String,
        engine: TranslationEngine,
        mode: TranslationMode,
        targetLanguage: String?,
        openAiConfig: OpenAiTranslationConfig?,
    ): String {
        // 将 HTML 分割为结构块与标签，保留所有的 HTML 标签、公式、代码与图片
        val paragraphRegex = Regex("(<p[^>]*>|<blockquote[^>]*>|<li[^>]*>|<h[1-6][^>]*>)([\\s\\S]*?)(</p>|</blockquote>|</li>|</h[1-6]>)")
        if (!paragraphRegex.containsMatchIn(html)) {
            // 没有标准段落标签的简单 HTML，按通用 Token 翻译
            return translateGenericTokens(html, engine, mode, targetLanguage, openAiConfig)
        }

        val resultBuilder = StringBuilder()
        var lastEnd = 0

        for (match in paragraphRegex.findAll(html)) {
            if (match.range.first > lastEnd) {
                resultBuilder.append(html.substring(lastEnd, match.range.first))
            }
            val openTag = match.groupValues[1]
            val innerContent = match.groupValues[2]
            val closeTag = match.groupValues[3]

            val plainText = unescapeHtml(stripHtmlTags(innerContent)).trim()
            if (plainText.isBlank() || isPureSymbolOrNumber(plainText)) {
                resultBuilder.append(openTag).append(innerContent).append(closeTag)
            } else {
                // 每段自适应语言判定：中文段落翻译为英文，英文/外文段落翻译为中文
                val segmentTargetLang = targetLanguage ?: if (containsChinese(plainText)) "en" else "zh-CN"

                // 非大模型时加入轻微间隔，避免免费端点频率过高
                if (engine != TranslationEngine.OpenAICompatible) {
                    kotlinx.coroutines.delay(100)
                }
                val translatedText = translateSingle(plainText, segmentTargetLang, engine, openAiConfig)

                val isSuccess = translatedText.isNotBlank() && translatedText != plainText
                when (mode) {
                    TranslationMode.TranslationOnly -> {
                        // 替换为译文段落
                        resultBuilder.append(openTag).append(escapeHtml(if (isSuccess) translatedText else plainText)).append(closeTag)
                    }
                    TranslationMode.Bilingual -> {
                        // 中英对照：保留原段落，若成功翻译则附加英文译文
                        resultBuilder.append(openTag).append(innerContent).append(closeTag)
                        if (isSuccess) {
                            resultBuilder.append(
                                """<blockquote style="margin-top: 4px; margin-bottom: 12px; color: #5B6B82; font-style: italic; border-left: 3px solid #3B82F6; padding-left: 8px;">${escapeHtml(translatedText)}</blockquote>""",
                            )
                        }
                    }
                }
            }
            lastEnd = match.range.last + 1
        }

        if (lastEnd < html.length) {
            resultBuilder.append(html.substring(lastEnd))
        }

        return resultBuilder.toString()
    }

    private suspend fun translateGenericTokens(
        html: String,
        engine: TranslationEngine,
        mode: TranslationMode,
        targetLanguage: String?,
        openAiConfig: OpenAiTranslationConfig?,
    ): String {
        val tokens = Regex("<[^>]+>|[^<]+\\z|[^<]+(?=<)").findAll(html).map { it.value }.toList()
        val textIndexes = tokens.indices.filter { index ->
            !tokens[index].startsWith("<") && tokens[index].isNotBlank()
        }
        if (textIndexes.isEmpty()) return html

        val translated = tokens.toMutableList()
        for (index in textIndexes) {
            val sourceText = unescapeHtml(tokens[index].trim())
            if (sourceText.isBlank() || isPureSymbolOrNumber(sourceText)) continue
            val segTargetLang = targetLanguage ?: if (containsChinese(sourceText)) "en" else "zh-CN"
            val trans = try {
                translateSingle(sourceText, segTargetLang, engine, openAiConfig)
            } catch (e: Exception) {
                sourceText
            }
            if (trans.isNotBlank()) {
                if (mode == TranslationMode.Bilingual) {
                    translated[index] = "${tokens[index]} <span style=\"color:#5B6B82; font-style:italic;\">(${escapeHtml(trans)})</span>"
                } else {
                    translated[index] = escapeHtml(trans)
                }
            }
        }
        return translated.joinToString("")
    }

    private suspend fun translateSingle(
        text: String,
        targetLanguage: String,
        engine: TranslationEngine,
        openAiConfig: OpenAiTranslationConfig? = null,
    ): String = try {
        when (engine) {
            TranslationEngine.OpenAICompatible -> {
                if (openAiConfig != null && openAiConfig.apiKey.isNotBlank()) {
                    translateOpenAiCompatible(text, targetLanguage, openAiConfig)
                } else {
                    translateMyMemory(text, targetLanguage)
                }
            }
            TranslationEngine.Youdao -> translateYoudao(text, targetLanguage)
            TranslationEngine.Microsoft -> translateMyMemory(text, targetLanguage)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("TranslationClient", "Engine $engine failed for text snippet: ${text.take(30)}", e)
        // 自动 fallback 容灾：优先降级到 MyMemory 或 Youdao
        try {
            when (engine) {
                TranslationEngine.OpenAICompatible -> translateMyMemory(text, targetLanguage)
                TranslationEngine.Youdao -> translateMyMemory(text, targetLanguage)
                TranslationEngine.Microsoft -> translateYoudao(text, targetLanguage)
            }
        } catch (fallbackEx: CancellationException) {
            throw fallbackEx
        } catch (fallbackEx: Exception) {
            text
        }
    }

    private suspend fun translateOpenAiCompatible(
        text: String,
        targetLanguage: String,
        config: OpenAiTranslationConfig?,
    ): String {
        val cfg = config ?: throw IllegalStateException("OpenAI / LLM translation config is missing")
        val apiKey = cfg.apiKey.trim()
        if (apiKey.isBlank()) {
            throw IllegalStateException("请在设置中配置 API Key 后使用大模型翻译")
        }

        val targetName = if (targetLanguage == "en") "natural and idiomatic English" else "Simplified Chinese (简体中文)"
        val requestJson = buildJsonObject {
            put("model", cfg.model)
            put(
                "messages",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "system")
                            put(
                                "content",
                                "You are a master bilingual translator and editor. Translate the user text into $targetName with natural, engaging phrasing and tone. Preserve formatting and technical terms. Return ONLY the translated string without quotes, explanations, markdown code blocks, or extra notes.",
                            )
                        },
                    )
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put("content", text)
                        },
                    )
                },
            )
            put("temperature", 0.3)
        }.toString()

        val response = httpClient.post(cfg.endpoint) {
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(requestJson)
        }
        val responseBody = response.bodyAsText()
        val root = Json.parseToJsonElement(responseBody).jsonObject
        val content = root["choices"]
            ?.jsonArray
            ?.firstOrNull()
            ?.jsonObject
            ?.get("message")
            ?.jsonObject
            ?.get("content")
            ?.jsonPrimitive
            ?.content

        return content?.trim()?.takeIf { it.isNotBlank() } ?: throw IllegalStateException("LLM 返回空内容: $responseBody")
    }

    private suspend fun translateYoudao(text: String, targetLanguage: String): String {
        val toLang = if (targetLanguage == "zh-CN") "zh-CHS" else targetLanguage
        val response = httpClient.submitForm(
            url = YOUDAO_ENDPOINT,
            formParameters = parameters {
                append("q", text)
                append("from", "Auto")
                append("to", toLang)
            },
        )
        val responseText = response.bodyAsText()
        val root = Json.parseToJsonElement(responseText).jsonObject
        val errorCode = root["errorCode"]?.jsonPrimitive?.content
        if (errorCode != null && errorCode != "0") {
            throw IllegalStateException("Youdao error: $errorCode ($responseText)")
        }
        val translationArr = root["translation"]?.jsonArray
        val translated = translationArr?.firstOrNull()?.jsonPrimitive?.content
        return translated?.takeIf { it.isNotBlank() } ?: throw IllegalStateException("Empty translation from Youdao")
    }

    private suspend fun translateMyMemory(text: String, targetLanguage: String): String {
        val isChinese = containsChinese(text)
        val langPair = if (targetLanguage == "en" || (targetLanguage == "zh-CN" && !isChinese) || isChinese) "zh|en" else "en|zh"
        val response = httpClient.get(MYMEMORY_ENDPOINT) {
            parameter("q", text)
            parameter("langpair", langPair)
        }
        val root = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val responseStatus = root["responseStatus"]?.jsonPrimitive?.content
        if (responseStatus != null && responseStatus != "200") {
            throw IllegalStateException("MyMemory error status: $responseStatus")
        }
        val responseData = root["responseData"]?.jsonObject
        val translated = responseData?.get("translatedText")?.jsonPrimitive?.content
        return translated?.takeIf { it.isNotBlank() } ?: text
    }

    private fun extractAllPlainText(html: String): String = unescapeHtml(html.replace(Regex("<[^>]+>"), " "))

    private fun stripHtmlTags(html: String): String = html.replace(Regex("<[^>]+>"), " ")

    private fun containsChinese(text: String): Boolean {
        var chineseCount = 0
        var totalLetters = 0
        for (ch in text) {
            if (ch.code in 0x4E00..0x9FA5) {
                chineseCount++
            }
            if (ch.isLetterOrDigit()) {
                totalLetters++
            }
        }
        return if (totalLetters == 0) false else (chineseCount.toFloat() / totalLetters) > 0.15f
    }

    private fun isPureSymbolOrNumber(text: String): Boolean = text.all { !it.isLetter() }
}

private fun escapeHtml(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&#39;")

private fun unescapeHtml(text: String): String = text
    .replace("&amp;", "&")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&#39;", "'")
    .replace("&nbsp;", " ")
