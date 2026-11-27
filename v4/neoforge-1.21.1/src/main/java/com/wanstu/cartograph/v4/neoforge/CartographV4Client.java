package com.wanstu.cartograph.v4.neoforge;

import com.mojang.blaze3d.platform.InputConstants;
import com.wanstu.cartograph.v4.*;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

/** Integrated-server development map. Multiplayer transport remains separate. */
@Mod(value="cartograph_v4_dev",dist=Dist.CLIENT)
public final class CartographV4Client {
    private static final KeyMapping OPEN=new KeyMapping("key.cartograph_v4_dev.open",InputConstants.Type.KEYSYM,GLFW.GLFW_KEY_M,"key.categories.cartograph_v4_dev");
    private static V4MapSession session;
    private static final DimensionCameras CAMERAS=new DimensionCameras();
    private static V4ClientPreferences preferences;
    public CartographV4Client(IEventBus modBus){
        preferences=new V4ClientPreferences(FMLPaths.CONFIGDIR.get().resolve("cartograph-v4-display.dat"));
        modBus.addListener((RegisterKeyMappingsEvent e)->e.register(OPEN));
        NeoForge.EVENT_BUS.addListener(CartographV4Client::tick);
        NeoForge.EVENT_BUS.addListener(CartographV4Client::shutdown);
    }
    private static void releaseSession(){
        if(session==null)return;
        preferences.update(session.preferences());
        preferences.expedite();
        CAMERAS.remember(session.dimension,session.camera);
        session.close();session=null;
    }
    private static void shutdown(GameShuttingDownEvent event){
        if(session!=null)preferences.update(session.preferences());
        preferences.close();
    }
    private static void tick(ClientTickEvent.Post event){
        Minecraft mc=Minecraft.getInstance();
        var server=mc.getSingleplayerServer();
        WorldIoService io=server==null?null:CartographV4Dev.localPresentation(server);
        String dimension=mc.level==null?null:mc.level.dimension().location().toString();
        if(session!=null&&(io!=session.io||!session.dimension.equals(dimension))){
            if(mc.screen instanceof V4MapScreen||mc.screen instanceof V4ScaledScreen)mc.setScreen(null);
            releaseSession();
        }
        CAMERAS.world(server);
        if(session!=null){session.markers.tick();session.regions.tick();preferences.update(session.preferences());}
        preferences.tick();
        while(OPEN.consumeClick()){
            if(mc.player==null||io==null||dimension==null)continue;
            if(mc.screen instanceof V4MapScreen){mc.setScreen(null);continue;}
            if(mc.screen!=null)continue;
            if(session==null)session=new V4MapSession(io,dimension,preferences.current(),CAMERAS.recall(dimension));
            mc.setScreen(new V4MapScreen(session,mc.player.getX(),mc.player.getZ()));
        }
    }
}
