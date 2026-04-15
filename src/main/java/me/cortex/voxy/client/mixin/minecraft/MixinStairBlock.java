package me.cortex.voxy.client.mixin.minecraft;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import me.cortex.voxy.client.core.util.StairBlockCooked;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

@Mixin(StairBlock.class)
public class MixinStairBlock implements StairBlockCooked {
    @Shadow
    BlockState baseState;

    @Override
    public BlockState setBaseWaterlogged(BlockState blockState) {
        if (baseState.hasProperty(BlockStateProperties.WATERLOGGED)) {
            return baseState.setValue(BlockStateProperties.WATERLOGGED,
                    blockState.getValue(BlockStateProperties.WATERLOGGED));
        } else {
            return baseState;
        }
    }
}
