package dev.jqve.serverscanner;

import dev.jqve.serverscanner.mixin.MultiplayerScreenInvoker;
import dev.jqve.serverscanner.screens.PortScannerScreen;
import dev.jqve.serverscanner.screens.ServerScannerScreen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MinecraftCompatibilityTest {
    @Test
    void multiplayerMixinsApplyToCurrentMinecraft() {
        // Loading the target through Fabric applies and validates both mixins,
        // including private field accessors and the add-server callback invoker.
        assertTrue(MultiplayerScreenInvoker.class.isAssignableFrom(JoinMultiplayerScreen.class));
    }

    @Test
    void scannerScreensImplementCurrentLifecycleMethods() throws NoSuchMethodException {
        for (Class<?> screen : new Class<?>[]{PortScannerScreen.class, ServerScannerScreen.class}) {
            screen.getDeclaredMethod("extractRenderState", GuiGraphicsExtractor.class,
                    int.class, int.class, float.class);
            screen.getDeclaredMethod("resize", int.class, int.class);
        }
        ServerScannerScreen.class.getDeclaredMethod("mouseScrolled",
                double.class, double.class, double.class, double.class);
    }
}
