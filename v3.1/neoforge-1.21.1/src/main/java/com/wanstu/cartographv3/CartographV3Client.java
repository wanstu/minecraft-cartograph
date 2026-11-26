package com.wanstu.cartographv3;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

import java.io.IOException;
import java.io.UncheckedIOException;

@Mod(value = MapProtocol.MOD_ID, dist = Dist.CLIENT)
public final class CartographV3Client {
    public CartographV3Client(IEventBus modBus) {
        MapTransport.bindClientSender(PacketDistributor::sendToServer);
        modBus.addListener(CartographV3Client::registerKeys);
        modBus.addListener(CartographV3Client::registerShaders);
        NeoForge.EVENT_BUS.addListener(CartographV3Client::tick);
    }

    private static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(CartographClient.OPEN_KEY);
    }

    private static void registerShaders(RegisterShadersEvent event) {
        try {
            var shader = new ShaderInstance(event.getResourceProvider(), MapGraphics.ICON_SHADER,
                    DefaultVertexFormat.POSITION_TEX_COLOR);
            event.registerShader(shader, MapGraphics::setIconShader);
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not load the Cartograph map icon shader", failure);
        }
    }

    private static void tick(ClientTickEvent.Post event) {
        CartographClient.tick(Minecraft.getInstance());
    }
}
