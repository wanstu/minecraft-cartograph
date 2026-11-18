package com.wanstu.cartographv3;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

@Mod(value = MapProtocol.MOD_ID, dist = Dist.CLIENT)
public final class CartographV3Client {
    public CartographV3Client(IEventBus modBus) {
        MapTransport.bindClientSender(PacketDistributor::sendToServer);
        modBus.addListener(CartographV3Client::registerKeys);
        NeoForge.EVENT_BUS.addListener(CartographV3Client::tick);
    }

    private static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(CartographClient.OPEN_KEY);
    }

    private static void tick(ClientTickEvent.Post event) {
        CartographClient.tick(Minecraft.getInstance());
    }
}
