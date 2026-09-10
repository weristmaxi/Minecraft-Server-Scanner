package dev.jqve.serverscanner.mixin;

import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(JoinMultiplayerScreen.class)
public interface MultiplayerScreenInvoker {

    @Invoker("refreshServerList")
    void invokeRefreshServerList();

    @Accessor("servers")
    void setServerList(ServerList serverList);

    @Accessor("editingServer")
    void setSelectedEntry(ServerData selectedEntry);

    @Invoker("addServerCallback")
    void invokeAddEntry(boolean confirmedAction);
}
