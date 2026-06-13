package me.cortex.voxy.client.mixin.sodium;

import me.cortex.voxy.client.config.IConfigPageSetter;
import net.caffeinemc.mods.sodium.client.gui.SodiumOptionsGUI;
import net.caffeinemc.mods.sodium.client.gui.options.OptionPage;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;

@Mixin(SodiumOptionsGUI.class)
public abstract class MixinVideoSettingsScreen implements IConfigPageSetter {
    @Shadow(remap = false) @Final private List<OptionPage> pages;

    @Override
    public void voxy$setPageJump(OptionPage page) {
        for (int i = 0; i < this.pages.size(); i++) {
            if (this.pages.get(i) == page) {
                //TODO: implement jump to page if possible, or just ignore for now
                // Sodium 0.6 doesn't seem to have an easy way to jump to a specific page from code
                // without refactoring more.
                break;
            }
        }
    }
}
