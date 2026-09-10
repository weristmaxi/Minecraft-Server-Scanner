package dev.jqve.serverscanner.screens;

import dev.jqve.serverscanner.mixin.MultiplayerScreenInvoker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.network.chat.Component;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class DeleteRegexScreen extends Screen {
    private final Screen parentScreen;
    private EditBox regexTextField;

    public DeleteRegexScreen(Screen parentScreen) {
        super(Component.literal("Delete Regex Screen"));
        this.parentScreen = parentScreen;
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parentScreen);
    }

    @Override
    protected void init() {
        // Initialize components
        regexTextField = new EditBox(this.font, width/2-100, 60, 200, 20, Component.literal(""));
        regexTextField.setHint(Component.literal(".* to delete all")); // Add placeholder text
        addRenderableWidget(regexTextField);

        // Add cancel button
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(width/2 - 100, 140, 90, 20)
                .build());

        addRenderableWidget(Button.builder(Component.literal("Delete"), b -> {
            String regex = regexTextField.getValue();
            deleteServers(regex);})
                .bounds(width/2 + 10, 140, 90, 20)
                .build());
    }

    private void deleteServers(String regex) {
        try {
            Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            this.regexTextField.setValue("Invalid regex");
            return;
        }
        Minecraft client = Minecraft.getInstance();
        ServerList serverList = ((JoinMultiplayerScreen) parentScreen).getServers();
        serverList.load();
        for (int i = 0; i < serverList.size(); i++) {
            if (serverList.get(i).name != null && serverList.get(i).name.matches(regex)) {
                serverList.remove(serverList.get(i));
                i--; // Adjust index after removal
            }
        }
        serverList.save();
        MultiplayerScreenInvoker invoker = (MultiplayerScreenInvoker) parentScreen;
        invoker.setServerList(serverList);
        invoker.invokeRefreshServerList();
    }

}
