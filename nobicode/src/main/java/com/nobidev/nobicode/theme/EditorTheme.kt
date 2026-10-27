package com.nobidev.nobicode.theme

import androidx.annotation.ColorInt

data class EditorTheme(
    val id: String,
    val displayName: String,
    val isDark: Boolean,
    @ColorInt val background: Int,
    @ColorInt val surface: Int,
    @ColorInt val text: Int,
    @ColorInt val caret: Int,
    @ColorInt val selection: Int,
    @ColorInt val currentLine: Int,
    @ColorInt val gutterBg: Int,
    @ColorInt val gutterText: Int,
    @ColorInt val gutterActive: Int,
    @ColorInt val keyword: Int,
    @ColorInt val string: Int,
    @ColorInt val comment: Int,
    @ColorInt val number: Int,
    @ColorInt val function: Int,
    @ColorInt val type: Int,
    @ColorInt val annotation: Int,
)
