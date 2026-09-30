package net.sbo.guilib.fabric.input

import net.sbo.guilib.core.event.Modifiers
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
        return "Unidentified"
    }

    fun modifiers(glfwMods: Int) = Modifiers(
        shift = glfwMods and GLFW.GLFW_MOD_SHIFT != 0,
        ctrl = glfwMods and GLFW.GLFW_MOD_CONTROL != 0,
        alt = glfwMods and GLFW.GLFW_MOD_ALT != 0,
        meta = glfwMods and GLFW.GLFW_MOD_SUPER != 0,
    )
}
