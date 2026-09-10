package dev.jqve.serverscanner.mixin;

import dev.jqve.serverscanner.screens.DeleteRegexScreen;
import dev.jqve.serverscanner.screens.PortScannerScreen;
import dev.jqve.serverscanner.screens.ServerScannerScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(JoinMultiplayerScreen.class)
public class MultiplayerScreenMixin extends Screen {
    @Shadow @Final
    private Screen lastScreen;
    @Shadow @Final
    private HeaderAndFooterLayout layout;
    @Unique
    private Button serverScanner;
    @Unique
    private Button portScanner;
    @Unique
    private Button deleteAllServersButton;
    @Unique
    private Button deleteViaRegex;
    @Unique
    private Button toggleButtonSets;
    @Unique
    private boolean confirmDelete = false;
    @Unique
    private boolean isFirstScreen = true;

    private MultiplayerScreenMixin(Component title) {
        super(title);
    }

    @Inject(at = @At("HEAD"), method = "init")
    private void reserveScannerToolbar(CallbackInfo ci) {
        layout.setFooterHeight(96);
    }

    @Inject(at = @At("TAIL"), method = "init")
    public void init(CallbackInfo ci) {
        // Create your buttons in separate helper methods or inline, as shown:
        serverScanner = createServerScannerButton();
        portScanner = createPortScannerButton();
        deleteAllServersButton = createDeleteAllServersButton();
        deleteViaRegex = createDeleteViaRegexButton();

        // Toggle button switcher
        toggleButtonSets = Button.builder(Component.literal("<->"), button -> {
            isFirstScreen = !isFirstScreen;
            if (!isFirstScreen) {
                // Remove first set, add second
                this.removeWidget(serverScanner);
                this.removeWidget(portScanner);
                this.addRenderableWidget(deleteAllServersButton);
                this.addRenderableWidget(deleteViaRegex);
            } else {
                // Remove second set, add first
                this.removeWidget(deleteAllServersButton);
                this.removeWidget(deleteViaRegex);
                this.addRenderableWidget(serverScanner);
                this.addRenderableWidget(portScanner);
            }
        }).width(25).pos(115, this.height - 29).build();
        this.addRenderableWidget(toggleButtonSets);

        // Display correct screen on initialization
        if (isFirstScreen) {
            this.addRenderableWidget(serverScanner);
            this.addRenderableWidget(portScanner);
        } else {
            this.addRenderableWidget(deleteAllServersButton);
            this.addRenderableWidget(deleteViaRegex);
        }
        positionScannerToolbar();
    }

    private Button createServerScannerButton() {
        return Button.builder(Component.literal("Server Scanner"), button ->
                Minecraft.getInstance().setScreenAndShow(new ServerScannerScreen(this))
        ).width(100).pos(10, this.height - 54).build();
    }

    private Button createPortScannerButton() {
        return Button.builder(Component.literal("Port Scanner"), button ->
                Minecraft.getInstance().setScreenAndShow(new PortScannerScreen(this))
        ).width(100).pos(10, this.height - 29).build();
    }

    private Button createDeleteViaRegexButton() {
        return Button.builder(Component.literal("Delete via Regex"), button ->
                Minecraft.getInstance().setScreenAndShow(new DeleteRegexScreen(this))
        ).width(100).pos(10, this.height - 29).build();
    }

    private Button createDeleteAllServersButton() {
        return Button.builder(Component.literal("Delete All Servers"), button -> {
            if (!confirmDelete) {
                // First press: ask for confirmation
                confirmDelete = true;
                button.setMessage(Component.literal("Are you sure?"));
            } else {
                // Second press: delete all servers
                confirmDelete = false;
                // Additional logic to delete servers
                Minecraft client = Minecraft.getInstance();
                ServerList serverList = new ServerList(client);
                serverList.load();
                while (serverList.size() > 0) {
                    serverList.remove(serverList.get(0));
                }
                serverList.save();
                MultiplayerScreenInvoker invoker = (MultiplayerScreenInvoker) this;
                invoker.setServerList(serverList);
                client.setScreenAndShow(new JoinMultiplayerScreen(lastScreen));
                button.setMessage(Component.literal("Delete All Servers"));
            }
        }).width(100).pos(10, this.height - 54).build();
    }

    @Inject(at = @At("TAIL"), method = "repositionElements")
    private void repositionScannerButtons(CallbackInfo ci) {
        if (serverScanner == null) return;
        positionScannerToolbar();
    }

    @Unique
    private void positionScannerToolbar() {
        int left = (this.width - 233) / 2;
        int y = this.height - 24;
        serverScanner.setPosition(left, y);
        deleteAllServersButton.setPosition(left, y);
        portScanner.setPosition(left + 104, y);
        deleteViaRegex.setPosition(left + 104, y);
        toggleButtonSets.setPosition(left + 208, y);
    }
}
