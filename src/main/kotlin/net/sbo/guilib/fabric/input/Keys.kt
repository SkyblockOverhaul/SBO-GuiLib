package net.sbo.guilib.fabric.input

import net.sbo.guilib.core.event.Modifiers
import net.minecraft.client.input.KeyEvent
//#if MC >= 26.3
//$$ import org.lwjgl.sdl.SDLKeyboard
//$$ import org.lwjgl.sdl.SDLKeycode
//$$ import org.lwjgl.sdl.SDLMouse
//$$ import org.lwjgl.sdl.SDLScancode.*
//#else
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.Minecraft
import org.lwjgl.glfw.GLFW
//#endif

/**
 * Maps Minecraft's key and mouse input to DOM names. Up to 26.2 Minecraft delivers GLFW key codes; from 26.3 on it
 * delivers SDL scancodes (the physical key, [KeyEvent.key]) plus SDL key codes (layout dependent, `KeyEvent.keycode`).
 */
object Keys {
    //#if MC >= 26.3
    //$$ private val NAMED = mapOf(
    //$$     SDL_SCANCODE_ESCAPE to "Escape",
    //$$     SDL_SCANCODE_RETURN to "Enter",
    //$$     SDL_SCANCODE_KP_ENTER to "Enter",
    //$$     SDL_SCANCODE_TAB to "Tab",
    //$$     SDL_SCANCODE_BACKSPACE to "Backspace",
    //$$     SDL_SCANCODE_INSERT to "Insert",
    //$$     SDL_SCANCODE_DELETE to "Delete",
    //$$     SDL_SCANCODE_RIGHT to "ArrowRight",
    //$$     SDL_SCANCODE_LEFT to "ArrowLeft",
    //$$     SDL_SCANCODE_DOWN to "ArrowDown",
    //$$     SDL_SCANCODE_UP to "ArrowUp",
    //$$     SDL_SCANCODE_PAGEUP to "PageUp",
    //$$     SDL_SCANCODE_PAGEDOWN to "PageDown",
    //$$     SDL_SCANCODE_HOME to "Home",
    //$$     SDL_SCANCODE_END to "End",
    //$$     SDL_SCANCODE_CAPSLOCK to "CapsLock",
    //$$     SDL_SCANCODE_SPACE to " ",
    //$$     SDL_SCANCODE_LSHIFT to "Shift",
    //$$     SDL_SCANCODE_RSHIFT to "Shift",
    //$$     SDL_SCANCODE_LCTRL to "Control",
    //$$     SDL_SCANCODE_RCTRL to "Control",
    //$$     SDL_SCANCODE_LALT to "Alt",
    //$$     SDL_SCANCODE_RALT to "Alt",
    //$$     SDL_SCANCODE_LGUI to "Meta",
    //$$     SDL_SCANCODE_RGUI to "Meta",
    //$$ )
    //$$
    //$$ /** The button number Minecraft uses for the left mouse button. */
    //$$ val MOUSE_LEFT: Int = SDLMouse.SDL_BUTTON_LEFT
    //$$
    //$$ fun keyName(event: KeyEvent, modifiers: Modifiers): String {
    //$$     val scancode = event.key()
    //$$     NAMED[scancode]?.let { return it }
    //$$     if (scancode in SDL_SCANCODE_F1..SDL_SCANCODE_F12) return "F${scancode - SDL_SCANCODE_F1 + 1}"
    //$$     if (scancode in SDL_SCANCODE_F13..SDL_SCANCODE_F24) return "F${scancode - SDL_SCANCODE_F13 + 13}"
    //$$     // Printable keys: the SDL key code is the code point of the key's unshifted character in the current layout.
    //$$     val keycode = event.keycode()
    //$$     if (keycode >= 0x20 && keycode and SDLKeycode.SDLK_SCANCODE_MASK == 0 && Character.isValidCodePoint(keycode)) {
    //$$         val name = String(Character.toChars(keycode))
    //$$         return if (modifiers.shift) name.uppercase() else name
    //$$     }
    //$$     // No layout name: fall back to US letters so Ctrl+C/V/X/A keep working.
    //$$     if (scancode in SDL_SCANCODE_A..SDL_SCANCODE_Z) {
    //$$         val c = 'a' + (scancode - SDL_SCANCODE_A)
    //$$         return if (modifiers.shift) c.uppercase() else c.toString()
    //$$     }
    //$$     return "Unidentified"
    //$$ }
    //$$
    //$$ fun modifiers(mods: Int) = Modifiers(
    //$$     shift = mods and SDLKeycode.SDL_KMOD_SHIFT != 0,
    //$$     ctrl = mods and SDLKeycode.SDL_KMOD_CTRL != 0,
    //$$     alt = mods and SDLKeycode.SDL_KMOD_ALT != 0,
    //$$     meta = mods and SDLKeycode.SDL_KMOD_GUI != 0,
    //$$ )
    //$$
    //$$ /** Modifier keys held right now, for events Minecraft delivers without modifiers (the mouse wheel). */
    //$$ fun currentModifiers(): Modifiers = modifiers(SDLKeyboard.SDL_GetModState().toInt())
    //$$
    //$$ /** Minecraft's mouse button number → DOM `MouseEvent.button` (SDL: 1 left, 2 middle, 3 right, 4/5 back/forward). */
    //$$ fun domButton(button: Int) = button - 1
    //#else
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

    /** The button number Minecraft uses for the left mouse button. */
    val MOUSE_LEFT: Int = GLFW.GLFW_MOUSE_BUTTON_LEFT

    fun keyName(event: KeyEvent, modifiers: Modifiers): String {
        val keyCode = event.key()
        NAMED[keyCode]?.let { return it }
        if (keyCode in GLFW.GLFW_KEY_F1..GLFW.GLFW_KEY_F25) return "F${keyCode - GLFW.GLFW_KEY_F1 + 1}"
        val name = try {
            GLFW.glfwGetKeyName(keyCode, event.scancode())
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

    /** Minecraft's mouse button number → DOM `MouseEvent.button` (GLFW: 0 left, 1 right, 2 middle). */
    fun domButton(button: Int) = when (button) {
        GLFW.GLFW_MOUSE_BUTTON_LEFT -> 0
        GLFW.GLFW_MOUSE_BUTTON_MIDDLE -> 1
        GLFW.GLFW_MOUSE_BUTTON_RIGHT -> 2
        else -> button
    }
    //#endif
}
