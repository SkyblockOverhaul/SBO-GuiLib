package net.sbo.guilib.fabric.entity

import com.mojang.authlib.GameProfile
import net.minecraft.client.Minecraft
import net.minecraft.client.entity.ClientMannequin
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.world.entity.decoration.Mannequin
import net.minecraft.world.entity.player.PlayerModelPart
import net.minecraft.world.entity.player.PlayerSkin
import net.minecraft.world.item.component.ResolvableProfile
import net.minecraft.world.scores.PlayerTeam
import net.minecraft.world.scores.Scoreboard
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * A client-only player model for the `entity` tag: `entity(FakePlayer.of("Notch") ?: return)`.
 * It is never added to the world, so it does not tick or move; the name tag and cape are hidden.
 * All factories need a loaded world and return `null` on the title screen.
 *
 * Based on SkyHanni's `FakePlayer` (https://github.com/hannibal002/SkyHanni, LGPL-2.1), extended to load
 * any player's skin from a name, UUID or profile.
 */
class FakePlayer private constructor(
    level: ClientLevel,
    /** Player whose skin and model parts are mirrored; `null` uses the profile's skin. */
    private val source: AbstractClientPlayer?,
) : ClientMannequin(level, Minecraft.getInstance().playerSkinRenderCache()) {

    init {
        // Unique negative ids keep fake entities apart from real ones (and from each other) in render state caches.
        id = nextId.getAndDecrement()
    }

    // ClientMannequin only applies a looked-up skin in tick(), which never runs for an entity outside the world.
    // The skin cache returns the default skin until the lookup finishes (like player heads).
    override fun getSkin(): PlayerSkin = source?.skin ?: Minecraft.getInstance().playerSkinRenderCache().getOrDefault(profile).playerSkin()

    override fun getTeam(): PlayerTeam = object : PlayerTeam(Scoreboard(), "") {
        override fun getNameTagVisibility() = Visibility.NEVER
    }

    override fun isModelPartShown(part: PlayerModelPart): Boolean =
        part != PlayerModelPart.CAPE && (source?.isModelPartShown(part) ?: super.isModelPartShown(part))

    private fun setProfile(profile: ResolvableProfile) = apply {
        entityData.set(Mannequin.DATA_PROFILE, profile)
    }

    companion object {
        private val nextId = AtomicInteger(-1)

        private fun level(): ClientLevel? = Minecraft.getInstance().level

        /** Mirrors [player] (skin and visible model parts). */
        fun of(player: AbstractClientPlayer): FakePlayer? = level()?.let { FakePlayer(it, player) }

        /** Mirrors the local player. */
        fun ofLocalPlayer(): FakePlayer? = Minecraft.getInstance().player?.let { of(it) }

        /** Shows the skin of the player called [name] (looked up through Mojang's API). */
        fun of(name: String): FakePlayer? = level()?.let { FakePlayer(it, null).setProfile(ResolvableProfile.createUnresolved(name)) }

        /** Shows the skin of the player with [uuid]. */
        fun of(uuid: UUID): FakePlayer? = level()?.let { FakePlayer(it, null).setProfile(ResolvableProfile.createUnresolved(uuid)) }

        /** Shows the skin of [profile] (textures from the profile's properties if present). */
        fun of(profile: GameProfile): FakePlayer? = level()?.let { FakePlayer(it, null).setProfile(ResolvableProfile.createResolved(profile)) }
    }
}
