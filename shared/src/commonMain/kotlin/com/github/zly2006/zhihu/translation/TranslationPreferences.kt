/*
 * Zhihu++ - Free & Ad-Free Zhihu client for all platforms.
 * Copyright (C) 2024-2026, zly2006 <i@zly2006.me>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation (version 3 only).
 */

package com.github.zly2006.zhihu.translation

import com.github.zly2006.zhihu.platform.SettingsStore

const val PREF_TRANSLATION_AUTO_ARTICLE = "translation_auto_article"
const val PREF_TRANSLATION_AUTO_COMMENT = "translation_auto_comment"
const val PREF_TRANSLATION_GLOBAL_MODE = "translation_global_mode"
const val PREF_TRANSLATION_GLOBAL_ENGINE = "translation_global_engine"
const val PREF_TRANSLATION_OPENAI_ENDPOINT = "translation_openai_endpoint"
const val PREF_TRANSLATION_OPENAI_API_KEY = "translation_openai_api_key"
const val PREF_TRANSLATION_OPENAI_MODEL = "translation_openai_model"

const val DEFAULT_OPENAI_ENDPOINT = "https://open.bigmodel.cn/api/paas/v4/chat/completions"
const val DEFAULT_OPENAI_MODEL = "glm-4-flash"

data class OpenAiTranslationConfig(
    val endpoint: String,
    val apiKey: String,
    val model: String,
)

fun loadOpenAiTranslationConfig(settings: SettingsStore): OpenAiTranslationConfig {
    return OpenAiTranslationConfig(
        endpoint = settings.getString(PREF_TRANSLATION_OPENAI_ENDPOINT, DEFAULT_OPENAI_ENDPOINT).ifBlank { DEFAULT_OPENAI_ENDPOINT },
        apiKey = settings.getString(PREF_TRANSLATION_OPENAI_API_KEY, ""),
        model = settings.getString(PREF_TRANSLATION_OPENAI_MODEL, DEFAULT_OPENAI_MODEL).ifBlank { DEFAULT_OPENAI_MODEL },
    )
}

fun saveOpenAiTranslationConfig(settings: SettingsStore, config: OpenAiTranslationConfig) {
    settings.putString(PREF_TRANSLATION_OPENAI_ENDPOINT, config.endpoint)
    settings.putString(PREF_TRANSLATION_OPENAI_API_KEY, config.apiKey)
    settings.putString(PREF_TRANSLATION_OPENAI_MODEL, config.model)
}

fun loadGlobalTranslationMode(settings: SettingsStore): TranslationMode {
    val raw = settings.getString(PREF_TRANSLATION_GLOBAL_MODE, TranslationMode.Bilingual.name)
    return runCatching { TranslationMode.valueOf(raw) }.getOrDefault(TranslationMode.Bilingual)
}

fun saveGlobalTranslationMode(settings: SettingsStore, mode: TranslationMode) {
    settings.putString(PREF_TRANSLATION_GLOBAL_MODE, mode.name)
}

fun loadGlobalTranslationEngine(settings: SettingsStore): TranslationEngine {
    val raw = settings.getString(PREF_TRANSLATION_GLOBAL_ENGINE, TranslationEngine.Microsoft.name)
    return runCatching { TranslationEngine.valueOf(raw) }.getOrDefault(TranslationEngine.Microsoft)
}

fun saveGlobalTranslationEngine(settings: SettingsStore, engine: TranslationEngine) {
    settings.putString(PREF_TRANSLATION_GLOBAL_ENGINE, engine.name)
}
