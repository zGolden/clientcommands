package net.earthcomputer.clientcommands.mixin;

import net.earthcomputer.clientcommands.interfaces.ITextFieldWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(TextFieldWidget.class)
public abstract class MixinTextFieldWidget implements ITextFieldWidget {

    @Accessor("maxLength")
    @Override
    public abstract int clientcommands_getMaxLength();
}
