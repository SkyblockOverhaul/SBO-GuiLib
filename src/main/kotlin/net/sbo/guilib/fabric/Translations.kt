package net.sbo.guilib.fabric

import net.minecraft.client.Minecraft
import net.minecraft.client.resources.language.I18n
import net.minecraft.locale.Language
import net.sbo.guilib.core.dom.createContext
import net.sbo.guilib.core.dsl.ComponentScope

/**
 * Translates keys from the game's language files (`assets/<modid>/lang/<lang>.json`), like `I18n.get`:
 *
 * ```kotlin
 * val t = useTranslation()
 * h1 { +t("mymod.gui.title") }
 * p { +t("mymod.gui.kills", kills) }   // "Kills: %s"
 * ```
 * Missing keys return the key itself (like Minecraft).
 */
class Translator internal constructor(
    /** The language code, e.g. `en_us`. */
    val language: String,
    private val lang: Language,
) {
    operator fun invoke(key: String, vararg args: Any): String = I18n.get(key, *args)

    /** True if the current language (or the English fallback) defines [key]. */
    fun has(key: String): Boolean = lang.has(key)

    companion object {
        private var cached: Translator? = null

        /**
         * The translator for the game's current language. A new instance after every language change or resource
         * reload, so components using it re-render.
         */
        fun current(): Translator {
            val lang = Language.getInstance()
            val code = Minecraft.getInstance().options.languageCode
            val c = cached
            if (c != null && c.lang === lang && c.language == code) return c
            return Translator(code, lang).also { cached = it }
        }
    }
}

internal val TranslatorContext = createContext<Translator?>(null, "Translator")

/**
 * The game's translations. The component re-renders when the language changes or resource packs reload, so every
 * `t("key")` shows the new language right away.
 */
fun ComponentScope.useTranslation(): Translator = useContext(TranslatorContext) ?: Translator.current()
