package net.sbo.guilib.fabric.input

import net.minecraft.client.input.KeyEvent
import net.sbo.guilib.core.event.Modifiers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
//#if MC >= 26.3
//$$ import org.lwjgl.sdl.SDLKeycode
//$$ import org.lwjgl.sdl.SDLMouse
//$$ import org.lwjgl.sdl.SDLScancode.*
//#else
import org.lwjgl.glfw.GLFW
//#endif

/** Minecraft's raw key and mouse input (GLFW up to 26.2, SDL from 26.3 on) → DOM names. */
class KeysTest {
    private val shift = Modifiers(shift = true)

    //#if MC >= 26.3
    //$$ private fun key(scancode: Int, keycode: Int = 0) = KeyEvent(scancode, keycode, 0)
    //$$
    //$$ @Test
    //$$ fun namedKeysUseThePhysicalKey() {
    //$$     assertEquals("Escape", Keys.keyName(key(SDL_SCANCODE_ESCAPE), Modifiers.NONE))
    //$$     assertEquals("Enter", Keys.keyName(key(SDL_SCANCODE_KP_ENTER), Modifiers.NONE))
    //$$     assertEquals("ArrowLeft", Keys.keyName(key(SDL_SCANCODE_LEFT, SDLKeycode.SDLK_LEFT), Modifiers.NONE))
    //$$     assertEquals(" ", Keys.keyName(key(SDL_SCANCODE_SPACE, ' '.code), Modifiers.NONE))
    //$$     assertEquals("F1", Keys.keyName(key(SDL_SCANCODE_F1), Modifiers.NONE))
    //$$     assertEquals("F13", Keys.keyName(key(SDL_SCANCODE_F13), Modifiers.NONE))
    //$$ }
    //$$
    //$$ @Test
    //$$ fun printableKeysFollowTheKeyboardLayout() {
    //$$     assertEquals("a", Keys.keyName(key(SDL_SCANCODE_A, 'a'.code), Modifiers.NONE))
    //$$     assertEquals("A", Keys.keyName(key(SDL_SCANCODE_A, 'a'.code), shift))
    //$$     // AZERTY: the key in the US "Q" position types "a"; Ctrl+A must still be "a".
    //$$     assertEquals("a", Keys.keyName(key(SDL_SCANCODE_Q, 'a'.code), Modifiers.NONE))
    //$$     assertEquals("ü", Keys.keyName(key(SDL_SCANCODE_LEFTBRACKET, 'ü'.code), Modifiers.NONE))
    //$$     // No layout character: US letters by position.
    //$$     assertEquals("c", Keys.keyName(key(SDL_SCANCODE_C), Modifiers.NONE))
    //$$     assertEquals("Unidentified", Keys.keyName(key(SDL_SCANCODE_PRINTSCREEN, SDLKeycode.SDLK_PRINTSCREEN), Modifiers.NONE))
    //$$ }
    //$$
    //$$ @Test
    //$$ fun modifiersAndMouseButtons() {
    //$$     val mods = Keys.modifiers(SDLKeycode.SDL_KMOD_RSHIFT or SDLKeycode.SDL_KMOD_LCTRL)
    //$$     assertTrue(mods.shift && mods.ctrl)
    //$$     assertFalse(mods.alt || mods.meta)
    //$$     assertTrue(Keys.modifiers(SDLKeycode.SDL_KMOD_LGUI).meta)
    //$$     assertEquals(0, Keys.domButton(SDLMouse.SDL_BUTTON_LEFT))
    //$$     assertEquals(1, Keys.domButton(SDLMouse.SDL_BUTTON_MIDDLE))
    //$$     assertEquals(2, Keys.domButton(SDLMouse.SDL_BUTTON_RIGHT))
    //$$     assertEquals(3, Keys.domButton(SDLMouse.SDL_BUTTON_X1))
    //$$     assertEquals(SDLMouse.SDL_BUTTON_LEFT, Keys.MOUSE_LEFT)
    //$$ }
    //#else
    private fun key(keyCode: Int) = KeyEvent(keyCode, 0, 0)

    // Printable keys go through glfwGetKeyName, which needs a running GLFW, so only named keys are tested here.
    @Test
    fun namedKeys() {
        assertEquals("Escape", Keys.keyName(key(GLFW.GLFW_KEY_ESCAPE), Modifiers.NONE))
        assertEquals("Enter", Keys.keyName(key(GLFW.GLFW_KEY_KP_ENTER), Modifiers.NONE))
        assertEquals("ArrowLeft", Keys.keyName(key(GLFW.GLFW_KEY_LEFT), Modifiers.NONE))
        assertEquals("Shift", Keys.keyName(key(GLFW.GLFW_KEY_RIGHT_SHIFT), shift))
        assertEquals("F13", Keys.keyName(key(GLFW.GLFW_KEY_F13), Modifiers.NONE))
    }

    @Test
    fun modifiersAndMouseButtons() {
        val mods = Keys.modifiers(GLFW.GLFW_MOD_SHIFT or GLFW.GLFW_MOD_CONTROL)
        assertTrue(mods.shift && mods.ctrl)
        assertFalse(mods.alt || mods.meta)
        assertEquals(0, Keys.domButton(GLFW.GLFW_MOUSE_BUTTON_LEFT))
        assertEquals(1, Keys.domButton(GLFW.GLFW_MOUSE_BUTTON_MIDDLE))
        assertEquals(2, Keys.domButton(GLFW.GLFW_MOUSE_BUTTON_RIGHT))
        assertEquals(GLFW.GLFW_MOUSE_BUTTON_LEFT, Keys.MOUSE_LEFT)
    }
    //#endif
}
