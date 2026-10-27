package com.nobidev.nobicode.theme

object Themes {

    val TOKYO = EditorTheme(
        id = "tokyo_night", displayName = "Tokyo Night", isDark = true,
        background = 0xFF1A1B26.toInt(), surface = 0xFF16161E.toInt(),
        text = 0xFFA9B1D6.toInt(), caret = 0xFF7AA2F7.toInt(),
        selection = 0xFF33467C.toInt(), currentLine = 0xFF292E42.toInt(),
        gutterBg = 0xFF16161E.toInt(), gutterText = 0xFF3B4261.toInt(), gutterActive = 0xFFA9B1D6.toInt(),
        keyword = 0xFFBB9AF7.toInt(), string = 0xFF9ECE6A.toInt(),
        comment = 0xFF565F89.toInt(), number = 0xFFFF9E64.toInt(),
        function = 0xFF7AA2F7.toInt(), type = 0xFF2AC3DE.toInt(),
        annotation = 0xFFE0AF68.toInt(),
    )

    val DRACULA = EditorTheme(
        id = "dracula", displayName = "Dracula", isDark = true,
        background = 0xFF282A36.toInt(), surface = 0xFF21222C.toInt(),
        text = 0xFFF8F8F2.toInt(), caret = 0xFFF8F8F0.toInt(),
        selection = 0xFF44475A.toInt(), currentLine = 0xFF343746.toInt(),
        gutterBg = 0xFF21222C.toInt(), gutterText = 0xFF6272A4.toInt(), gutterActive = 0xFFF8F8F2.toInt(),
        keyword = 0xFFFF79C6.toInt(), string = 0xFFF1FA8C.toInt(),
        comment = 0xFF6272A4.toInt(), number = 0xFFBD93F9.toInt(),
        function = 0xFF50FA7B.toInt(), type = 0xFF8BE9FD.toInt(),
        annotation = 0xFFFFB86C.toInt(),
    )

    // w20: Nobi tokens for the default theme — gutter/selection/current-line use
    // app cyan #22D3EE (alpha variants of the existing token, same idiom as
    // applyTheme's alpha masks); other user-pickable themes untouched.
    val ONE_DARK = EditorTheme(
        id = "one_dark", displayName = "One Dark Pro", isDark = true,
        background = 0xFF12131B.toInt(), surface = 0xFF1F2230.toInt(),
        text = 0xFFE6E9F5.toInt(), caret = 0xFF8B7CF6.toInt(),
        selection = 0x3322D3EE.toInt(), currentLine = 0x1422D3EE.toInt(),
        gutterBg = 0xFF0D0E14.toInt(), gutterText = 0xB322D3EE.toInt(), gutterActive = 0xFF22D3EE.toInt(),
        keyword = 0xFFC678DD.toInt(), string = 0xFF98C379.toInt(),
        comment = 0xFF5C6370.toInt(), number = 0xFFD19A66.toInt(),
        function = 0xFF61AFEF.toInt(), type = 0xFFE5C07B.toInt(),
        annotation = 0xFFE5C07B.toInt(),
    )

    val NORD = EditorTheme(
        id = "nord", displayName = "Nord", isDark = true,
        background = 0xFF2E3440.toInt(), surface = 0xFF292E39.toInt(),
        text = 0xFFD8DEE9.toInt(), caret = 0xFF88C0D0.toInt(),
        selection = 0xFF434C5E.toInt(), currentLine = 0xFF3B4252.toInt(),
        gutterBg = 0xFF292E39.toInt(), gutterText = 0xFF4C566A.toInt(), gutterActive = 0xFFD8DEE9.toInt(),
        keyword = 0xFF81A1C1.toInt(), string = 0xFFA3BE8C.toInt(),
        comment = 0xFF616E88.toInt(), number = 0xFFB48EAD.toInt(),
        function = 0xFF88C0D0.toInt(), type = 0xFF8FBCBB.toInt(),
        annotation = 0xFFEBCB8B.toInt(),
    )

    val AMOLED = EditorTheme(
        id = "amoled", displayName = "AMOLED Black", isDark = true,
        background = 0xFF000000.toInt(), surface = 0xFF0A0A0A.toInt(),
        text = 0xFFE0E0E0.toInt(), caret = 0xFFBB86FC.toInt(),
        selection = 0xFF2A2A2A.toInt(), currentLine = 0xFF111111.toInt(),
        gutterBg = 0xFF0A0A0A.toInt(), gutterText = 0xFF444444.toInt(), gutterActive = 0xFFE0E0E0.toInt(),
        keyword = 0xFFBB86FC.toInt(), string = 0xFF6BCC77.toInt(),
        comment = 0xFF555555.toInt(), number = 0xFFFFAB70.toInt(),
        function = 0xFF82AAFF.toInt(), type = 0xFF7FDBFF.toInt(),
        annotation = 0xFFFFCB6B.toInt(),
    )

    val SOLARIZED = EditorTheme(
        id = "solarized_light", displayName = "Solarized Light", isDark = false,
        background = 0xFFFDF6E3.toInt(), surface = 0xFFEEE8D5.toInt(),
        text = 0xFF657B83.toInt(), caret = 0xFF268BD2.toInt(),
        selection = 0xFFE5DDBE.toInt(), currentLine = 0xFFF5EFD6.toInt(),
        gutterBg = 0xFFEEE8D5.toInt(), gutterText = 0xFF93A1A1.toInt(), gutterActive = 0xFF586E75.toInt(),
        keyword = 0xFF859900.toInt(), string = 0xFF2AA198.toInt(),
        comment = 0xFF93A1A1.toInt(), number = 0xFFD33682.toInt(),
        function = 0xFF268BD2.toInt(), type = 0xFFB58900.toInt(),
        annotation = 0xFFCB4B16.toInt(),
    )

    val ALL = listOf(TOKYO, DRACULA, ONE_DARK, NORD, AMOLED, SOLARIZED)
    val DEFAULT = TOKYO

    fun byId(id: String?): EditorTheme =
        PluginThemes.get(id) ?: ALL.firstOrNull { it.id == id } ?: DEFAULT

    /** Built-ins plus enabled plugin themes, for pickers. */
    fun allWithPlugins(): List<EditorTheme> = ALL + PluginThemes.all()
}
