package net.sbo.guilib.fabric

import net.fabricmc.api.ClientModInitializer
import org.slf4j.LoggerFactory

object GuiLibMod : ClientModInitializer {
    const val MOD_ID = "guilib"
    val logger = LoggerFactory.getLogger("GuiLib")

    override fun onInitializeClient() {
        logger.info("GuiLib initialized")
    }
}
