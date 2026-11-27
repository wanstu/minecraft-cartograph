package com.wanstu.cartograph.v4.neoforge;

import com.mojang.blaze3d.platform.InputConstants;
import com.wanstu.cartograph.v4.WorldIoService;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

/** Integrated-server development map. Multiplayer transport remains separate. */
@Mod(value="cartograph_v4_dev",dist=Dist.CLIENT)
public final class CartographV4Client {
    private static final KeyMapping OPEN=new KeyMapping("key.cartograph_v4_dev.open",InputConstants.Type.KEYSYM,GLFW.GLFW_KEY_M,"key.categories.cartograph_v4_dev");
    private static V4MapSession session;
    public CartographV4Client(IEventBus modBus){
        modBus.addListener((RegisterKeyMappingsEvent e)->e.register(OPEN));
        NeoForge.EVENT_BUS.addListener(CartographV4Client::tick);
    }
    private static void tick(ClientTickEvent.Post event){
        Minecraft mc=Minecraft.getInstance();
        var server=mc.getSingleplayerServer();
        WorldIoService io=server==null?null:CartographV4Dev.localPresentation(server);
        String dimension=mc.level==null?null:mc.level.dimension().location().toString();
        if(session!=null&&(io!=session.io||!session.dimension.equals(dimension))){
            if(mc.screen instanceof V4MapScreen)mc.setScreen(null);
            session.close();session=null;
        }
        while(OPEN.consumeClick()){
            if(mc.player==null||io==null||dimension==null)continue;
            if(mc.screen instanceof V4MapScreen){mc.setScreen(null);continue;}
            if(mc.screen!=null)continue;
            if(session==null)session=new V4MapSession(io,dimension);
            mc.setScreen(new V4MapScreen(session,mc.player.getX(),mc.player.getZ()));
        }
    }
}
