package me.cortex.voxy.client.mixin.sodium;

import net.caffeinemc.mods.sodium.client.gl.buffer.GlMutableBuffer;
import net.caffeinemc.mods.sodium.client.render.chunk.SharedQuadIndexBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = SharedQuadIndexBuffer.class, remap = false)
public interface AccessorSharedQuadIndexBuffer {
    @Accessor("buffer")
    GlMutableBuffer voxy$getBuffer();

    @Accessor("indexType")
    SharedQuadIndexBuffer.IndexType voxy$getIndexType();

    @Accessor("maxPrimitives")
    int voxy$getMaxPrimitives();

    @Accessor("maxPrimitives")
    void voxy$setMaxPrimitives(int maxPrimitives);
}
