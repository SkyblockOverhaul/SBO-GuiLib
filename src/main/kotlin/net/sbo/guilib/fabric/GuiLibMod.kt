package net.sbo.guilib.fabric

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.minecraft.client.Minecraft
import net.sbo.guilib.core.Log
import net.sbo.guilib.fabric.dev.DevAutomation
import net.sbo.guilib.fabric.render.GuiPipelines
import net.sbo.guilib.fabric.resources.Stylesheets
import net.sbo.guilib.fabric.showcase.Showcase
import org.slf4j.LoggerFactory

object GuiLibMod : ClientModInitializer {
    const val MOD_ID = "guilib"
    val logger = LoggerFactory.getLogger("GuiLib")

    override fun onInitializeClient() {
        Log.sink = { level, msg ->
            when (level) {
                Log.Level.DEBUG -> logger.debug(msg)
                Log.Level.INFO -> logger.info(msg)
                Log.Level.WARN -> logger.warn(msg)
                Log.Level.ERROR -> logger.error(msg)
            }
        }

        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                ClientCommands.literal("guilib")
                    .then(ClientCommands.literal("showcase").executes {
                        // Open after the chat screen has closed.
                        Minecraft.getInstance().schedule { Showcase.open() }
                        1
                    })
                    .then(ClientCommands.literal("reload").executes {
                        Minecraft.getInstance().schedule { Stylesheets.reloadAll() }
                        1
                    }),
            )
        }
        GuiPipelines.init()
        DevAutomation.init()
        logger.info("GuiLib initialized")
    }
}
