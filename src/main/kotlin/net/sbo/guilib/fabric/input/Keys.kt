package net.sbo.guilib.fabric.input

import net.sbo.guilib.core.event.Modifiers
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.Minecraft
import org.lwjgl.glfw.GLFW

/** Maps GLFW key codes to DOM `KeyboardEvent.key` names. */
object Keys {
    private val NAMED = mapOf(
        GLFW.GLFW_KEY_ESCAPE to "Escape",
        GLFW.GLFW_KEY_ENTER to "Enter",
        GLFW.GLFW_KEY_KP_ENTER to "Enter",
        GLFW.GLFW_KEY_TAB to "Tab",
        GLFW.GLFW_KEY_BACKSPACE to "Backspace",
        GLFW.GLFW_KEY_INSERT to "Insert",
        GLFW.GLFW_KEY_DELETE to "Delete",
        GLFW.GLFW_KEY_RIGHT to "ArrowRight",
        GLFW.GLFW_KEY_LEFT to "ArrowLeft",
        GLFW.GLFW_KEY_DOWN to "ArrowDown",
        GLFW.GLFW_KEY_UP to "ArrowUp",
        GLFW.GLFW_KEY_PAGE_UP to "PageUp",
        GLFW.GLFW_KEY_PAGE_DOWN to "PageDown",
        GLFW.GLFW_KEY_HOME to "Home",
        GLFW.GLFW_KEY_END to "End",
        GLFW.GLFW_KEY_CAPS_LOCK to "CapsLock",
        GLFW.GLFW_KEY_SPACE to " ",
        GLFW.GLFW_KEY_LEFT_SHIFT to "Shift",
        GLFW.GLFW_KEY_RIGHT_SHIFT to "Shift",
        GLFW.GLFW_KEY_LEFT_CONTROL to "Control",
        GLFW.GLFW_KEY_RIGHT_CONTROL to "Control",
        GLFW.GLFW_KEY_LEFT_ALT to "Alt",
        GLFW.GLFW_KEY_RIGHT_ALT to "Alt",
        GLFW.GLFW_KEY_LEFT_SUPER to "Meta",
        GLFW.GLFW_KEY_RIGHT_SUPER to "Meta",
    )

    fun keyName(keyCode: Int, scanCode: Int, modifiers: Modifiers): String {
        NAMED[keyCode]?.let { return it }
        if (keyCode in GLFW.GLFW_KEY_F1..GLFW.GLFW_KEY_F25) return "F${keyCode - GLFW.GLFW_KEY_F1 + 1}"
        val name = try {
            GLFW.glfwGetKeyName(keyCode, scanCode)
        } catch (_: Throwable) {
            null
        }
        if (!name.isNullOrEmpty()) return if (modifiers.shift) name.uppercase() else name
        // No layout name (happens with some layouts/platforms): fall back to US letters so Ctrl+C/V/X/A keep working.
        if (keyCode in GLFW.GLFW_KEY_A..GLFW.GLFW_KEY_Z) {
            val c = 'a' + (keyCode - GLFW.GLFW_KEY_A)
            return if (modifiers.shift) c.uppercase() else c.toString()
        }
        return "Unidentified"
    }

    fun modifiers(glfwMods: Int) = Modifiers(
        shift = glfwMods and GLFW.GLFW_MOD_SHIFT != 0,
        ctrl = glfwMods and GLFW.GLFW_MOD_CONTROL != 0,
        alt = glfwMods and GLFW.GLFW_MOD_ALT != 0,
        meta = glfwMods and GLFW.GLFW_MOD_SUPER != 0,
    )

    /** Modifier keys held right now, for events Minecraft delivers without modifiers (the mouse wheel). */
    fun currentModifiers(): Modifiers {
        val window = Minecraft.getInstance().window
        fun down(vararg keys: Int) = keys.any { InputConstants.isKeyDown(window, it) }
        var mods = 0
        if (down(GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT)) mods = mods or GLFW.GLFW_MOD_SHIFT
        if (down(GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL)) mods = mods or GLFW.GLFW_MOD_CONTROL
        if (down(GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT)) mods = mods or GLFW.GLFW_MOD_ALT
        if (down(GLFW.GLFW_KEY_LEFT_SUPER, GLFW.GLFW_KEY_RIGHT_SUPER)) mods = mods or GLFW.GLFW_MOD_SUPER
        return modifiers(mods)
    }
}
