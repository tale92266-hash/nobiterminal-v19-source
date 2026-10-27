package com.nobidev.nobicode.theme

/**
 * Runtime registry for plugin-provided themes. PluginManager registers here;
 * Themes.byId consults this registry before its built-in list.
 */
object PluginThemes {

    private val themes = LinkedHashMap<String, EditorTheme>()

    @Synchronized
    fun register(theme: EditorTheme) {
        themes[theme.id] = theme
    }

    @Synchronized
    fun unregister(pluginId: String) {
        themes.remove("plugin:" + pluginId)
    }

    @Synchronized
    fun get(id: String?): EditorTheme? = themes[id]

    @Synchronized
    fun all(): List<EditorTheme> = themes.values.toList()
}
